# `#p"..."` Pattern Destructuring: Feasibility Report

**Date:** 2026-02-20
**Status:** Pre-implementation planning
**Sources:** `bdd/13-pattern-destructuring.feature`, `test/datatwist/pattern_destructuring_test.clj`,
`docs/pattern-destructuring-research.md`, `PRD.md §6b`, `BACKLOG.md §String Pattern Destructuring`

---

## 1. Feature Scope

### What does `#p"..."` do?

`#p"..."` is a reader macro syntax for _reverse-format string destructuring_: you describe the
shape of a string and capture named fragments from it as an object. It is the complement of string
interpolation (where you fill a template), applied in the read direction.

```
"alice@example.com" | #p"{user}@{domain}" -> {user domain}
; => {user: "alice" domain: "example.com"}
```

Patterns compile to Java named-capture-group regexes at eval time. The result of a successful match
is a DataTwist object (`{:keyword "string"}`). All captured values are strings; numeric conversion
is explicit (`to-int`).

### Distinct capabilities

The feature has six orthogonal capabilities:

1. **Tier 1 — Simple captures** (`{var}`): non-greedy match up to the next literal separator.
2. **Tier 2 — Type-hint shorthand** (`{var:d}`, `{var:w}`, `{var:Nd}`, `{var:N}`): abbreviated constraints for common cases.
3. **Tier 3 — Full constraint mini-language** (`{var: many digit}`, `{var: 'http' maybe 's'}`, `not`, alternation `|`, grouping): for complex structural requirements.
4. **Brace escaping** (`{{` → literal `{`, `}}` → literal `}`).
5. **Integration surface**: guard arms, `is` destructuring, named first-class patterns, stdlib functions `extract` and `match?`.
6. **Compile-time validation**: reject adjacent unconstrained captures, `rest` in non-final position, nested quantifiers.

### Categorization of the 59 stub tests

| Section | Tests | Capability |
|---------|-------|------------|
| 1: Tier 1 — Simple captures | 9 | `{var}`, non-greedy, literal mismatch → nil |
| 2: Tier 2 — Type hints | 7 | `:d`, `:w`, `:N`, `:Nd` shorthand |
| 3: Tier 3 — Full constraint | 11 | `many`, `maybe`, `not`, `rest`, alternation, grouping |
| 4: Brace escaping | 3 | `{{`, `}}` in patterns |
| 5: Guard integration | 5 | Pattern in guard arms, `when` clause, fall-through |
| 6: Named first-class patterns | 3 | `is` binding, reuse across sites, passed as arg |
| 7: Pipeline integration | 6 | `extract`, `match?`, `map`/`filter` combos |
| 8: Compile-time errors | 4 | Adjacent captures, bad `rest` position, nested quantifiers |
| 9: Edge cases / failure modes | 10 | Empty match, full-anchor, non-string input, wildcard `{_}`, `is`-destruct throw |
| 10: Integration smoke test | 1 | Nginx log line (8 captures, escaped quotes) |
| **Total** | **59** | |

---

## 2. Grammar Changes Required

### 2.1 New `Pattern` atom

The `#p"..."` syntax must become a new atom in the Instaparse grammar. It is structurally identical
to the existing `Regex` rule (`#"..."`) but with a different prefix and different evaluator
semantics.

Current `Regex` rule (line 160 of `resources/datatwist.grammar`):
```
Regex = <'#"'> #'(?:[^"\\]|\\.)*' <'"'>
```

New `Pattern` rule to add immediately after:
```
Pattern = <'#p"'> #'(?:[^"\\]|\\.)*' <'"'>
```

The inner regex `(?:[^"\\]|\\.)*` handles standard backslash escapes within the pattern string
(needed for the nginx log scenario where `\"` appears in the pattern). The `{{`/`}}` brace-escape
sequences are handled later by the constraint parser, not by Instaparse.

### 2.2 Insert `Pattern` into `Atom`

