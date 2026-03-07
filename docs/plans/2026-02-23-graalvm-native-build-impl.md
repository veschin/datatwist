# GraalVM Native Image Build — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build DataTwist as a standalone native binary with full CLI subcommands and uberjar fallback.

**Architecture:** CLI entry point (`datatwist.main`) dispatches to subcommands (run/eval/repl/fmt) using `tools.cli`. Grammar pre-compiled via `defparser`. Build via `tools.build` → uberjar → `native-image`.

**Tech Stack:** Clojure tools.build, tools.cli, GraalVM native-image, graal-build-time, Instaparse defparser

---

### Task 1: Add tools.cli dependency and build aliases to deps.edn

**Files:**
- Modify: `deps.edn`

**Step 1: Update deps.edn**

Replace the entire `deps.edn` with:

```clojure
{:deps {instaparse/instaparse {:mvn/version "1.5.0"}
        org.clojure/tools.cli {:mvn/version "1.1.230"}}
 :paths ["src" "test" "resources"]
 :aliases
 {:build
  {:deps {io.github.clojure/tools.build {:mvn/version "0.10.5"}}
   :ns-default build}
  :native
  {:extra-deps {com.github.clj-easy/graal-build-time {:mvn/version "1.0.5"}}}}}
```

**Step 2: Verify deps resolve**

Run: `clj -Sdeps '{:deps {}}' -Stree`
Expected: deps tree shows instaparse and tools.cli

**Step 3: Run existing tests to confirm no breakage**

Run: `clj -M -e "(require 'clojure.test 'datatwist.literals-test) (clojure.test/run-tests 'datatwist.literals-test)"`
Expected: all tests pass (no regressions from adding tools.cli dep)

**Step 4: Commit**

```bash
git add deps.edn
git commit -m "build: add tools.cli dep and build/native aliases to deps.edn"
```

---

### Task 2: Migrate parser.clj to defparser macro

**Files:**
- Modify: `src/datatwist/parser.clj`

**Step 1: Run parser tests before change**

Run: `clj -M -e "(require 'clojure.test 'datatwist.parser-test) (clojure.test/run-tests 'datatwist.parser-test)"`
Expected: all parser tests pass

**Step 2: Modify parser.clj**

Replace the entire file with:

```clojure
(ns datatwist.parser
  (:require [instaparse.core :as insta]
            [clojure.java.io :as io]))

(insta/defparser parser
  (slurp (io/resource "datatwist.grammar")))

(defn parse
  "Parse DataTwist source code. Returns parse tree or instaparse failure."
  [input]
  (parser input))

(defn eval-dt
  "Parse and evaluate DataTwist source code. Returns the result."
  [input]
  ((requiring-resolve 'datatwist.evaluator/evaluate) input))

(defn parse-error?
  "Returns true if the input fails to parse."
  [input]
  (insta/failure? (parser input)))
```

Key change: `(def parser (insta/parser (io/resource "datatwist.grammar")))` → `(insta/defparser parser (slurp (io/resource "datatwist.grammar")))`. The `defparser` macro pre-compiles the grammar at macro-expansion time (AOT compile time), eliminating runtime grammar parsing.

**Step 3: Run parser tests after change**

Run: `clj -M -e "(require 'clojure.test 'datatwist.parser-test) (clojure.test/run-tests 'datatwist.parser-test)"`
Expected: all parser tests pass (defparser produces identical parser)

**Step 4: Run full test suite**

Run: `make test`
Expected: 761 tests, 0 failures, 0 errors

**Step 5: Commit**

```bash
git add src/datatwist/parser.clj
git commit -m "build: migrate parser to defparser macro for AOT grammar compilation"
```

---

### Task 3: Create CLI entry point (datatwist.main)

**Files:**
- Create: `src/datatwist/main.clj`
- Test: `test/datatwist/main_test.clj`

**Step 1: Write the failing test**

Create `test/datatwist/main_test.clj`:

