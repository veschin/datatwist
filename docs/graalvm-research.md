# GraalVM Native Image Research for DataTwist

**Date:** 2026-02-19
**Status:** Research complete, ready for prototyping

---

## 1. Executive Summary

**Verdict: CONDITIONAL GO** -- feasible with targeted workarounds.

DataTwist can be compiled to a GraalVM native binary. The core challenge is not
Instaparse itself (which does NOT use `clojure.core/eval` internally), but rather
DataTwist's own evaluator, which uses `Class/forName`, `clojure.lang.Reflector`,
`requiring-resolve`, and dynamic `require` for Java interop and Clojure namespace
loading at runtime. These features conflict with GraalVM's closed-world assumption.

The path forward is:

1. Use `instaparse.core/defparser` macro to pre-compile the grammar at AOT time.
2. Add `graal-build-time` for Clojure class initialization.
3. Generate reflection and resource configs via the native-image tracing agent.
4. Accept that Java interop features (`Class/forName`, `Reflector`, dynamic
   `require`) will work only for classes explicitly registered in reflection
   config -- or scope the native binary to "pure DataTwist" mode without
   arbitrary Java interop.

Expected startup time: **10-50ms** (vs 1-3s on JVM).

---

## 2. Clojure + GraalVM State of the Art

### What Works