Line 102 of the grammar:
```
Atom = Float / Integer / String / Boolean / Nil / Keyword / Regex
     / Object / FnDef / List
     / InstanceMethod / Constructor / QualifiedName
     / Wildcard / Identifier / ParenExpr
```

Becomes:
```
Atom = Float / Integer / String / Boolean / Nil / Keyword / Regex / Pattern
     / Object / FnDef / List
     / InstanceMethod / Constructor / QualifiedName
     / Wildcard / Identifier / ParenExpr
```

### 2.3 Add `Pattern` to `GuardPattern`

Current (line 52):
```
GuardPattern = DestructListPattern / DestructObjPattern / OrExpr
```

Becomes:
```
GuardPattern = DestructListPattern / DestructObjPattern / Pattern / OrExpr
```

This makes `#p"..."` directly recognizable as a structural guard pattern — the evaluator can
distinguish it from a boolean expression without ambiguity.

### 2.4 `DestructPattern` extension for `is` LHS

Current `Binding` rule (line 33):
```
Binding = Identifier _ <KW-IS> _ MultiArityFn
        / Identifier _ <KW-IS> _ TryCatch
        / Identifier _ <KW-IS> _ PipeExpr
        / DestructPattern _ <KW-IS> _ PipeExpr
```

To support `#p"{y:4d}-{m:2d}-{d:2d}" is "2024-03-22"` (Section 9 scenario), add:
```
        / Pattern _ <KW-IS> _ PipeExpr
```

### 2.5 Constraint mini-language

The constraint syntax inside `{var: ...}` is _not_ parsed by Instaparse. It is a separate
hand-written parser that runs at eval time on the raw string content of the `Pattern` node. Its
grammar (from the research doc, Section 1.6):

```
pattern       = segment*
segment       = literal | capture
literal       = (char | '{{' | '}}')+
capture       = '{' name (':' constraint)? '}'
name          = identifier | '_'
constraint    = alt-expr
alt-expr      = seq-expr ('|' seq-expr)*
seq-expr      = quant-expr+
quant-expr    = quantifier? atom-expr
quantifier    = 'many' '!'? | 'some' '!'? | 'maybe' | INT | INT '..' INT
atom-expr     = char-class | string-lit | '(' alt-expr ')' | 'not' atom-expr
char-class    = 'digit' | 'letter' | 'word' | 'space' | 'any' | 'rest'
string-lit    = "'" [^']* "'"
```

**Type-hint shorthand** (Tier 2) maps to full constraints before the constraint parser runs, or is
handled as a parallel parsing branch:

| Shorthand | Expands to |
|-----------|-----------|
| `:d` | `many digit` |
| `:w` | `many word` |
| `:N` (integer N) | `N any` |
| `:Nd` (N followed by `d`) | `N digit` |

---

## 3. Parser Changes

### 3.1 How Instaparse handles the macro

Instaparse will tokenize `#p"..."` as a single `Pattern` AST node:
```clojure
[:Pattern "content of pattern string"]
```

This is identical to how `[:Regex "content"]` works today. The parser produces a string token; the
evaluator interprets it. No changes to `parser.clj` are needed beyond what the grammar generates.

**Potential ambiguity:** The `#p` prefix must be parsed before Instaparse tries the `#"..."` regex
rule. In Instaparse PEG-style ordered alternation, placing `Pattern` before `Regex` in `Atom`
ensures `#p"` is consumed first. If both rules are present, ordering in the `Atom` alternation
handles disambiguation.

### 3.2 AST node shape

```clojure
;; Source: #p"{user}@{domain}"
;; AST:
[:Pattern "{user}@{domain}"]

;; In a guard arm:
[:GuardArm
  [:GuardPattern [:Pattern "{user}@{domain}"]]
  [:OrExpr ...]]   ; result expression

;; In a Binding:
[:Binding
  [:Pattern "{y:4d}-{m:2d}-{d:2d}"]
  [:String "2024-03-22"]]
```

### 3.3 Constraint parser output

