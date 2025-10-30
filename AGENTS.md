# DataTwist Development Guidelines

## Build/Test Commands

**Run all tests:**
```bash
make test
# or directly:
clj -M -m datatwist.test-runner
```

**Run single test:**
```bash
clj -M -e "(load-file \"test/datatwist/grammar_tests.clj\") (in-ns 'datatwist.grammar-tests) (run-tests 'datatwist.grammar-tests/literals-tests)"
```

**Lint with clj-kondo:**
```bash
make lint
# or directly:
clj-kondo --lint src/ test/ 2>/dev/null || clj-kondo --lint *.clj
```

**Run single test:**
```bash
clj -M -e "(load-file \"test/datatwist/grammar_tests.clj\") (in-ns 'datatwist.grammar-tests) (run-tests 'datatwist.grammar-tests/literals-tests)"
```

**Run single test:**
```bash
clj -M -e "(load-file \"test/datatwist/grammar_tests.clj\") (in-ns 'datatwist.grammar-tests) (run-tests 'datatwist.grammar-tests/literals-tests)"
```

**Lint with clj-kondo:**
```bash
clj-kondo --lint src/ test/ 2>/dev/null || clj-kondo --lint *.clj
```

## Code Style Guidelines

**Imports:** Use `(:require [namespace :as alias])` format, one namespace per line

**Naming:** 
- Functions: kebab-case (`parse-success?`, `get-parse-tree`)
- Variables: kebab-case (`test-cases`, `grammar-text`)
- Constants: SCREAMING_SNAKE_CASE (rare in Clojure)

**Formatting:** 2-space indentation, trailing parentheses on new line for long expressions

**Error Handling:** Use `insta/failure?` for parse failures, wrap in try-catch for external calls

**Testing:** Use `deftest` with descriptive test names, `testing` blocks for context, positive/negative case separation

**Grammar:** Follow Instaparse EBNF syntax, use `< >` to hide intermediate nodes, prefer string literals over regex for fixed tokens

**Comments:** Use `;` for line comments, `#` for grammar notes, `comment` form for development code