```clojure
(ns datatwist.main-test
  (:require [clojure.test :refer [deftest testing is]]
            [datatwist.main :as main]))

(deftest parse-args-test
  (testing "eval subcommand with -e flag"
    (let [result (main/parse-args ["eval" "-e" "1 + 2"])]
      (is (= :eval (:command result)))
      (is (= "1 + 2" (:expression result)))))

  (testing "run subcommand with file argument"
    (let [result (main/parse-args ["run" "script.dt"])]
      (is (= :run (:command result)))
      (is (= "script.dt" (:file result)))))

  (testing "repl subcommand"
    (let [result (main/parse-args ["repl"])]
      (is (= :repl (:command result)))))

  (testing "fmt subcommand"
    (let [result (main/parse-args ["fmt"])]
      (is (= :fmt (:command result)))))

  (testing "bare invocation defaults to repl"
    (let [result (main/parse-args [])]
      (is (= :repl (:command result)))))

  (testing "--help flag"
    (let [result (main/parse-args ["--help"])]
      (is (= :help (:command result)))))

  (testing "--version flag"
    (let [result (main/parse-args ["--version"])]
      (is (= :version (:command result)))))

  (testing "unknown subcommand returns error"
    (let [result (main/parse-args ["unknown"])]
      (is (= :error (:command result))))))

(deftest eval-expression-test
  (testing "evaluates simple arithmetic"
    (is (= 3 (main/eval-expression "1 + 2"))))

  (testing "evaluates string operations"
    (is (= "HELLO" (main/eval-expression "\"hello\" |> upcase")))))

(deftest run-file-test
  (testing "runs a .dt file and returns result"
    ;; Use an existing example file or create a temp one
    (let [tmp (java.io.File/createTempFile "test" ".dt")]
      (.deleteOnExit tmp)
      (spit tmp "1 + 2")
      (is (= 3 (main/run-file (.getAbsolutePath tmp)))))))
```

**Step 2: Run test to verify it fails**

Run: `clj -M -e "(require 'clojure.test 'datatwist.main-test) (clojure.test/run-tests 'datatwist.main-test)"`
Expected: FAIL — `datatwist.main` namespace not found

**Step 3: Write the implementation**

Create `src/datatwist/main.clj`:

```clojure
(ns datatwist.main
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [datatwist.parser :as parser]
            [datatwist.error-renderer :as renderer])
  (:gen-class))

(def version "0.1.0")

(def global-opts
  [["-h" "--help" "Show help"]
   ["-v" "--version" "Show version"]
   ["-e" "--expression EXPR" "Expression to evaluate"]])

(defn parse-args
  "Parse CLI arguments into a command map.
   Returns {:command :eval/:run/:repl/:fmt/:help/:version/:error ...}"
  [args]
  (let [{:keys [options arguments errors]} (parse-opts args global-opts
                                                       :in-order true)]
    (cond
      errors
      {:command :error :message (str/join "\n" errors)}

      (:help options)
      {:command :help}

      (:version options)
      {:command :version}

      :else
      (let [subcmd (first arguments)
            rest-args (rest arguments)]
        (case subcmd
          "eval" (let [{:keys [options errors]} (parse-opts rest-args global-opts)]
                   (cond
                     errors {:command :error :message (str/join "\n" errors)}
                     (:expression options) {:command :eval :expression (:expression options)}
                     :else {:command :error :message "eval requires -e <expression>"}))
          "run"  (if-let [file (first rest-args)]
                   {:command :run :file file}
                   {:command :error :message "run requires a file argument"})
          "repl" {:command :repl}
          "fmt"  {:command :fmt}
          nil    {:command :repl}
          {:command :error :message (str "Unknown command: " subcmd)})))))

(defn eval-expression
  "Evaluate a DataTwist expression string. Returns the result."
  [expr]
  (parser/eval-dt expr))

(defn run-file
  "Read and evaluate a DataTwist file. Returns the result."
  [path]
  (let [source (slurp path)]
    (parser/eval-dt source)))

(defn- print-result
  "Print a result value to stdout."
  [result]
  (when (some? result)
    (prn result)))

(defn- usage []
  (str "DataTwist " version "\n"
       "\n"
       "Usage: datatwist <command> [options]\n"
       "\n"
       "Commands:\n"
       "  eval -e <expr>   Evaluate an expression\n"
       "  run <file.dt>    Execute a DataTwist file\n"
       "  repl             Start interactive REPL\n"
       "  fmt              Format DataTwist source (not implemented)\n"
       "\n"
       "Options:\n"
       "  -h, --help       Show this help\n"
       "  -v, --version    Show version\n"
       "\n"
       "Examples:\n"
       "  datatwist eval -e '1 + 2'\n"
       "  datatwist run script.dt\n"
       "  datatwist repl\n"))

(defn- repl-loop
  "Run an interactive read-eval-print loop."
  []
  (println (str "DataTwist " version " REPL"))
  (println "Type an expression and press Enter. Ctrl-D to exit.")
  (print "dt> ")
  (flush)
  (loop []
    (when-let [line (read-line)]
      (let [trimmed (str/trim line)]
        (when-not (str/blank? trimmed)
          (try
            (let [result (parser/eval-dt trimmed)]
              (print-result result))
            (catch clojure.lang.ExceptionInfo e
              (let [data (ex-data e)]
                (if (:dt/error data)
                  (binding [*out* *err*]
                    (println (renderer/render-error data)))
                  (binding [*out* *err*]
                    (println (str "Error: " (.getMessage e)))))))
            (catch Exception e
              (binding [*out* *err*]
                (println (str "Error: " (.getMessage e))))))))
      (print "dt> ")
      (flush)
      (recur))))

(defn -main [& args]
  (let [{:keys [command expression file message]} (parse-args args)]
    (case command
      :eval    (try
                 (print-result (eval-expression expression))
                 (System/exit 0)
                 (catch clojure.lang.ExceptionInfo e
                   (let [data (ex-data e)]
                     (if (:dt/error data)
                       (do (binding [*out* *err*]
                             (println (renderer/render-error data)))
                           (System/exit 1))
                       (do (binding [*out* *err*]
                             (println (str "Error: " (.getMessage e))))
                           (System/exit 1)))))
                 (catch Exception e
                   (binding [*out* *err*]
                     (println (str "Error: " (.getMessage e))))
                   (System/exit 1)))

      :run     (try
                 (print-result (run-file file))
                 (System/exit 0)
                 (catch java.io.FileNotFoundException _
                   (binding [*out* *err*]
                     (println (str "Error: file not found: " file)))
                   (System/exit 1))
                 (catch Exception e
                   (binding [*out* *err*]
                     (println (str "Error: " (.getMessage e))))
                   (System/exit 1)))

      :repl    (repl-loop)

      :fmt     (do (binding [*out* *err*]
                     (println "Error: fmt is not implemented yet"))
                   (System/exit 1))

      :help    (do (println (usage))
                   (System/exit 0))

      :version (do (println (str "datatwist " version))
                   (System/exit 0))

      :error   (do (binding [*out* *err*]
                     (println (str "Error: " message))
                     (println "")
                     (println (usage)))
                   (System/exit 3)))))
```