The constraint parser (a new `src/datatwist/pattern_compiler.clj` namespace) converts the raw
pattern string to an intermediate representation:

```clojure
;; #p"{user}@{domain}"
[{:type :capture :name "user"  :constraint nil}
 {:type :literal :text "@"}
 {:type :capture :name "domain" :constraint nil}]

;; #p"{y:4d}-{m:2d}-{d:2d}"
[{:type :capture :name "y" :constraint {:quantifier 4 :class :digit}}
 {:type :literal :text "-"}
 {:type :capture :name "m" :constraint {:quantifier 2 :class :digit}}
 {:type :literal :text "-"}
 {:type :capture :name "d" :constraint {:quantifier 2 :class :digit}}]
```

---

## 4. Evaluator Changes

### 4.1 Pattern compilation (new `pattern_compiler.clj`)

A new source file, `src/datatwist/pattern_compiler.clj`, contains:

1. **`parse-pattern-string`**: hand-written recursive descent parser. Consumes the raw pattern string character-by-character, returning a segment vector. Detects `{{`/`}}` brace escapes, `{name}` captures, `{name: constraint}` captures, and literal text.

2. **`compile-constraint`**: converts a parsed constraint AST node to a Java regex fragment string. Uses `Pattern/quote` for literal segments.

3. **`build-regex`**: assembles the full anchored regex string `^(?<name1>...)...(?<nameN>...)$` from the segment vector.

4. **`compile-pattern`**: top-level entry point.
   ```clojure
   (defn compile-pattern [raw-string]
     (let [segments     (parse-pattern-string raw-string)
           _            (validate-segments! segments)  ; compile-time checks
           capture-names (extract-names segments)
           regex-str    (build-regex segments)
           compiled     (re-pattern regex-str)]
       {:dt/type :pattern
        :regex   compiled
        :names   capture-names
        :source  raw-string}))
   ```

5. **`validate-segments!`**: raises compile-time errors for:
   - Adjacent unconstrained captures (no literal separator between them)
   - `rest` in non-final capture position
   - Nested quantifiers (`many (many digit)`)

6. **`apply-pattern`**: applies a compiled pattern to a string, returns a DataTwist object or `nil`.
   ```clojure
   (defn apply-pattern [pattern-val s]
     (when (string? s)
       (let [m (re-matcher (:regex pattern-val) s)]
         (when (.matches m)
           (into {} (for [n (:names pattern-val)]
                      [(keyword n) (.group m n)]))))))
   ```

### 4.2 `eval-node` for `:Pattern`

In `evaluator.clj`, add a dispatch case for `:Pattern` in `eval-node`:

```clojure
:Pattern
(let [raw (first children)]
  (pattern-compiler/compile-pattern raw))
```

This runs at eval time (each time the expression is evaluated). For performance, patterns bound
with `is` are compiled once and the compiled value is stored in the environment.

### 4.3 Guard block integration

In `eval-guard-block` (currently at line 1129 of `evaluator.clj`), add a new match case in the
`cond` dispatch. Currently the dispatch checks node type by tag. The new case:

```clojure
;; String pattern: #p"..." in guard position
(and (vector? guard-inner)
     (= :Pattern (first guard-inner)))
(let [pat-val (eval-node guard-inner env)
     result  (pattern-compiler/apply-pattern pat-val ctx)]
  (if result
    [true (merge env (update-keys result (comp name key)))]
    [false env]))
```

A named pattern bound via `is` (e.g., `email-pat`) evaluates to a `:pattern` map at runtime. In
the guard `cond`'s `:else` branch, after evaluating the guard expression, if the result has
`:dt/type :pattern`, apply it as a pattern match rather than treating it as a boolean:

```clojure
:else
(let [val (eval-node guard-inner env)]
  (if (= :pattern (:dt/type val))
    (let [result (pattern-compiler/apply-pattern val ctx)]
      (if result
        [true (merge env (update-keys result (comp name key)))]
        [false env]))
    ;; existing boolean fallback
    [(not (or (nil? val) (false? val))) env]))
```