- **Babashka** proves the model: a native Clojure scripting runtime achieving
  ~30ms startup. It ships with SCI (Small Clojure Interpreter) for runtime eval,
  plus pre-compiled libraries for performance.
  ([babashka/babashka](https://github.com/babashka/babashka))

- **clj-easy ecosystem** provides battle-tested tooling:
  - [`graal-build-time`](https://github.com/clj-easy/graal-build-time) --
    auto-registers Clojure classes for build-time initialization (replaces the
    deprecated `--initialize-at-build-time` flag).
  - [`graal-docs`](https://github.com/clj-easy/graal-docs) -- community
    knowledge base of tips and workarounds.
  - [`graalvm-clojure`](https://github.com/clj-easy/graalvm-clojure) --
    hello-world projects verifying library compatibility (35+ libraries tested).
  - [`graal-config`](https://github.com/clj-easy/graal-config) -- pre-built
    native-image config for popular libraries.

- **Standard Clojure** compiles natively without issues as long as you avoid:
  `eval`, dynamic class loading, `locking` macro (fixed in recent Clojure),
  and unresolved reflection.

### What Does NOT Work

- **`clojure.core/eval`** -- fundamentally incompatible. Eval generates new
  classes at runtime via a dynamic classloader; native-image's closed-world
  assumption prohibits this.
  ([GraalVM discussion](https://groups.google.com/g/clojure/c/LMbNOg67wcw))

- **Dynamic `require` / `requiring-resolve`** -- requires classloading at
  runtime, which native-image cannot support for classes not in the image.

- **Unrestricted `Class/forName`** -- only works for classes explicitly
  registered in reflection config at build time.

- **`clojure.lang.Reflector`** -- had MethodHandle issues on JDK11+ but
  largely fixed in GraalVM v21+. Still requires reflection config for target
  classes.
  ([borkdude/clj-reflector-graal-java11-fix](https://github.com/borkdude/clj-reflector-graal-java11-fix))

---

## 3. Instaparse-Specific Challenges

### Good News: No `eval` in Instaparse

Analysis of the [Instaparse source code](https://github.com/Engelberg/instaparse/blob/master/src/instaparse/core.cljc)
confirms:

- The `parser` function does NOT use `clojure.core/eval`. It builds the parser
  via standard function calls (`cfg/build-parser`, `abnf/build-parser`).
- No usage of `proxy`, `reify`, `Class/forName`, or Java reflection in the
  core parser creation path.
- Instaparse is a `.cljc` library (works in both CLJ and CLJS), which implies
  it avoids JVM-only dynamic features.

### The `defparser` Macro

Instaparse provides `defparser`, a macro that:
1. Parses the grammar specification at **macro-expansion time** (compile time).
2. Uses `walk/postwalk` to transform the grammar into code that constructs a
   `Parser` record directly.
3. Eliminates the runtime cost of grammar parsing entirely.

This is ideal for native-image: the parser data structure is fully materialized
at AOT compile time and baked into the native binary.

### Resource Loading (`io/resource`)

DataTwist currently loads the grammar at runtime:
```clojure
(def parser
  (insta/parser
   (io/resource "datatwist.grammar")))
```

This uses `clojure.java.io/resource` which calls `ClassLoader.getResource()`.
In native-image, classpath resources are NOT included by default. Two options:

1. **Resource config**: Include the grammar file via `resource-config.json`.
2. **`defparser` macro**: Eliminate the runtime resource load entirely by
   pre-compiling the grammar at AOT time (preferred approach).

### Instaparse NOT in clj-easy Registry

Instaparse is not listed in the
[clj-easy/graalvm-clojure](https://github.com/clj-easy/graalvm-clojure) tested
library list. This means we would be among the first to verify it. However,
given that Instaparse does not use `eval` or reflection, the risk is low.

---

## 4. AOT Strategy

### Option A: `defparser` Macro (Recommended)

Replace the runtime parser creation with the compile-time `defparser` macro:

```clojure
;; Before (runtime):
(def parser
  (insta/parser (io/resource "datatwist.grammar")))

;; After (compile-time):
(insta/defparser parser
  (slurp (io/resource "datatwist.grammar")))
```

**Note:** `defparser` requires a string literal or something resolvable at
macro-expansion time. Since the grammar is in a resource file, we may need to
either:

- Use `slurp` + `io/resource` inside the macro call (the macro evaluates its
  argument at compile time, so this should work during AOT).
- Inline the grammar as a string literal in the source file (less maintainable).
- Use a build step that reads the grammar and generates a `.clj` file containing
  `defparser` with the inlined grammar string.

### Option B: Resource Config Fallback

If `defparser` proves difficult, keep the runtime `(insta/parser ...)` call but
ensure the grammar file is included in the native image via resource config:

```json
{
  "resources": {
    "includes": [
      {"pattern": "datatwist\\.grammar"}
    ]
  }
}
```

Combined with `--initialize-at-build-time` (or `graal-build-time`), the parser
`def` will be initialized during image build, effectively pre-compiling it.

### Option C: Serialized Parser

Pre-build the parser in a build step, serialize it (e.g., via `pr-str` or
Nippy), and deserialize at startup. This adds complexity without clear benefit
over Option A.

**Recommendation:** Start with Option A (`defparser`). Fall back to Option B if
the macro has issues with resource loading during AOT.

---

## 5. Required Configuration

### 5.1 Reflection Config (`reflect-config.json`)

DataTwist's evaluator uses heavy reflection for Java interop:

| Code Pattern | Location | Issue |
|---|---|---|
| `Class/forName` | `evaluator.clj:288,315,319,1237` | Dynamic class lookup |
| `Reflector/getStaticField` | `evaluator.clj:291` | Reflective field access |
| `Reflector/invokeStaticMethod` | `evaluator.clj:294` | Reflective method calls |
| `Reflector/invokeInstanceMethod` | `evaluator.clj:305` | Reflective method calls |
| `Reflector/invokeConstructor` | `evaluator.clj:314,318` | Reflective constructor calls |
| `resolve` (symbol) | `stdlib.clj:250,259` | Var resolution |
| `requiring-resolve` | `parser.clj:18` | Dynamic namespace load |
| `clojure.core/require` | `evaluator.clj:1483`, `stdlib.clj:257` | Dynamic namespace load |

Minimum reflection config for core functionality (without Java interop):

```json
[
  {
    "name": "java.lang.reflect.AccessibleObject",
    "methods": [{"name": "canAccess"}]
  }
]
```

For Java interop support, every class accessible via DataTwist's `Class/forName`
must be registered. This is fundamentally open-ended -- you cannot predict which
classes users will reference.

### 5.2 Resource Config (`resource-config.json`)

```json
{
  "resources": {
    "includes": [
      {"pattern": "datatwist\\.grammar"}
    ]
  }
}
```

Only needed if NOT using `defparser` (Option B).

### 5.3 Native Image Flags

```
--features=clj_easy.graal_build_time.InitClojureClasses
--no-fallback
-H:ReflectionConfigurationFiles=reflect-config.json
-H:ResourceConfigurationFiles=resource-config.json
--report-unsupported-elements-at-runtime
```

### 5.4 File Layout

```
META-INF/
  native-image/
    datatwist/
      native-image.properties
      reflect-config.json
      resource-config.json
```

Or pass configs as CLI flags to `native-image`.

---

## 6. Build Pipeline

### Recommended Steps

```
deps.edn --> AOT compile --> uberjar --> native-image --> binary
```

### 6.1 Add Build Dependencies

```clojure
;; deps.edn additions
{:aliases
 {:build
  {:deps {io.github.clojure/tools.build {:mvn/version "0.10.5"}}
   :ns-default build}
  :native
  {:extra-deps {com.github.clj-easy/graal-build-time {:mvn/version "1.0.5"}}}}}
```

### 6.2 Create `build.clj`

```clojure
(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'datatwist/datatwist)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"
                                    :aliases [:native]})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                   :src-dirs ["src"]
                   :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'datatwist.main}))  ;; needs :gen-class
```

### 6.3 Create Main Entry Point

```clojure
(ns datatwist.main
  (:gen-class))

(defn -main [& args]
  ;; CLI entry point for native binary
  ...)
```

### 6.4 Build Commands

```bash
# Build uberjar
clj -T:build uber

# Generate native image
native-image \
  -jar target/datatwist-0.1.0-standalone.jar \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  --no-fallback \
  -H:ReflectionConfigurationFiles=reflect-config.json \
  -H:ResourceConfigurationFiles=resource-config.json \
  --report-unsupported-elements-at-runtime \
  -o datatwist

# Or use Makefile target
make native
```

### 6.5 Tracing Agent for Config Generation

```bash
# Run tests with tracing agent to discover reflection usage
java -agentlib:native-image-agent=config-output-dir=META-INF/native-image \
     -jar target/datatwist-0.1.0-standalone.jar \
     < test-inputs.dt

# Review and curate generated configs
```

---

## 7. Startup Time

### Community Benchmarks

| Runtime | Startup Time | Source |
|---|---|---|
| Clojure on JVM | 1000-3000ms | [Stuart Sierra 2019](https://stuartsierra.com/2019/12/21/clojure-start-time-in-2019/) |
| Babashka (native) | 10-40ms | [babashka.org](https://babashka.org/) |
| GraalVM native (simple) | <10ms | [GraalVM blog](https://medium.com/graalvm/babashka-how-graalvm-helped-create-a-fast-starting-scripting-environment-for-clojure-b0fcc38b0746) |
| Python 3 | 30-50ms | Community benchmarks |

### DataTwist Estimate

DataTwist's native binary should achieve **10-50ms** startup:

- The parser is pre-compiled at build time (no grammar parsing at startup).
- The stdlib is a static map of Clojure fns (no dynamic loading).
- The evaluator is a pure tree-walker (no JIT or class generation).

The main variable is image size. Babashka is ~25MB with many libraries included.
DataTwist should be smaller (only Instaparse + Clojure core), likely **15-25MB**.

The **<50ms target is realistic** and consistent with babashka's demonstrated
performance.

---

## 8. Uberjar Fallback

For platforms where native-image is unavailable (e.g., exotic architectures,
CI environments without GraalVM), ship the uberjar:

```bash
# Build
clj -T:build uber

# Run
java -jar target/datatwist-0.1.0-standalone.jar [args...]
```

### Uberjar Optimizations

- **CDS (Class Data Sharing):** Pre-generate a shared archive for faster JVM
  startup (~500ms instead of ~2s):
  ```bash
  java -XX:DumpLoadedClassList=classes.lst -jar datatwist.jar
  java -Xshare:dump -XX:SharedClassListFile=classes.lst -XX:SharedArchiveFile=app-cds.jsa -jar datatwist.jar
  java -Xshare:on -XX:SharedArchiveFile=app-cds.jsa -jar datatwist.jar
  ```

- **GraalVM JIT (non-native):** Run on GraalVM JDK for better peak performance
  without the native-image constraints.

### Distribution Strategy

| Artifact | Startup | Size | Java Interop | Platforms |
|---|---|---|---|---|
| Native binary | ~30ms | ~20MB | Limited (registered classes only) | Linux x86_64, macOS aarch64 |
| Uberjar | ~2s | ~5MB | Full | Any JVM 11+ |

Ship both. The native binary is the default for CLI usage; the uberjar is the
fallback and the distribution for library/embedding usage.

---

## 9. Recommended Approach

### Phase 1: Validate Instaparse Compatibility (1-2 days)

1. Switch `parser.clj` to use `defparser` macro.
2. Add `graal-build-time` dependency.
3. Create minimal `main.clj` with `:gen-class`.
4. Build uberjar with `tools.build`.
5. Attempt `native-image` with `--no-fallback`.
6. Fix errors iteratively (reflection config, resource config).
7. **Success criteria:** `./datatwist -e '"hello" |> upcase'` returns `"HELLO"`.

### Phase 2: Handle Dynamic Features (2-3 days)

1. Run the full test suite with the native-image tracing agent.
2. Curate reflection config for standard Java classes (`Math`, `Integer`,
   `String`, `System`, etc.).
3. Decide on Java interop scope for the native binary:
   - **Option A:** Register a fixed set of common Java classes. Users who need
     exotic classes use the uberjar.
   - **Option B:** Use SCI for a limited `eval`-like capability (significant
     effort, not recommended initially).
4. Replace `requiring-resolve` in `parser.clj:18` with a direct `require` +
   function reference (trivial fix).
5. Handle `clojure.core/require` in evaluator's `Require` node -- either
   pre-load supported namespaces or disable in native mode.

### Phase 3: Build Pipeline & Distribution (1-2 days)

1. Add `build.clj` and Makefile targets (`make uberjar`, `make native`).
2. Add CI job for native-image builds (GitHub Actions + GraalVM setup action).
3. Test on Linux x86_64 and macOS aarch64.
4. Document limitations of native mode vs. uberjar mode.

### Phase 4: Optimize (1 day)

1. Measure actual startup time.
2. Profile native binary with `--pgo` (Profile-Guided Optimization) if needed.
3. Trim image size with `--gc=epsilon` for short-lived CLI invocations.
4. Add `-H:+StaticExecutableWithDynamicLibC` for fully static Linux binaries.

---

## 10. Open Questions

### Must Resolve Before Implementation

1. **Does `defparser` work with `(slurp (io/resource ...))`?** The macro
   evaluates its argument at compile time. If AOT compilation has the resource
   on the classpath, this should work. Needs verification.

2. **What is the minimal reflection config?** Need to run the tracing agent
   against the test suite to discover all reflection sites. The evaluator's
   `Reflector` usage is extensive.

3. **How to handle DataTwist's `require` statement in native mode?** The
   language supports `require clojure.string as str` which calls
   `clojure.core/require` at runtime. In native mode, only pre-loaded
   namespaces would work. Need to define the supported set.

### Nice to Investigate

4. **Image size**: How large is the binary with Instaparse + Clojure core?
   Babashka is ~25MB with many libraries; DataTwist should be smaller.

5. **PGO benefit**: Would Profile-Guided Optimization meaningfully improve
   parser throughput for the native binary?

6. **Static linking**: Can we produce a fully static binary for Linux
   distribution (no glibc dependency)?

7. **Windows support**: GraalVM native-image on Windows requires Visual Studio
   build tools. Worth supporting?

8. **Instaparse version**: Current version is 1.5.0. Check if newer versions
   have any GraalVM-relevant changes or improvements.

---

## Sources

- [clj-easy/graalvm-clojure](https://github.com/clj-easy/graalvm-clojure) -- Library compatibility testing
- [clj-easy/graal-docs](https://github.com/clj-easy/graal-docs) -- Tips and workarounds for Clojure + GraalVM
- [clj-easy/graal-build-time](https://github.com/clj-easy/graal-build-time) -- Build-time class initialization
- [clj-easy/graal-config](https://github.com/clj-easy/graal-config) -- Pre-built native-image configs
- [babashka/babashka](https://github.com/babashka/babashka) -- Reference implementation of native Clojure
- [babashka/sci](https://github.com/babashka/sci) -- Small Clojure Interpreter for GraalVM contexts
- [Engelberg/instaparse](https://github.com/Engelberg/instaparse) -- Parser library source code
- [Instaparse core.cljc](https://github.com/Engelberg/instaparse/blob/master/src/instaparse/core.cljc) -- Confirms no eval usage
- [borkdude/clj-reflector-graal-java11-fix](https://github.com/borkdude/clj-reflector-graal-java11-fix) -- Reflector fix
- [borkdude/refl](https://github.com/borkdude/refl) -- Reflection config cleanup tool
- [GraalVM native-image docs](https://www.graalvm.org/latest/reference-manual/native-image/) -- Official reference
- [GraalVM resource config](https://www.graalvm.org/jdk21/reference-manual/native-image/dynamic-features/Resources/) -- Resource inclusion
- [GraalVM reflection config](https://www.graalvm.org/22.2/reference-manual/native-image/guides/build-with-reflection/) -- Reflection configuration
- [GraalVM tracing agent](https://www.graalvm.org/latest/reference-manual/native-image/guides/configure-with-tracing-agent/) -- Auto-config generation
- [Clojure tools.build guide](https://clojure.org/guides/tools_build) -- Uberjar build pipeline
- [Stuart Sierra: Clojure Start Time](https://stuartsierra.com/2019/12/21/clojure-start-time-in-2019/) -- JVM startup benchmarks
- [Babashka GraalVM blog post](https://medium.com/graalvm/babashka-how-graalvm-helped-create-a-fast-starting-scripting-environment-for-clojure-b0fcc38b0746) -- Native startup benchmarks
- [GraalVM eval incompatibility discussion](https://groups.google.com/g/clojure/c/LMbNOg67wcw) -- eval limitations
- [GraalVM oracle/graal#2214](https://github.com/oracle/graal/issues/2214) -- Reflector MethodHandle issue
- [Setup Clojure with GraalVM](https://shagunagrawal.me/setup-clojure-with-graalvm-for-native-image/) -- Step-by-step guide
- [taylorwood/clj.native-image](https://github.com/taylorwood/clj.native-image) -- deps.edn native-image tool
- [luchiniatwork/cambada](https://github.com/luchiniatwork/cambada) -- Alternative packager with GraalVM support
