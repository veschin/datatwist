# GraalVM Native Image Build — Design

**Date:** 2026-02-23
**Status:** Approved
**Research:** `docs/graalvm-research.md`

---

## Scope

Build DataTwist as a standalone native binary via GraalVM `native-image`, with uberjar as fallback. Full CLI with subcommands. Full Java interop via tracing agent.

## Architecture

```
datatwist.main (CLI entry point, :gen-class)
  ├── run <file.dt>     — execute .dt file, print result
  ├── eval -e 'expr'    — evaluate expression, print result
  ├── repl              — interactive read-eval-print loop
  ├── fmt               — stub (exits 1, "not implemented")
  └── (bare)            — defaults to REPL
```

## Components

### 1. `src/datatwist/main.clj` — CLI Entry Point

- `(:gen-class)` for AOT compilation
- `tools.cli` for argument parsing
- Subcommand dispatch: `run`, `eval`, `repl`, `fmt`
- `run <file>`: read file, eval with `datatwist.parser/eval-dt`, print result
- `eval -e <expr>`: eval expression, print result
- `repl`: loop of `print-prompt → read-line → eval-dt → print-result`, catches exceptions and prints error via `error_renderer`
- `fmt`: prints "Not implemented yet" to stderr, exits 1
- Bare invocation (no args, no subcommand): launches REPL
- `--help` / `-h`: print usage
- `--version` / `-v`: print version string
- Exit codes: 0 success, 1 runtime error, 2 parse error, 3 usage error

### 2. `parser.clj` — defparser Migration

Replace:
```clojure
(def parser (insta/parser (io/resource "datatwist.grammar")))
```

With:
```clojure
(insta/defparser parser (slurp (io/resource "datatwist.grammar")))
```

Grammar is compiled into bytecode at AOT time. No resource loading needed at runtime. The `io/resource` call happens at macro-expansion time during AOT.

### 3. `build.clj` — tools.build Script

- `clean`: delete `target/`
- `uber`: AOT-compile all namespaces, produce `target/datatwist-VERSION-standalone.jar`
- Main class: `datatwist.main`
- Includes `graal-build-time` in the classpath for native builds

### 4. `deps.edn` Aliases

```clojure
:build {:deps {io.github.clojure/tools.build {:mvn/version "0.10.5"}}
        :ns-default build}
:native {:extra-deps {com.github.clj-easy/graal-build-time {:mvn/version "1.0.5"}}}
```

`tools.cli` added as a main dependency (needed for CLI arg parsing in the distributed binary).

### 5. GraalVM Configuration

Location: `META-INF/native-image/datatwist/`

- `native-image.properties`: default flags (`--no-fallback`, `--features=...`, report unsupported at runtime)
- `reflect-config.json`: generated via tracing agent, curated
- `resource-config.json`: include `datatwist.grammar` (fallback if defparser has issues)

Tracing workflow:
1. Build uberjar
2. Run test suite + demo with `-agentlib:native-image-agent=config-output-dir=...`
3. Curate: remove test-only classes, keep evaluator/stdlib reflection sites
4. Commit curated configs

### 6. Makefile Targets

- `make uberjar`: `clj -T:build uber`
- `make native`: `clj -T:build uber && native-image -jar target/datatwist-*-standalone.jar -o datatwist`
- Updated `make clean`: also removes `target/`

### 7. Interop Strategy

Full interop via tracing agent. Run against:
- Full test suite (761 tests)
- Demo runner
- Manual test script exercising Java interop (`Math/PI`, `String` methods, etc.)

This captures all reflection sites used by the evaluator's `Class/forName`, `Reflector` calls, and `require` statements. Users who need classes not in the config use the uberjar.

## Not In Scope

- CI/CD pipeline (follow-up)
- PGO / static linking (follow-up)
- REPL history persistence / tab completion (follow-up, see BACKLOG P2)
- `fmt` implementation (follow-up, see BACKLOG P2)
- Windows support (follow-up)

## Success Criteria

1. `make uberjar` produces a working standalone jar
2. `java -jar target/datatwist-*-standalone.jar eval -e '"hello" |> upcase'` → `"HELLO"`
3. `make native` produces a working native binary (requires GraalVM installed)
4. `./datatwist eval -e '"hello" |> upcase'` → `"HELLO"` with startup < 50ms
5. `./datatwist run examples/demo.dt` executes successfully
6. `./datatwist` launches REPL, can evaluate expressions interactively
7. All 761 existing tests still pass on JVM