### 4.4 `Binding` with `Pattern` on the LHS

In `eval-expr` for `:Binding`, add a case for when the target node is `:Pattern`:

```clojure
:Pattern
(let [val     (eval-node value-expr env)
      pat-val (eval-node target env)
      result  (pattern-compiler/apply-pattern pat-val val)]
  (if result
    [val (merge env (update-keys result (comp name key)))]
    (throw (ex-info "Pattern does not match"
                    {:dt/error true :code "DT-R007"
                     :message (str "Pattern does not match: "
                                   (:source pat-val)
                                   " applied to " (pr-str val))}))))
```

### 4.5 Stdlib additions (`stdlib.clj`)

Two new functions:

**`extract`** — applies a pattern, returns matched object or `nil`:
```clojure
"extract" (fn [s pat]
  (when (and (string? s) (= :pattern (:dt/type pat)))
    (pattern-compiler/apply-pattern pat s)))
```

**`match?`** — boolean test:
```clojure
"match?" (fn [s pat]
  (boolean (and (string? s)
                (= :pattern (:dt/type pat))
                (pattern-compiler/apply-pattern pat s))))
```

### 4.6 Error codes

New error codes needed:

| Code | Category | Trigger |
|------|----------|---------|
| `DT-P010` | `PATTERN ERROR` | Adjacent unconstrained captures (compile-time) |
| `DT-P011` | `PATTERN ERROR` | `rest` in non-final position (compile-time) |
| `DT-P012` | `PATTERN ERROR` | Nested quantifiers (compile-time) |
| `DT-R007` | `PATTERN ERROR` | Pattern-`is` destructuring on no-match (runtime) |

---

## 5. Effort Estimate

### Component breakdown

| Component | Size | Notes |
|-----------|------|-------|
| Grammar changes (4 edits to `datatwist.grammar`) | Small | Mechanical; add `Pattern` rule + 3 references |
| `pattern_compiler.clj` — Tier 1 only | Small | ~80 lines: parse `{var}` + literals, `.*?` compilation, `apply-pattern` |
| `pattern_compiler.clj` — Tier 2 type hints | Small | ~50 lines: shorthand expansion on top of Tier 1 |
| `pattern_compiler.clj` — Tier 3 constraint mini-language | Medium | ~200 lines: full recursive descent parser for constraint grammar |
| `pattern_compiler.clj` — compile-time validation | Small | ~40 lines: three checks on segment vector |
| Evaluator: `eval-node :Pattern` | Small | ~5 lines: delegate to compiler |
| Evaluator: guard block extension | Small | ~15 lines: new `cond` clause + named-pattern detection in `:else` |
| Evaluator: `Binding` with Pattern LHS | Small | ~15 lines: new case in `eval-expr` |
| Stdlib: `extract` + `match?` | Small | ~10 lines |
| Error codes: 4 new entries in `errors.clj` | Small | ~16 lines |
| BDD test implementation (59 stubs) | Medium | All stubs exist; implementation is filling in `eval-dt` calls |

### Complexity classification

| Tier | Complexity | Reason |
|------|-----------|--------|
| Tier 1 only (Sections 1, 4, 5, 6 tests) | **Medium** | Grammar + compiler + evaluator integration, but constraint parser is trivial |
| Add Tier 2 type hints (Section 2 tests) | **Small** | Shorthand expansion, no new parser state |
| Add Tier 3 full constraints (Section 3 tests) | **Medium** | Full recursive descent constraint parser |
| Stdlib + pipeline integration (Section 7) | **Small** | Two functions on top of existing machinery |
| Compile-time errors (Section 8) | **Small** | Validation pass on segment IR |
| Edge cases and `is` destructuring (Sections 9–10) | **Small** | One new `Binding` case + non-string guard fall-through |

**Total estimated complexity: Medium.** The core difficulty is the Tier 3 constraint parser and
the guard block integration. Neither is architecturally novel — both follow established patterns
in the codebase.

### Suggested phases