**Step 4: Run tests to verify they pass**

Run: `clj -M -e "(require 'clojure.test 'datatwist.main-test) (clojure.test/run-tests 'datatwist.main-test)"`
Expected: all tests pass

**Step 5: Add main-test to test runner**

Modify `test/datatwist/test_runner.clj` — add `datatwist.main-test` to the require list and `run-tests` call.

**Step 6: Run full test suite**

Run: `make test`
Expected: 761 + new tests pass, 0 failures

**Step 7: Commit**

```bash
git add src/datatwist/main.clj test/datatwist/main_test.clj test/datatwist/test_runner.clj
git commit -m "feat: add CLI entry point with eval/run/repl/fmt subcommands"
```

---

### Task 4: Create build.clj and Makefile targets

**Files:**
- Create: `build.clj`
- Modify: `Makefile`

**Step 1: Create build.clj**

```clojure
(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.datatwist/datatwist)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file (format "target/datatwist-%s-standalone.jar" version))
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
           :main 'datatwist.main})
  (println (str "Built: " uber-file)))
```

**Step 2: Verify uberjar builds**

Run: `clj -T:build uber`
Expected: `Built: target/datatwist-0.1.0-standalone.jar` printed, file exists

**Step 3: Verify uberjar runs**

Run: `java -jar target/datatwist-0.1.0-standalone.jar eval -e '1 + 2'`
Expected: `3`

Run: `java -jar target/datatwist-0.1.0-standalone.jar --version`
Expected: `datatwist 0.1.0`

Run: `java -jar target/datatwist-0.1.0-standalone.jar --help`
Expected: usage text

**Step 4: Update Makefile**

Replace full Makefile content:

```makefile
# DataTwist Makefile

.PHONY: test lint clean demo changelog help uberjar native

# Default target - run all tests
test:
	@echo "=== Running all DataTwist tests ==="
	clj -M -m datatwist.test-runner

# Run language showcase demo
demo:  ## Run language showcase demo
	@clj -M -m datatwist.demo-runner

# Lint code
lint:
	@echo "=== Running linter ==="
	clj-kondo --lint src/

# Build uberjar
uberjar:  ## Build standalone uberjar
	@echo "=== Building uberjar ==="
	clj -T:build uber

# Build native binary (requires GraalVM native-image)
native: uberjar  ## Build native binary via GraalVM
	@echo "=== Building native image ==="
	native-image \
		-jar target/datatwist-0.1.0-standalone.jar \
		--no-fallback \
		--report-unsupported-elements-at-runtime \
		-H:+ReportExceptionStackTraces \
		-o datatwist

# Clean build artifacts
clean:
	@echo "=== Cleaning cache and build artifacts ==="
	rm -rf .cpcache/
	rm -rf .lsp/.cache/
	rm -rf target/

changelog:  ## Generate changelog entry: make changelog TITLE="Feature Name" COMMITS="abc..def"
	@./scripts/changelog-entry.sh "$(TITLE)" $(COMMITS)

# Show help
help:
	@echo "DataTwist Development Commands:"
	@echo ""
	@echo "  make test     - Run all tests"
	@echo "  make lint     - Run linter"
	@echo "  make clean    - Clean cache and build artifacts"
	@echo "  make demo     - Run language showcase demo"
	@echo "  make uberjar  - Build standalone uberjar"
	@echo "  make native   - Build native binary (requires GraalVM)"
	@echo "  make help     - Show this help"
```

