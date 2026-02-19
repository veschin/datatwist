# DataTwist IDE Tooling Research

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [nREPL Architecture](#2-nrepl-architecture)
3. [CIDER Integration](#3-cider-integration)
4. [Tree-sitter Grammar](#4-tree-sitter-grammar)
5. [LSP Server](#5-lsp-server)
6. [TextMate Grammar](#6-textmate-grammar)
7. [Plugin Directory Structure](#7-plugin-directory-structure)
8. [Phased Roadmap](#8-phased-roadmap)
9. [Open Questions](#9-open-questions)

---

## 1. Executive Summary

DataTwist's IDE tooling strategy targets a CIDER-like experience in Emacs as the
primary goal, with broad editor support as a secondary objective. The
architecture splits cleanly into two independent stacks:

**Stack A -- Interactive evaluation (Emacs-first):**
nREPL server with DataTwist eval middleware, `datatwist-mode` for Emacs, inline
result overlays, data inspector.

**Stack B -- Static analysis (all editors):**
Tree-sitter grammar for incremental parsing, TypeScript LSP server for
autocomplete/hover/go-to-definition, TextMate grammar as a quick-win fallback.

These stacks are independent and can be developed in parallel. The nREPL stack
runs on the JVM (same process as the evaluator), while the LSP stack runs in
Node.js and never needs to evaluate code. This separation matters because
eval-at-point requires the JVM evaluator, while autocomplete and diagnostics only
need static analysis.

### Recommended Phasing

1. **TextMate grammar** (1-2 days) -- instant syntax highlighting in VS Code,
   Sublime, GitHub, and any TextMate-compatible editor.
2. **nREPL middleware + basic Emacs mode** (1-2 weeks) -- the core REPL
   experience: eval-at-point, result overlays, basic completions.
3. **Tree-sitter grammar** (1-2 weeks) -- incremental parsing for all editors,
   structural editing in Emacs/Neovim.
4. **LSP server** (2-4 weeks) -- diagnostics, completion, signature help, hover,
   go-to-definition.
5. **Full CIDER-like experience** (2-4 weeks) -- inspector, debugger, test
   runner, namespace browser.

---

## 2. nREPL Architecture

### 2.1 How nREPL Middleware Works

nREPL consists of three abstractions: **handlers**, **middleware**, and
**transports**, analogous to Ring's handlers, middleware, and adapters. A handler
is a function that takes a message map and produces responses. Middleware are
higher-order functions that wrap handlers to add functionality.

The middleware stack is ordered via dependency declarations in var metadata. Each
middleware declares `:requires` (which middleware must run above it) and
`:handles` (which ops it implements). nREPL linearizes these into a handler
chain.

The default middleware stack includes:

- `nrepl.middleware.session/session` -- session management
- `nrepl.middleware.interruptible-eval/interruptible-eval` -- code evaluation
- `nrepl.middleware.completion/completion` -- code completion
- `nrepl.middleware.lookup/lookup` -- symbol info lookup

Sources:
- [nREPL Middleware Design](https://nrepl.org/nrepl/design/middleware.html)
- [Building Middleware](https://nrepl.org/nrepl/building_middleware.html)
- [nREPL Supported Ops](https://nrepl.org/nrepl/ops.html)

### 2.2 What Middleware DataTwist Needs

DataTwist needs **one primary middleware** that intercepts `eval` messages and
redirects them through the DataTwist parser/evaluator. This follows the
**Piggieback pattern** -- the same approach used for ClojureScript in
CIDER. Piggieback sits above `interruptible-eval` in the middleware chain and
hijacks eval messages, routing them to the ClojureScript compiler instead of
Clojure's `eval`.

The DataTwist equivalent:

```clojure
;; datatwist.nrepl.middleware/wrap-datatwist-eval

(defn wrap-datatwist-eval
  "Middleware that intercepts eval ops for DataTwist code.
   Detects DataTwist source (via file extension or session flag),
   parses it through the DataTwist parser, and evaluates via the
   tree-walking evaluator."
  [handler]
  (fn [{:keys [op code session] :as msg}]
    (if (and (= op "eval")
             (datatwist-session? session))
      (handle-datatwist-eval msg)
      (handler msg))))
```

**Required ops:**

| Op | Purpose | Implementation |
|---|---|---|
| `eval` | Evaluate DataTwist code | Parse with `datatwist.parser/parse`, evaluate with `datatwist.evaluator/evaluate`, send result back |
| `complete` | Code completion | Return stdlib function names + bound names from current session |
| `info` / `lookup` | Symbol documentation | Return metadata for stdlib functions (from `stdlib-metadata.edn`) |
| `inspect-start` | Begin inspecting a value | Store last eval result, render as inspectable structure |
| `inspect-push` | Drill into nested value | Navigate into sub-value of current inspection |
| `inspect-pop` | Go back up | Return to parent of current inspection |
| `load-file` | Evaluate a .dt file | Read file, parse all expressions, evaluate sequentially |

### 2.3 How to Translate DataTwist Source to Clojure Eval

The existing pipeline in `datatwist/parser.clj` and `datatwist/evaluator.clj`
already handles this:

```
DataTwist source code
    |
    v
datatwist.parser/parse  (Instaparse, returns AST as nested vectors)
    |
    v
datatwist.evaluator/eval-node  (tree-walking evaluator, returns JVM value)
    |
    v
JVM value (Long, String, vector, map, fn, etc.)
```

The evaluator (`evaluator.clj`, ~1500 lines) is a tree-walking interpreter. It
takes an AST node and an environment (a simple string->value map managed by
`env.clj`) and produces a JVM value. The `evaluate` function at line 1503 is the
public entry point:

```clojure
(defn evaluate [input]
  (when-not (comment-or-whitespace-only? input)
    (let [ast (parser/parse input)]
      (when-not (insta/failure? ast)
        (eval-node ast (stdlib/default-env))))))
```

For nREPL, the middleware wraps this: it calls `evaluate`, captures the result,
and sends it back as an nREPL response with the standard `:value`, `:out`,
`:err`, and `:status` keys.

**Important: session environment persistence.** Currently, `evaluate` creates a
fresh `default-env` on every call. For a REPL experience, bindings must persist
across evaluations within a session. This requires:

1. Storing the environment in the nREPL session.
2. Threading the environment through successive evals.
3. Returning the updated environment after each eval (since `is` bindings create
   new env mappings).

The evaluator's `eval-node` for `:Program` already threads an env through its
children via `reduce`. The middleware needs to extract the final env and store it
in the session for the next eval.

### 2.4 Eval-at-Point: Parsing Sub-Expressions from Cursor Position

This is the critical UX challenge for DataTwist. CIDER's `cider-eval-last-sexp`
works because Clojure has unambiguous s-expression boundaries (just count
parentheses). DataTwist uses infix operators, juxtaposition function calls, and
newline-sensitive function calls -- making sub-expression extraction harder.

**Strategy: use Tree-sitter on the Emacs side.**

Emacs 29+ has built-in Tree-sitter support via `treesit.el`. With a Tree-sitter
grammar for DataTwist, the Emacs mode can:

1. Ask Tree-sitter for the syntax node at point.
2. Walk up the tree to find the nearest "evaluable unit" (expression, binding,
   pipeline step).
3. Extract the text of that node.
4. Send it to nREPL for evaluation.

This avoids re-implementing a parser in Elisp. The Tree-sitter parse tree is
always available (it is incrementally updated on every keystroke) and provides
exact node boundaries.

**Evaluable units** (node types that can be independently evaluated):

- `binding` -- evaluate the RHS and show the result
- `pipeline` -- evaluate the whole pipeline
- `call_expression` -- evaluate the function call
- `binary_expression` -- evaluate the expression
- `function_definition` -- return the function (show arity info)
- Any literal -- evaluate to itself
- `program` -- evaluate the entire buffer

**Fallback for pre-Tree-sitter phase:** A simpler heuristic parser in Elisp
that looks for expression boundaries using newlines (DataTwist statements are
newline-separated) and balanced brackets/parens. This is less precise but
functional for the initial nREPL phase.

---

## 3. CIDER Integration

### 3.1 What `datatwist-mode` Needs to Provide

`datatwist-mode` is an Emacs major mode that provides the editing and REPL
experience. It needs the following components:

**Syntax layer (phase 1 -- without Tree-sitter):**
- `datatwist-mode-syntax-table` -- defines character classes (word chars include
  `-`, `?`, `!`)
- `datatwist-font-lock-keywords` -- regex-based highlighting as a fallback
- Comment syntax (`//` as line comment)
- Indentation rules (increase after `|>`, `[`, `{`, `|`; decrease on `]`, `}`)
- Bracket matching and auto-pairing

**Syntax layer (phase 3 -- with Tree-sitter):**
- `datatwist-ts-mode` -- a Tree-sitter-based major mode using `treesit.el`
- Highlighting via Tree-sitter queries (from `highlights.scm`)
- Structural navigation (next/previous expression, up/down in tree)
- `treesit-defun-type-regexp` set to `"binding\\|pipeline\\|function_definition"`

**REPL layer:**
- `datatwist-jack-in` -- start nREPL server and connect
- `datatwist-eval-last-sexp` -- evaluate expression before point
- `datatwist-eval-defun-at-point` -- evaluate top-level form
- `datatwist-eval-buffer` -- evaluate entire buffer
- `datatwist-eval-region` -- evaluate selected region
- `datatwist-switch-to-repl-buffer` -- switch to REPL buffer

**Inspector layer:**
- `datatwist-inspect-last-result` -- inspect the result of the last evaluation
- `datatwist-inspector-mode` -- a special buffer mode for navigating data
  structures

### 3.2 CIDER Middleware vs Custom Middleware

There are two approaches for CIDER integration:

**Option A: Reuse cider-nrepl middleware (recommended initially).**

CIDER's middleware (`cider-nrepl`) provides 25+ middleware functions including
`wrap-complete`, `wrap-info`, `wrap-inspect`, `wrap-stacktrace`,
`wrap-macroexpand`, `wrap-test`, etc. Most of these are Clojure-specific, but
several are useful as-is:

- `wrap-inspect` -- works on any JVM value, not Clojure-specific
- `wrap-stacktrace` -- works for any JVM exception
- `wrap-out` -- captures stdout/stderr (works for any JVM code)

The DataTwist middleware only needs to replace the `eval` op. Other ops can fall
through to cider-nrepl or be handled by custom DataTwist middleware.

**Startup:**
```clojure
;; deps.edn for the nREPL server
{:deps {nrepl/nrepl {:mvn/version "1.3.0"}
        cider/cider-nrepl {:mvn/version "0.50.2"}
        datatwist/datatwist-nrepl {:local/root "../datatwist-nrepl"}}
 :aliases
 {:nrepl {:main-opts ["-m" "nrepl.cmdline"
                      "--middleware"
                      "[cider.nrepl/cider-middleware,datatwist.nrepl/middleware]"]}}}
```

The DataTwist eval middleware must be ordered **above** cider-nrepl's eval
middleware so it intercepts eval messages first.

**Option B: Standalone middleware stack (for lighter-weight use).**

For users who do not want the full CIDER dependency, provide a minimal middleware
stack with just eval, complete, and info ops. This is the better approach for
non-Emacs editors (Calva, Conjure) that use nREPL.

Sources:
- [cider-nrepl GitHub](https://github.com/clojure-emacs/cider-nrepl)
- [cider-nrepl Supported Ops](https://docs.cider.mx/cider-nrepl/nrepl-api/ops.html)
- [nREPL Middleware Setup in CIDER](https://docs.cider.mx/cider/basics/middleware_setup.html)

### 3.3 Inspector Protocol Details

CIDER's inspector uses the `orchard.inspect` library. The protocol is based on
nREPL ops:

| Op | Request | Response |
|---|---|---|
| `inspect-start` | `{:op "inspect-start" :code "expr" :session ...}` | Rendered inspector string |
| `inspect-push` | `{:op "inspect-push" :idx 2 :session ...}` | Rendered inspector string (drilled into index 2) |
| `inspect-pop` | `{:op "inspect-pop" :session ...}` | Rendered inspector string (back to parent) |
| `inspect-refresh` | `{:op "inspect-refresh" :session ...}` | Current inspector state, re-rendered |
| `inspect-set-page-size` | `{:op "inspect-set-page-size" :page-size 50}` | Updated page size |

The inspector renders values as a vector of rendering instructions (strings, with
special markers for clickable elements). The Emacs client interprets these
instructions and renders them in an `*cider-inspect*` buffer.

For DataTwist, the inspector can reuse `orchard.inspect` directly since DataTwist
values are standard JVM values (maps, vectors, longs, strings, functions). The
only customization needed is to render DataTwist's object keys (keywords like
`:name`) as `name:` (postfix colon syntax) rather than `:name` (Clojure syntax)
in the inspector display.

Sources:
- [cider-nrepl inspect.clj](https://github.com/clojure-emacs/cider-nrepl/blob/master/src/cider/nrepl/middleware/inspect.clj)
- [CIDER Inspector Docs](https://docs.cider.mx/cider/debugging/inspector.html)

### 3.4 Overlay / Inline Result Display

CIDER implements inline results via `cider-overlays.el`. The mechanism:

1. After evaluation, CIDER receives the result from nREPL.
2. `cider--make-result-overlay` creates an Emacs overlay at the end of the
   evaluated expression.
3. The overlay uses the `after-string` property to display `=> result` after the
   expression text.
4. The overlay is removed after the next command (configurable via
   `cider-eval-result-duration`).

Configuration points:
- `cider-use-overlays` -- `t` (both echo area and overlay), `nil` (echo area
  only), `'errors-only`, `'both`
- `cider-result-overlay-position` -- `'at-eol` (end of line) or `'at-point`
  (after the form)
- `cider-overlays-use-font-lock` -- whether to syntax-highlight the result

DataTwist's `datatwist-mode` can implement the same overlay mechanism. The key
function is:

```elisp
(defun datatwist--display-result-overlay (result pos)
  "Display RESULT as an inline overlay at POS."
  (let ((ov (make-overlay pos pos nil t t)))
    (overlay-put ov 'after-string
                 (propertize (format " => %s" result)
                             'face 'datatwist-result-overlay-face))
    (overlay-put ov 'category 'datatwist-result)
    (run-at-time cider-eval-result-duration nil
                 (lambda () (delete-overlay ov)))))
```

This is straightforward to implement and does not depend on CIDER.

Sources:
- [cider-overlays.el source](https://github.com/clojure-emacs/cider/blob/master/cider-overlays.el)
- [CIDER Code Evaluation docs](https://docs.cider.mx/cider/usage/code_evaluation.html)
- [Eval-result overlays in Emacs-lisp (Endless Parentheses)](https://endlessparentheses.com/eval-result-overlays-in-emacs-lisp.html)

---

## 4. Tree-sitter Grammar

### 4.1 Strategy for Converting Instaparse EBNF to Tree-sitter grammar.js

The existing design document (`docs/lsp-tree-sitter-design.md`) already contains
a comprehensive Tree-sitter grammar skeleton (sections 1.1-1.9, approximately
760 lines of detailed design). This section summarizes the conversion strategy
and adds research findings.

**Key differences between Instaparse PEG and Tree-sitter LR(1)/GLR:**

| Aspect | Instaparse (PEG) | Tree-sitter (LR/GLR) |
|---|---|---|
| Choice semantics | Ordered (`/`) -- first match wins | Unordered (`choice()`) -- longest match wins |
| Lookahead | Unlimited (regex lookahead) | LR(1) = 1 token lookahead |
| Precedence | Implicit in rule ordering | Explicit via `prec()`, `prec.left()`, `prec.right()` |
| Whitespace | Manual (`_`, `__`, `__I` rules) | Automatic via `extras` (with exceptions) |
| Error recovery | None | Built-in `ERROR` nodes |
| Hidden rules | `<angle brackets>` | `_` prefix on rule names |

**Conversion steps:**

1. **Flatten precedence chain.** Instaparse's chain (`OrExpr` -> `AndExpr` ->
   `NilCoalesce` -> ... -> `MulExpr` -> `UnaryExpr`) becomes a single
   `binary_expression` rule with `prec.left()` levels. The existing design
   document maps these to levels 1-13.

2. **Replace manual whitespace with `extras`.** Remove `_`, `__` from between
   tokens. Add `/\s/` and `$.comment` to Tree-sitter's `extras` array. The
   exception is `__I` (inline whitespace) -- handled by the external scanner.

3. **External scanner for `__I`.** DataTwist's `__I` rule (inline whitespace,
   no newlines) prevents function call arguments from spanning lines. Tree-sitter
   needs an external scanner in C that emits `NEWLINE` tokens at line boundaries
   unless the next line starts with `|>`. The design document contains a complete
   C implementation (section 1.7).

4. **GLR conflicts for `[` ambiguity.** Both `List` (`[1 2 3]`) and `FnDef`
   (`[x -> x + 1]`) start with `[`. Tree-sitter's GLR mode forks the parse and
   commits when `->` is (or is not) found. Requires a `conflicts` declaration.

5. **Keyword word boundaries.** Instaparse uses regex negative lookahead
   (`is(?![a-zA-Z0-9_?!\-])`). Tree-sitter uses the `word` property on
   `$.identifier` to handle keyword disambiguation via longest-match. Identifiers
   like `is-valid` naturally win over the keyword `is` because they are longer
   tokens.

**Existing tool: `tree-sitter-ebnf-generator`.** The
[eatkins/tree-sitter-ebnf-generator](https://github.com/eatkins/tree-sitter-ebnf-generator)
can convert EBNF to Tree-sitter grammar.js format. However, DataTwist's grammar
uses Instaparse-specific features (hidden rules, regex literals, manual
whitespace) that the tool does not understand. Manual conversion is necessary,
but the design document already provides the complete grammar skeleton.

Sources:
- [Tree-sitter Grammar DSL](https://tree-sitter.github.io/tree-sitter/creating-parsers/2-the-grammar-dsl.html)
- [tree-sitter-ebnf-generator](https://github.com/eatkins/tree-sitter-ebnf-generator)
- [External Scanners](https://tree-sitter.github.io/tree-sitter/creating-parsers/4-external-scanners.html)
- [Writing a Tree-sitter grammar (TLA+ case study)](https://ahelwer.ca/post/2023-01-11-tree-sitter-tlaplus/)

### 4.2 Key Differences: EBNF vs PEG-like vs Tree-sitter

The DataTwist grammar is specifically **PEG** (Parsing Expression Grammar),
not pure EBNF. Instaparse supports both but DataTwist uses PEG features:
ordered choice, regex literals, and hidden rules.

Tree-sitter is neither PEG nor EBNF -- it is **LR(1) with GLR fallback**.
This means:

- **No ordered choice.** PEG's `/` is priority-based (first alternative wins);
  Tree-sitter's `choice()` is ambiguity-based (GLR explores all alternatives
  and picks the one that produces a valid parse). This only matters when two
  alternatives overlap -- for DataTwist, the main overlap is `List` vs `FnDef`.

- **No inline regex lookahead.** PEG allows `!regex` (negative lookahead) and
  `&regex` (positive lookahead). Tree-sitter's regex engine in tokens does not
  support lookahead. Keyword boundaries need the `word` property or `conflicts`.

- **Precedence is explicit.** PEG derives precedence from rule nesting depth
  (`MulExpr` binds tighter than `AddExpr` because it is deeper in the call
  chain). Tree-sitter uses numeric `prec()` levels.

### 4.3 Testing Approach

Tree-sitter provides a built-in test framework:

```
tree-sitter-datatwist/
  test/
    corpus/
      literals.txt
      bindings.txt
      pipelines.txt
      functions.txt
      guards.txt
      ...
```

Each test file uses the format:

```
==================
Integer literal
==================

42

---

(program
  (integer))

==================
Pipeline with filter
==================

users |> filter _.active

---

(program
  (pipeline
    source: (identifier)
    step: (call_expression
      function: (identifier)
      argument: (field_access
        object: (wildcard)
        field: (field_name)))))
```

Test commands:
- `tree-sitter test` -- runs all corpus tests
- `tree-sitter parse file.dt` -- parses a single file and prints the CST
- `tree-sitter highlight file.dt` -- shows highlighted output

The BDD feature files in `bdd/` provide an excellent source of test cases.
Each `Given` clause contains DataTwist source code that should be added to the
Tree-sitter test corpus.

---

## 5. LSP Server

### 5.1 Implementation Language Recommendation

The existing design document (`docs/lsp-tree-sitter-design.md`, section 2.1)
recommends **TypeScript** for the LSP server. After additional research, this
recommendation stands, but with a second viable option:

**Option A: TypeScript (recommended for pure LSP)**

Pros:
- Mature LSP library (`@vscode/languageserver-node`)
- First-class Tree-sitter WASM bindings (`web-tree-sitter`)
- Instant startup (~100ms vs ~3s for JVM)
- Easy VS Code extension packaging
- LSP server does not need to evaluate code

Cons:
- Separate codebase from the Clojure evaluator
- Cannot share stdlib metadata without a build step (EDN -> JSON)

**Option B: Clojure via lsp4clj (viable for unified stack)**

[lsp4clj](https://github.com/clojure-lsp/lsp4clj) is a library for building
LSP servers in Clojure. It handles JSON-RPC over stdio and provides
multimethods for dispatching on LSP methods. clojure-lsp itself is built on
lsp4clj, proving the library works at scale (30,000+ lines of code).

Pros:
- Same language as the evaluator, can share code directly
- Access to the Instaparse parser for accurate diagnostics
- lsp4clj handles all protocol details
- Can use `CompletableFuture` for parallel request processing
- GraalVM native-image could solve startup time (if the GraalVM work from the
  backlog is done)

Cons:
- JVM startup time (2-5 seconds) unless using GraalVM native binary
- No Tree-sitter bindings for JVM (would need to use Instaparse for parsing)
- Smaller ecosystem for LSP tooling in Clojure

**Recommendation:** Start with TypeScript for the LSP server. If the GraalVM
native binary work (BACKLOG P0) succeeds and startup time drops below 200ms,
reconsider Clojure via lsp4clj -- having everything in one language is a
significant maintenance advantage.

Sources:
- [lsp4clj GitHub](https://github.com/clojure-lsp/lsp4clj)
- [lsp4clj API docs](https://cljdoc.org/d/com.github.clojure-lsp/lsp4clj/1.7.1/doc/readme)
- [clojure-lsp Architecture](https://clojure-lsp.io/development/)
- [clojure-lsp GitHub](https://github.com/clojure-lsp/clojure-lsp)

### 5.2 Required Capabilities for MVP

The existing design document (section 2.4) defines three phases. For an MVP:

| Capability | LSP Method | Priority |
|---|---|---|
| Diagnostics | `textDocument/publishDiagnostics` | Must have |
| Completion | `textDocument/completion` | Must have |
| Signature Help | `textDocument/signatureHelp` | Must have |
| Hover | `textDocument/hover` | Should have |
| Go-to-Definition | `textDocument/definition` | Should have |
| Document Symbols | `textDocument/documentSymbol` | Nice to have |

### 5.3 How to Leverage Tree-sitter for Parsing

The LSP server uses Tree-sitter (via WASM) for incremental parsing. On every
document change, Tree-sitter re-parses only the changed region. The resulting
CST is used for:

- **Diagnostics:** Walk the CST for `ERROR` nodes (parse errors). Also detect
  common mistakes (e.g., `x = 42` when `x is 42` was intended).
- **Completion:** Determine the cursor context (after `|>`, after `.`, general
  identifier position) by examining the CST node at the cursor.
- **Scope analysis:** Walk the CST to build a binding table (which names are
  in scope at the cursor position). Used for go-to-definition and references.
- **Signature help:** Find the enclosing `call_expression` node and count
  arguments to determine which parameter the cursor is on.

The LSP server does NOT use the Instaparse parser or the Clojure evaluator.
All analysis is purely structural, based on the Tree-sitter CST.

---

## 6. TextMate Grammar

### 6.1 Quick-Win Approach for Basic Syntax Highlighting

A TextMate grammar (`.tmLanguage.json`) provides instant syntax highlighting in
VS Code, Sublime Text, GitHub (for `.dt` files), and any TextMate-compatible
editor. It is regex-based and cannot handle all of DataTwist's syntax (e.g.,
context-dependent `_` highlighting), but covers 90% of the language.

The grammar is a JSON file with `patterns` (regex rules) and `repository`
(reusable pattern groups). Each pattern assigns a **scope name** to matched
text, which themes use for coloring.

### 6.2 Scope Naming Conventions

TextMate scopes follow a hierarchical naming convention:

| DataTwist element | TextMate scope |
|---|---|
| `//` comments | `comment.line.double-slash.datatwist` |
| `"strings"` | `string.quoted.double.datatwist` |
| `42`, `3.14` | `constant.numeric.datatwist` |
| `true`, `false` | `constant.language.boolean.datatwist` |
| `nil` | `constant.language.nil.datatwist` |
| `is`, `and`, `or`, `not`, `in` | `keyword.operator.datatwist` |
| `try`, `catch`, `finally` | `keyword.control.datatwist` |
| `require`, `as` | `keyword.control.import.datatwist` |
| `when` | `keyword.control.conditional.datatwist` |
| `recur` | `keyword.control.flow.datatwist` |
| `\|>` | `keyword.operator.pipe.datatwist` |
| `>>`, `<<` | `keyword.operator.compose.datatwist` |
| `??` | `keyword.operator.nil-coalesce.datatwist` |
| `+`, `-`, `*`, `/`, `%` | `keyword.operator.arithmetic.datatwist` |
| `=`, `!=`, `>`, `<`, `>=`, `<=` | `keyword.operator.comparison.datatwist` |
| `->` | `keyword.operator.arrow.datatwist` |
| `_` | `variable.language.wildcard.datatwist` |
| Function names in calls | `entity.name.function.datatwist` |
| Identifiers after `is` | `variable.other.binding.datatwist` |
| `name:` in objects | `entity.name.tag.datatwist` |
| `:keyword` | `constant.other.keyword.datatwist` |
| `log!`, `tap!` | `entity.name.function.side-effect.datatwist` |
| `.method` | `entity.name.function.method.datatwist` |
| `ClassName.` | `entity.name.type.constructor.datatwist` |

### 6.3 TextMate Grammar Skeleton

```json
{
  "$schema": "https://raw.githubusercontent.com/martinring/tmlanguage/master/tmlanguage.json",
  "name": "DataTwist",
  "scopeName": "source.datatwist",
  "patterns": [
    { "include": "#comments" },
    { "include": "#strings" },
    { "include": "#numbers" },
    { "include": "#constants" },
    { "include": "#keywords" },
    { "include": "#operators" },
    { "include": "#function-calls" },
    { "include": "#bindings" },
    { "include": "#object-keys" },
    { "include": "#identifiers" }
  ],
  "repository": {
    "comments": {
      "patterns": [{
        "name": "comment.line.double-slash.datatwist",
        "match": "//.*$"
      }]
    },
    "strings": {
      "patterns": [
        {
          "name": "string.quoted.double.datatwist",
          "begin": "\"",
          "end": "\"",
          "patterns": [{
            "name": "constant.character.escape.datatwist",
            "match": "\\\\."
          }]
        },
        {
          "name": "string.quoted.single.datatwist",
          "begin": "'",
          "end": "'",
          "patterns": [{
            "name": "constant.character.escape.datatwist",
            "match": "\\\\."
          }]
        }
      ]
    },
    "numbers": {
      "patterns": [
        {
          "name": "constant.numeric.float.datatwist",
          "match": "\\b[0-9]+\\.[0-9]+\\b"
        },
        {
          "name": "constant.numeric.integer.datatwist",
          "match": "\\b[0-9]+\\b"
        }
      ]
    },
    "constants": {
      "patterns": [
        {
          "name": "constant.language.boolean.datatwist",
          "match": "\\b(true|false)\\b"
        },
        {
          "name": "constant.language.nil.datatwist",
          "match": "\\bnil\\b"
        },
        {
          "name": "constant.other.keyword.datatwist",
          "match": ":[a-zA-Z][a-zA-Z0-9_\\-]*"
        }
      ]
    },
    "keywords": {
      "patterns": [
        {
          "name": "keyword.control.datatwist",
          "match": "\\b(try|catch|finally|recur|when)\\b"
        },
        {
          "name": "keyword.control.import.datatwist",
          "match": "\\b(require|as)\\b"
        },
        {
          "name": "keyword.operator.binding.datatwist",
          "match": "\\bis\\b"
        },
        {
          "name": "keyword.operator.logical.datatwist",
          "match": "\\b(and|or|not|in)\\b"
        }
      ]
    },
    "operators": {
      "patterns": [
        {
          "name": "keyword.operator.pipe.datatwist",
          "match": "\\|>"
        },
        {
          "name": "keyword.operator.compose.datatwist",
          "match": ">>|<<"
        },
        {
          "name": "keyword.operator.nil-coalesce.datatwist",
          "match": "\\?\\?"
        },
        {
          "name": "keyword.operator.arrow.datatwist",
          "match": "->"
        },
        {
          "name": "keyword.operator.comparison.datatwist",
          "match": ">=|<=|!=|>|<|="
        },
        {
          "name": "keyword.operator.arithmetic.datatwist",
          "match": "[+\\-*/%]"
        },
        {
          "name": "keyword.operator.guard.datatwist",
          "match": "\\|(?!>)"
        }
      ]
    },
    "function-calls": {
      "patterns": [{
        "name": "entity.name.function.side-effect.datatwist",
        "match": "\\b[a-zA-Z][a-zA-Z0-9_\\-]*!\\b"
      }]
    },
    "bindings": {
      "patterns": [{
        "match": "\\b([a-zA-Z][a-zA-Z0-9_\\-]*[?!]?)\\s+(is)\\b",
        "captures": {
          "1": { "name": "variable.other.binding.datatwist" },
          "2": { "name": "keyword.operator.binding.datatwist" }
        }
      }]
    },
    "object-keys": {
      "patterns": [{
        "match": "([a-zA-Z][a-zA-Z0-9_\\-]*):",
        "captures": {
          "1": { "name": "entity.name.tag.datatwist" }
        }
      }]
    },
    "identifiers": {
      "patterns": [
        {
          "name": "variable.language.wildcard.datatwist",
          "match": "\\b_\\b"
        },
        {
          "name": "variable.other.datatwist",
          "match": "\\b[a-zA-Z][a-zA-Z0-9_\\-]*[?!]?\\b"
        }
      ]
    }
  }
}
```

This grammar is approximate -- TextMate regexes cannot perfectly distinguish
function calls from identifiers in all contexts. But it provides good-enough
highlighting for 90% of cases. The Tree-sitter grammar (and semantic tokens from
the LSP) handle the remaining 10%.

Sources:
- [VS Code Syntax Highlight Guide](https://code.visualstudio.com/api/language-extensions/syntax-highlight-guide)
- [TextMate Language Grammars Manual](https://macromates.com/manual/en/language_grammars)
- [Scope Naming Conventions (Sublime Text docs)](https://www.sublimetext.com/docs/scope_naming.html)

---

## 7. Plugin Directory Structure

### 7.1 Recommended Layout for `plugins/`

Each plugin is an independent project with its own build system. They reference
the core DataTwist project as a dependency, not as a source directory.

```
plugins/
  datatwist-nrepl/              # nREPL middleware (Clojure)
    deps.edn                    # depends on datatwist core + nrepl
    src/
      datatwist/nrepl/
        middleware.clj           # wrap-datatwist-eval
        session.clj              # session env persistence
        completion.clj           # DataTwist-aware completion
        inspector.clj            # custom inspector rendering
    test/
      datatwist/nrepl/
        middleware_test.clj

  datatwist-emacs/              # Emacs major mode + REPL client
    datatwist-mode.el           # major mode, font-lock, indentation
    datatwist-repl.el           # nREPL client, eval commands
    datatwist-overlays.el       # inline result overlays
    datatwist-inspector.el      # data inspector buffer
    datatwist-ts-mode.el        # tree-sitter major mode variant

  tree-sitter-datatwist/        # Tree-sitter grammar
    grammar.js                  # grammar definition
    src/
      scanner.c                 # external scanner for newline handling
    queries/
      highlights.scm            # syntax highlighting queries
      locals.scm                # scope/local variable queries
      tags.scm                  # symbol extraction queries
    test/
      corpus/
        literals.txt
        bindings.txt
        pipelines.txt
        functions.txt
        guards.txt
        objects.txt
        destructuring.txt
        try-catch.txt
    package.json                # npm package for tree-sitter

  datatwist-lsp/                # LSP server (TypeScript)
    package.json
    tsconfig.json
    src/
      server.ts
      capabilities.ts
      analyzer/
        scope.ts
        pipeline.ts
      providers/
        diagnostics.ts
        completion.ts
        signature-help.ts
        hover.ts
        definition.ts
      metadata/
        stdlib.json
        loader.ts

  datatwist-vscode/             # VS Code extension
    package.json
    src/
      extension.ts
    syntaxes/
      datatwist.tmLanguage.json
    language-configuration.json
```

### 7.2 How Plugins Reference Core DataTwist

**Clojure plugins** (`datatwist-nrepl`):
```clojure
;; deps.edn
{:deps {datatwist/datatwist {:local/root "../../"}  ; during development
        ;; Later: {:mvn/version "0.1.0"} or {:git/url ...}
        nrepl/nrepl {:mvn/version "1.3.0"}}}
```

**TypeScript plugins** (`datatwist-lsp`):
The LSP server does not depend on the Clojure codebase at runtime. It depends on:
- `tree-sitter-datatwist` (WASM binary, built from the Tree-sitter grammar)
- `stdlib.json` (generated from `resources/stdlib-metadata.edn` at build time)

**Emacs plugins** (`datatwist-emacs`):
No compile-time dependency on the Clojure codebase. The Emacs mode connects to
the nREPL server at runtime. The Tree-sitter grammar is loaded from a compiled
`.so`/`.dylib` (Emacs 29+ can download and compile it automatically).

### 7.3 Future: Separate Repositories

Initially, all plugins live under `plugins/` in the monorepo for easy
development. When they stabilize, each becomes its own repository:

- `github.com/datatwist/tree-sitter-datatwist` (required for nvim-treesitter
  registry, tree-sitter.github.io parser list)
- `github.com/datatwist/datatwist-lsp` (npm package for the LSP binary)
- `github.com/datatwist/datatwist-vscode` (VS Code marketplace)
- `github.com/datatwist/datatwist-emacs` (MELPA/ELPA package)
- `github.com/datatwist/datatwist-nrepl` (Clojars artifact)

---

## 8. Phased Roadmap

### Phase 1: TextMate Grammar (1-2 days)

**Goal:** Instant syntax highlighting in VS Code, Sublime, GitHub.

**Deliverables:**
- `plugins/datatwist-vscode/syntaxes/datatwist.tmLanguage.json`
- `plugins/datatwist-vscode/language-configuration.json`
- `plugins/datatwist-vscode/package.json`
- File type registration for `.dt` and `.datatwist`
- Bracket matching, comment toggling, auto-close pairs

**What it enables:**
- VS Code: syntax highlighting, bracket matching, comment toggle
- GitHub: syntax highlighting on `.dt` files (via `linguist` override)
- Sublime Text: syntax highlighting (via `.tmLanguage.json` installation)

**No dependencies on other phases.**

### Phase 2: nREPL Middleware + Basic Emacs Mode (1-2 weeks)

**Goal:** Evaluate DataTwist code interactively from Emacs.

**Deliverables:**
- `plugins/datatwist-nrepl/` -- eval middleware with session env persistence
- `plugins/datatwist-emacs/datatwist-mode.el` -- basic major mode (regex
  font-lock, indentation, comment syntax)
- `plugins/datatwist-emacs/datatwist-repl.el` -- `jack-in`, eval-at-point,
  eval-buffer, result overlays
- `datatwist-mode` registered on MELPA (or installable via `use-package`)

**Key technical challenges:**
- Session environment persistence across evals
- Expression boundary detection for eval-at-point (heuristic parser in Elisp
  until Tree-sitter is available)
- Proper error display (parse errors from Instaparse, runtime errors from the
  evaluator)

**Dependencies:** None (does not require Tree-sitter or LSP).

### Phase 3: Tree-sitter Grammar (1-2 weeks)

**Goal:** Incremental parsing for all editors, structural editing.

**Deliverables:**
- `plugins/tree-sitter-datatwist/grammar.js` -- complete grammar
- `plugins/tree-sitter-datatwist/src/scanner.c` -- external scanner
- `plugins/tree-sitter-datatwist/queries/highlights.scm`
- `plugins/tree-sitter-datatwist/queries/locals.scm`
- Test corpus covering all BDD scenarios
- npm package published

**What it enables:**
- Neovim: native Tree-sitter highlighting via `nvim-treesitter`
- Emacs 29+: `datatwist-ts-mode` using `treesit.el`
- Better eval-at-point in Emacs (use Tree-sitter nodes instead of heuristics)
- Foundation for the LSP server

**Dependencies:** None (independent of nREPL work).

### Phase 4: LSP Server (2-4 weeks)

**Goal:** Diagnostics, completion, signature help, hover across all editors.

**Deliverables:**
- `plugins/datatwist-lsp/` -- TypeScript LSP server
- MVP capabilities: diagnostics, completion, signature help
- `resources/stdlib-metadata.edn` -- function metadata
- Build step: EDN -> JSON metadata generation
- Integration with VS Code extension, Neovim lspconfig, Emacs lsp-mode/eglot

**What it enables:**
- Real-time parse error diagnostics (from Tree-sitter ERROR nodes)
- Context-aware completion (stdlib functions, bound names, keywords)
- Function signature help with pipe-awareness
- Hover documentation for stdlib functions

**Dependencies:** Phase 3 (Tree-sitter grammar must exist for the LSP to parse).

### Phase 5: Full CIDER-like Experience (2-4 weeks)

**Goal:** Inspector, integrated test runner, debugger, namespace browser.

**Deliverables:**
- `plugins/datatwist-nrepl/` enhanced with: `inspect-*` ops, test runner ops,
  stacktrace middleware
- `plugins/datatwist-emacs/datatwist-inspector.el` -- interactive data inspector
- `plugins/datatwist-emacs/datatwist-test.el` -- run DataTwist tests from Emacs
- Semantic tokens from LSP (distinguish stdlib vs user functions)
- Inlay hints (parameter names in function calls)
- LSP Phase 2+3 capabilities: go-to-definition, find references, rename

**What it enables:**
- Drill into any evaluated data structure interactively
- Run tests from the editor, jump to failures
- Semantic highlighting (stdlib functions vs user-defined)
- Parameter name hints inline

**Dependencies:** Phases 2, 3, 4.

---

## 9. Open Questions

These are design decisions that need user input before implementation begins.

### 9.1 File Extension

The `.dt` extension is concise but may conflict with other tools. `.datatwist`
is unambiguous but verbose.

- **Question:** Is `.dt` the canonical extension? Should both be supported?
- **Impact:** TextMate grammar, file type detection, all editor configs.

### 9.2 nREPL Session Model

When a user evaluates `x is 42` followed by `x + 1`, should `x` persist?

- **Option A:** Persistent session (CIDER model) -- bindings persist until
  session is reset or REPL is restarted.
- **Option B:** File-scoped evaluation -- eval-buffer re-evaluates everything;
  eval-at-point evaluates in the context of all bindings above the cursor in
  the file.
- **Question:** Which model? Or both (file-scoped by default, persistent REPL
  as an option)?

### 9.3 REPL Display of DataTwist Values

Should the REPL display values in DataTwist syntax or Clojure syntax?

- `{name: "Alice" age: 25}` (DataTwist) vs `{:name "Alice", :age 25}` (Clojure)
- `[1 2 3]` (same in both)
- `[x -> x + 1]` (DataTwist) vs `#function[...]` (Clojure)

**Question:** Implement a DataTwist pretty-printer for the nREPL middleware?
This is important for the REPL UX but non-trivial (needs to handle all JVM
types, circular references, etc.).

### 9.4 Error Message Format

DataTwist aims for Elm-style error messages (PRD). Should nREPL errors:

- Return raw Instaparse failure data (and let the client format it)?
- Return pre-formatted error messages (from the server)?
- Return structured error data (column, line, expected tokens, suggestions)?

**Question:** Which approach? Structured is most flexible but requires more
middleware work.

### 9.5 Module System Impact on LSP

The module system (BACKLOG P1) is not yet designed. When it arrives, the LSP
will need:

- Multi-file analysis (resolve `require` statements across files)
- Project-level scope (not just single-file scope)
- Module index / project database

**Question:** Should the LSP be designed with multi-file support from the
start, or single-file-only for MVP?

### 9.6 Eval Middleware: Piggieback Model vs Dedicated Server

- **Piggieback model:** DataTwist eval middleware runs inside a standard Clojure
  nREPL server. Users can switch between Clojure and DataTwist evaluation in
  the same session.
- **Dedicated server:** DataTwist ships its own nREPL server binary that only
  evaluates DataTwist code.

**Question:** Which model? Piggieback is more flexible (useful for Clojure
interop debugging) but more complex.

### 9.7 Tree-sitter Grammar Repository

Tree-sitter grammars must be in their own repository to work with
`nvim-treesitter` and the Tree-sitter parser registry. Options:

- Start in `plugins/tree-sitter-datatwist/` and split later.
- Create a separate repo from the start.

**Question:** Start in monorepo or separate repo? Monorepo is easier for
development; separate repo is required for Neovim integration.

### 9.8 Emacs Package: MELPA vs Manual Install

- MELPA provides easy `package-install` but requires a separate repo and
  review process.
- Manual install (use-package with `:vc` or straight.el) works from day one.

**Question:** Target MELPA from the start, or manual install only for early
adopters?

### 9.9 GraalVM Dependency

The nREPL middleware requires the JVM. If DataTwist ships as a GraalVM native
binary (BACKLOG P0), should the nREPL server also be native? GraalVM native
binaries support nREPL (clojure-lsp does this), but it requires careful
handling of reflection and dynamic class loading.

**Question:** Is GraalVM native binary for the nREPL server in scope, or is
JVM-only acceptable?