**Phase 1 — Tier 1 + guard integration (enables 24 of 59 tests)**
- Grammar: add `Pattern` rule to `Atom` and `GuardPattern`
- `pattern_compiler.clj`: parse simple `{var}` and literals; compile to `.*?` with named groups
- `eval-node :Pattern` dispatch
- Guard block: `[:Pattern ...]` case + named-pattern `:else` detection
- Stdlib: `extract` + `match?`
- Covers: all of Sections 1, 4, 5, 6, 7, and most of Section 9

**Phase 2 — Tier 2 type hints (enables 7 more tests)**
- Extend `parse-pattern-string` to parse `:d`, `:w`, `:N`, `:Nd` shorthands
- Map to regex fragments: `\d+?`, `\w+?`, `.{N}`, `\d{N}`

**Phase 3 — Tier 3 full constraints (enables 11 more tests)**
- Implement full constraint recursive descent parser: `many`, `maybe`, `not`, `rest`, alternation,
  grouping, literals, character classes
- Add compile-time validation (adjacent captures, `rest` position, nested quantifiers)
- Covers Sections 3 and 8

**Phase 4 — `is` LHS destructuring + edge cases (enables remaining 17 tests)**
- `Binding` grammar extension: `Pattern _ <KW-IS> _ PipeExpr`
- `eval-expr` `:Pattern` case for Binding
- Error code `DT-R007`
- Wildcard capture `{_}` (no-bind), edge case scenarios

---

## 6. Dependencies

### Hard dependencies (blocking)

None. The feature is self-contained. It does not depend on:
- Lazy evaluation (Feature 8) — patterns work on ordinary strings
- Error reporting (Feature 9) — compile-time errors can use the existing `ex-info` convention
- LSP or nREPL (Features 11–12)

### Soft dependencies (nice-to-have)

- **Error rendering (`error_renderer.clj`)**: the compile-time pattern errors would benefit from
  the Elm-style renderer, but the feature works without it (plain exception messages suffice).
- **`to-int` stdlib function**: used in Tier 2 test scenarios (Section 2, test 5) where the user
  explicitly converts captured digit strings to integers. This function must exist in stdlib before
  that specific test can pass. It likely already exists — confirm with `grep "to-int" src/datatwist/stdlib.clj`.

### Can it be done incrementally?

Yes, and this is the recommended approach. Phase 1 alone delivers the core use case (email, IP,
log parsing in guards and pipelines) and enables 24 of 59 tests. Each subsequent phase adds
capability independently without reworking prior phases. The IR (segment vector + compiled
`:pattern` map) is stable across all phases.

### Interaction with existing destructuring

The existing `DestructPattern` system (`DestructObjPattern` + `DestructListPattern`) in the
evaluator is independent. `#p` destructuring is a third destructuring form at the same level.
There is no need to unify them; they co-exist through the `Binding` dispatch in `eval-expr`. The
guard block already dispatches on node type, so inserting `:Pattern` as a new case does not affect
existing structural destructuring.

---

## Appendix: Key File Locations

| File | Role in this feature |
|------|---------------------|
| `resources/datatwist.grammar` | Add `Pattern` rule; update `Atom`, `GuardPattern`, `Binding` |
| `src/datatwist/pattern_compiler.clj` | New file: parser + compiler + `apply-pattern` |
| `src/datatwist/evaluator.clj` | Add `:Pattern` case in `eval-node`; extend `eval-guard-block`; extend `eval-expr` Binding |
| `src/datatwist/stdlib.clj` | Add `extract` + `match?` |
| `src/datatwist/errors.clj` | Add 4 new error codes (`DT-P010`–`DT-P012`, `DT-R007`) |
| `bdd/13-pattern-destructuring.feature` | BDD spec (587 lines, 59 scenarios) |
| `test/datatwist/pattern_destructuring_test.clj` | 59 stub tests awaiting implementation |
| `docs/pattern-destructuring-research.md` | Prior research: constraint grammar, Regal analysis, integration design |