**Step 5: Verify Makefile targets**

Run: `make clean && make uberjar`
Expected: uberjar builds successfully

**Step 6: Commit**

```bash
git add build.clj Makefile
git commit -m "build: add build.clj, uberjar and native Makefile targets"
```

---

### Task 5: Add GraalVM native-image configuration

**Files:**
- Create: `resources/META-INF/native-image/datatwist/native-image.properties`
- Create: `resources/META-INF/native-image/datatwist/resource-config.json`

**Step 1: Create native-image.properties**

```properties
Args = --no-fallback \
       --report-unsupported-elements-at-runtime \
       -H:+ReportExceptionStackTraces \
       --features=clj_easy.graal_build_time.InitClojureClasses
```

**Step 2: Create resource-config.json**

This is a fallback in case `defparser` doesn't fully eliminate resource needs:

```json
{
  "resources": {
    "includes": [
      {"pattern": "datatwist\\.grammar"},
      {"pattern": "datatwist\\.grammar$"}
    ]
  }
}
```

**Step 3: Generate reflect-config.json via tracing agent**

This step requires a built uberjar. Run the tracing agent:

```bash
# Build uberjar first
clj -T:build uber

# Run test suite with tracing agent
java -agentlib:native-image-agent=config-output-dir=graal-agent-output \
     -jar target/datatwist-0.1.0-standalone.jar eval -e '"hello" |> upcase'

# Run demo with tracing agent (merge configs)
java -agentlib:native-image-agent=config-merge-dir=graal-agent-output \
     -jar target/datatwist-0.1.0-standalone.jar eval -e 'Math/PI'

# Run more interop cases
java -agentlib:native-image-agent=config-merge-dir=graal-agent-output \
     -jar target/datatwist-0.1.0-standalone.jar eval -e '[1 2 3] |> map [x -> x * 2]'
```

**Step 4: Curate and copy generated reflect-config.json**

```bash
cp graal-agent-output/reflect-config.json resources/META-INF/native-image/datatwist/
# Review and remove test-only entries if needed
rm -rf graal-agent-output
```

**Step 5: Attempt native-image build**

Run: `make native`
Expected: either succeeds or gives specific errors to fix

**Step 6: Fix errors iteratively**

Common fixes:
- Add missing classes to reflect-config.json
- Fix `requiring-resolve` in parser.clj (replace with direct require if needed for native)
- Add serialization config if needed

**Step 7: Verify native binary**

```bash
./datatwist eval -e '1 + 2'        # expect: 3
./datatwist eval -e '"hello" |> upcase'  # expect: "HELLO"
./datatwist --version              # expect: datatwist 0.1.0
./datatwist --help                 # expect: usage text
```

**Step 8: Commit**

```bash
git add resources/META-INF/ build.clj
git commit -m "build: add GraalVM native-image configuration and reflection config"
```

---

### Task 6: Update .gitignore and add to BACKLOG

**Files:**
- Modify: `.gitignore`
- Modify: `BACKLOG.md`

**Step 1: Add build artifacts to .gitignore**

Add these lines:
```
target/
datatwist
graal-agent-output/
```

**Step 2: Update BACKLOG.md**

Mark completed items in the GraalVM section:
- [x] Create CLI entry point (`-main` with `:gen-class`)
- [x] defparser macro for AOT grammar compilation
- [x] `build.clj` with tools.build
- [x] Add `make native` and `make uberjar` build targets
- [x] Add GraalVM native-image build config (reflection config, resource config)

**Step 3: Commit**

```bash
git add .gitignore BACKLOG.md
git commit -m "chore: update gitignore for build artifacts, mark GraalVM tasks done"
```

---

### Task 7: End-to-end verification

**Step 1: Run full test suite**

Run: `make test`
Expected: all tests pass (761+ tests, 0 failures)

**Step 2: Build and test uberjar**

```bash
make clean && make uberjar
java -jar target/datatwist-0.1.0-standalone.jar eval -e '1 + 2'
java -jar target/datatwist-0.1.0-standalone.jar eval -e '[1 2 3] |> map [x -> x * 2]'
java -jar target/datatwist-0.1.0-standalone.jar --help
java -jar target/datatwist-0.1.0-standalone.jar --version
```

**Step 3: Build and test native binary (if GraalVM available)**

```bash
make native
./datatwist eval -e '1 + 2'
./datatwist eval -e '"hello" |> upcase'
./datatwist --version
time ./datatwist eval -e '1'   # verify startup < 50ms
```

**Step 4: Final commit if any fixes needed**
