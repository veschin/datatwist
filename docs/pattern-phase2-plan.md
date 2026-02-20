# Pattern Destructuring Phase 2: Type Hint Shorthand

**Date:** 2026-02-20
**Status:** Pre-implementation planning
**Depends on:** Phase 1 complete (9 Section 1 tests passing, guard integration, extract/match?, brace escaping)
**Sources:** `bdd/13-pattern-destructuring.feature` Sections 2–10, `test/datatwist/pattern_destructuring_test.clj`,
`src/datatwist/pattern_compiler.clj`, `docs/pattern-destructuring-plan.md`

---

## 1. Phase 2 Scope

Phase 2 adds **Tier 2 type hint shorthand** to pattern captures. A type hint is a suffix after `:` inside
a `{name:hint}` capture that expands to a simple, fixed regex fragment. The constraint parser from
Phase 1 already splits the colon and stores the raw constraint string in `:constraint`; Phase 2
gives that string meaning for the four shorthand forms.

Phase 3 (separate plan) covers the full constraint mini-language (`many`, `maybe`, `not`, `rest`,
alternation, grouping, `N digit` exact-count forms written as words).

### Test stubs unlocked by Phase 2

The following stubs move from `(testing "stub...")` to real assertions:

| Test name | Section | Shorthand used | BDD scenario |
|-----------|---------|----------------|--------------|
| `iso-date-with-4d-2d-2d-type-hints` | 2 | `:4d` `:2d` | ISO date `{y:4d}-{m:2d}-{d:2d}` |
| `type-hint-d-enforces-digits-only-octets` | 2 | `:d` | Digits-only octets, IP address |
| `type-hint-d-rejects-non-digit-content` | 2 | `:d` | "abc.def.ghi.jkl" → "no match" |
| `exact-length-type-hint-n-captures-fixed-char-count` | 2 | `:3` | `{code:3}` fixed width, any char |
| `exact-length-type-hint-nd-captures-fixed-digit-count` | 2 | `:4d` `:2d` | ISO date captures as int |
| `type-hint-w-captures-word-characters-only` | 2 | `:w` | `{word:w}` word chars only |
| `type-hint-d-does-not-match-letters` | 2 | `:2d` | "ab-12" → "no match" |
| `pattern-bound-with-is-is-a-first-class-value` | 6 | `:4d` `:2d` | Named pattern with type hints |
| `nginx-style-log-pattern-extracts-all-fields` | 10 | none (Tier 1) | Smoke test; works with Phase 1 but blocked by test stub note |
| `unsatisfiable-constraint-too-few-chars-returns-no-match` | 9 | `:5 digit` (Phase 3) | Stub; stays Phase 3 |

**Phase 2 directly unlocks 7 Section 2 tests.** Section 6 test `pattern-bound-with-is-is-a-first-class-value`
is already passing with a Tier 1 workaround in the test file (uses `{y}-{m}-{d}` not `{y:4d}-{m:2d}-{d:2d}`);
however the BDD scenario uses type hints — Phase 2 makes the BDD-faithful version passable.

The Section 10 nginx smoke test stub (`nginx-style-log-pattern-extracts-all-fields`) is annotated
"Phase 2+ or Tier 2" but the actual pattern only uses Tier 1 `{var}` captures. It can be implemented
in Phase 2 by filling in the test body with Tier 1 syntax, without waiting for Tier 3. This is an
additional unlock.

### Stubs that remain after Phase 2 (Phase 3)

These require the full constraint mini-language and stay as stubs:

| Test name | Reason |
|-----------|--------|
| `many-constraint-matches-one-or-more-characters` | `many letter` |
| `maybe-constraint-matches-optional-suffix` | `maybe 's'` |
| `maybe-constraint-allows-absent-optional-part` | `maybe 's'` |
| `not-constraint-excludes-a-character-class` | `not '/'` |
| `url-pattern-with-not-and-maybe-constraints` | both |
| `alternation-constraint-matches-one-of-several-literals` | `'jpg' \| 'png' \| 'gif'` |
| `alternation-constraint-rejects-non-matching-literal` | same |
| `rest-constraint-captures-everything-remaining` | `rest` keyword |
| `exact-count-constraint-with-digit` | `4 digit` (word form) |
| `many-digit-used-for-ip-style-parsing` | `many digit` |
| `grouped-alternation-with-parentheses` | `('http' maybe 's') \| 'ftp'` |
| `constrained-adjacent-captures-are-valid` | `{code: 3 any}` (Phase 3 form) |
| `rest-in-non-final-position-is-a-compile-time-error` | `rest` keyword |
| `nested-quantifiers-are-a-compile-time-error` | `many (many digit)` |
| `pattern-is-full-match-anchored-trailing-chars-cause-no-match` | `many letter` |
| `unsatisfiable-constraint-too-few-chars-returns-no-match` | `5 digit` (word form) |

### Stubs that remain after Phase 2 (Phase 4 — is-destructuring)

| Test name | Reason |
|-----------|--------|
| `pattern-destructuring-via-is-binds-all-captures-in-scope` | `#p"..." is "string"` LHS grammar |
| `pattern-destructuring-via-is-throws-on-no-match` | same |

---

## 2. Type Hint Syntax and Regex Generation

A type hint is the string after the `:` in `{name:hint}`. The current `parse-pattern-string` already
stores it in `:constraint` as a trimmed string; Phase 1 ignores it (any constraint causes a
`TODO` path — see `segment->regex` below).

### 2.1 The four shorthands

| Syntax | `:constraint` value | Regex fragment | Notes |
|--------|---------------------|----------------|-------|
| `{name:d}` | `"d"` | `\d+` | One or more digits, greedy |
| `{name:w}` | `"w"` | `\w+` | One or more word chars (`[a-zA-Z0-9_]`), greedy |
| `{name:N}` | `"3"`, `"10"`, etc. | `.{N}` | Exactly N of any character |
| `{name:Nd}` | `"4d"`, `"2d"`, etc. | `\d{N}` | Exactly N digits |

**Disambiguation rule:** A `:constraint` string is a type hint if and only if it matches
the regex `^(\d+)?[dw]?$` with at least one character and follows the table above. The
canonical check order is:
1. Matches `^\d+d$` → `:Nd` (N digits)
2. Matches `^\d+$` → `:N` (N any chars)
3. Is exactly `"d"` → `:d`
4. Is exactly `"w"` → `:w`
5. Otherwise → unknown shorthand (raise `DT-P013`)

### 2.2 Exact regex fragments

All type-hint captures use **greedy** quantifiers, unlike Phase 1's non-greedy `.*?`. This is
correct: a `:d` capture consuming digits will stop naturally when it encounters a non-digit
separator. The last-capture special case (using `.*` instead of `.*?`) does NOT apply to
constrained captures — their character class already bounds the match.

```
:d   →  \d+
:w   →  \w+
:N   →  .{N}         (where N is the integer)
:Nd  →  \d{N}        (where N is the integer)
```

Named-group wrapping (Java syntax):

```
(?<name>\d+)          for :d
(?<name>\w+)          for :w
(?<name>.{3})         for :3
(?<name>\d{4})        for :4d
```

Wildcard `{_:d}` is valid (matches digits, no binding); group name uses `wc<idx>` as in Phase 1.

### 2.3 Non-greedy vs greedy decision rationale

The Phase 1 default `.*?` (non-greedy) works because the next literal anchors the match. Type
hints with a fixed character class (`\d`, `\w`) are naturally bounded — they stop at the first
character outside the class. Using greedy quantifiers here is both correct and simpler. The only
case where this could differ from `.*?` is adjacent constrained captures (e.g.,
`{a:d}{b:d}`); the adjacency validation in `validate-segments!` blocks the unconstrained case,
but two `:d` captures adjacent to each other would both compile — the first `\d+` is greedy and
will consume all digits, leaving nothing for the second. This is a user error. Phase 2 does NOT
need to handle it; the compile-time adjacency check from Phase 1 only blocks unconstrained
adjacency (`nil` constraint on both). A future refinement could warn about adjacent same-class
constraints but it is outside Phase 2 scope.

---

## 3. Changes to `pattern_compiler.clj`

### 3.1 Current state of `segment->regex`

```clojure
;; src/datatwist/pattern_compiler.clj, lines 115–136
(defn- segment->regex [seg seg-idx is-last?]
  (case (:type seg)
    :literal
    (java.util.regex.Pattern/quote (:text seg))

    :capture
    (let [name  (:name seg)
          inner (if is-last? ".*" ".*?")         ; <-- Phase 1 only
          group-name (if (= name "_")
                       (wildcard-group-name seg-idx)
                       name)]
      (str "(?<" group-name ">" inner ")"))))
```

Phase 1 unconditionally uses `.*` / `.*?` for all captures, ignoring `:constraint`.

### 3.2 New private function: `compile-type-hint`

Add before `segment->regex`:

```clojure
(defn- compile-type-hint
  "Parse a Tier 2 type hint string (the raw text after ':' in {name:hint}).
   Returns a regex fragment string (without named-group wrapping), or nil if
   the string is not a recognised type hint.

   Recognised forms:
     \"d\"           -> \"\\\\d+\"
     \"w\"           -> \"\\\\w+\"
     \"<N>\"         -> \".{N}\"   (N is a positive integer string)
     \"<N>d\"        -> \"\\\\d{N}\"

   Returns nil for empty string, nil input, or any string that does not fit
   these four forms — the caller falls through to Phase 3 constraint parsing."
  [hint]
  (cond
    (nil? hint)        nil
    (= hint "d")       "\\d+"
    (= hint "w")       "\\w+"
    (re-matches #"\d+" hint)
    (str ".{" hint "}")
    (re-matches #"(\d+)d" hint)
    (let [n (re-find #"\d+" hint)]
      (str "\\d{" n "}"))
    :else nil))
```

### 3.3 Updated `segment->regex`

Replace the `:capture` branch to call `compile-type-hint` first:

```clojure
(defn- segment->regex [seg seg-idx is-last?]
  (case (:type seg)
    :literal
    (java.util.regex.Pattern/quote (:text seg))

    :capture
    (let [name       (:name seg)
          constraint (:constraint seg)
          group-name (if (= name "_")
                       (wildcard-group-name seg-idx)
                       name)
          inner      (or (compile-type-hint constraint)
                         ;; Phase 3: compile-full-constraint will go here
                         ;; For now, unknown non-nil constraints fall through
                         ;; to unconstrained (Phase 1 behaviour as fallback)
                         (if is-last? ".*" ".*?"))]
      (str "(?<" group-name ">" inner ")"))))
```

**Important:** The `is-last?` parameter is still passed to `segment->regex` for the unconstrained
fallback path, but constrained captures (`compile-type-hint` returning non-nil) ignore `is-last?`
entirely. This is correct: `\d+` is already bounded.

### 3.4 Updated `validate-segments!`

Phase 1 only blocks adjacent captures where **both** have `nil` constraint. That check is already
correct for Phase 2: `{a:d}{b:d}` has non-nil constraints on both and passes validation. No
changes needed to `validate-segments!` for Phase 2. The docstring should be updated to reflect
that Phase 2 validations (unknown hint errors `DT-P013`) are added here:

```clojure
(defn- validate-segments!
  "Check for compile-time errors on the parsed segment vector.
   Raises ex-info for:
   - Adjacent unconstrained captures with no literal separator (DT-P010)
   - [Phase 2] Unrecognised type hint (DT-P013)
   - [Phase 3] rest in non-final position (DT-P011)
   - [Phase 3] Nested quantifiers (DT-P012)"
  ...)
```

Add an unrecognised-hint check inside the loop, after the adjacency check:

```clojure
(when (and (= :capture (:type seg))
           (some? (:constraint seg))
           (nil? (compile-type-hint (:constraint seg))))
  ;; Phase 3 constraint — not yet supported; raise friendly error
  (throw (ex-info (str "Unrecognised pattern constraint: \"" (:constraint seg)
                       "\" in pattern: " source)
                  {:dt/error true
                   :code     "DT-P013"
                   :category "PATTERN ERROR"
                   :message  (str "Constraint \"" (:constraint seg)
                                  "\" is not a recognised type hint. "
                                  "Valid: :d :w :N :Nd (e.g. :4d, :3, :w).")
                   :source   source})))
```

This ensures that a Phase 3 constraint written in a Phase 2 build gives a clear error rather
than silently treating the text as unconstrained. It will be removed when Phase 3 lands.

### 3.5 No changes to `compile-pattern`, `apply-pattern`, or public API

The public shape of `compile-pattern` and `apply-pattern` is unchanged. The `:constraint` field
in the IR segment is already stored (Phase 1 parses it); Phase 2 only adds a consumer of it in
`segment->regex`.

---

## 4. Error Codes

One new error code for Phase 2:

| Code | Category | Trigger |
|------|----------|---------|
| `DT-P013` | `PATTERN ERROR` | Unrecognised constraint string (not a known type hint, not yet Phase 3) |

This code is temporary scaffolding — Phase 3 will replace the throw with real constraint parsing.
It should be added to `src/datatwist/errors.clj`.

The existing Phase 1 code (`DT-P010`) is unchanged.

---

## 5. No Grammar Changes Required

Phase 1 already added the `Pattern` rule to `Atom` and `GuardPattern`. Phase 2 adds no new
syntax — the `{name:hint}` colon is already consumed by the hand-written `parse-pattern-string`
inside `pattern_compiler.clj`. The Instaparse grammar sees only a raw string token.

---

## 6. No Evaluator Changes Required

Phase 1 already dispatches `:Pattern` in `eval-node` and handles it in `eval-guard-block`.
Phase 2 changes are entirely inside `pattern_compiler.clj`. The evaluator calls
`pattern-compiler/compile-pattern` which now produces correct regexes for type-hinted captures.

---

## 7. No Stdlib Changes Required

`extract` and `match?` were added in Phase 1. They call `apply-pattern` which is unchanged.
The Section 2 test `exact-length-type-hint-nd-captures-fixed-digit-count` uses `to-int` — that
function already exists in `stdlib.clj` (line 553: `"to-int" #(if (string? %) (Long/parseLong %) (long %))`).

---

## 8. Implementation Order

The changes are confined to one file: `src/datatwist/pattern_compiler.clj`.

**Step 1:** Add `compile-type-hint` private function (new, ~15 lines).

**Step 2:** Update `segment->regex` `:capture` branch to call `compile-type-hint` (~5 line change).

**Step 3:** Add the unrecognised-hint guard to `validate-segments!` (~10 lines).

**Step 4:** Add `DT-P013` to `src/datatwist/errors.clj` (~4 lines).

**Step 5:** Fill in the 7 Section 2 test stubs in `test/datatwist/pattern_destructuring_test.clj`.

**Step 6:** Fill in the Section 10 nginx smoke test (Tier 1 captures only — no type hints needed,
the nginx pattern uses only `{var}` simple captures).

**Verification after each step:**
```bash
clj -M -e "(require 'clojure.test 'datatwist.pattern-destructuring-test) \
            (clojure.test/run-tests 'datatwist.pattern-destructuring-test)"
```
Run after Step 2, Step 5, and Step 6 to confirm incremental progress.

---

## 9. Test Implementation for Phase 2 Stubs

### Section 2 stubs — exact test bodies

**`iso-date-with-4d-2d-2d-type-hints`**
```clojure
(is (= {:y "2024" :m "01" :d "15"}
       (eval-dt
        "\"2024-01-15\"
            |> (| #p\"{y:4d}-{m:2d}-{d:2d}\" -> {y: y m: m d: d}
                  | _ -> nil)")))
```

**`type-hint-d-enforces-digits-only-octets`**
```clojure
(is (= {:a "10" :b "0" :c "0" :d "1"}
       (eval-dt
        "\"10.0.0.1\"
            |> (| #p\"{a:d}.{b:d}.{c:d}.{d:d}\" -> {a: a b: b c: c d: d}
                  | _ -> nil)")))
```

**`type-hint-d-rejects-non-digit-content`**
```clojure
(is (= "no match"
       (eval-dt
        "\"abc.def.ghi.jkl\"
            |> (| #p\"{a:d}.{b:d}.{c:d}.{d:d}\" -> \"matched\"
                  | _                            -> \"no match\")")))
```

**`exact-length-type-hint-n-captures-fixed-char-count`**
```clojure
(is (= {:code "ABC" :rest "remainder"}
       (eval-dt
        "\"ABC-remainder\"
            |> (| #p\"{code:3}-{rest}\" -> {code: code rest: rest}
                  | _ -> nil)")))
```

**`exact-length-type-hint-nd-captures-fixed-digit-count`**
```clojure
(is (= {:y 2024 :m 1 :d 15}
       (eval-dt
        "\"2024-01-15\"
            |> (| #p\"{y:4d}-{m:2d}-{d:2d}\" -> {y: to-int y m: to-int m d: to-int d}
                  | _ -> nil)")))
```

**`type-hint-w-captures-word-characters-only`**
```clojure
(is (= {:word "hello" :rest "world"}
       (eval-dt
        "\"hello world\"
            |> (| #p\"{word:w} {rest}\" -> {word: word rest: rest}
                  | _ -> nil)")))
```

**`type-hint-d-does-not-match-letters`**
```clojure
(is (= "no match"
       (eval-dt
        "\"ab-12\"
            |> (| #p\"{x:2d}-{y}\" -> \"matched\"
                  | _              -> \"no match\")")))
```

### Section 10 nginx smoke test

The nginx test stub is annotated "Phase 2+ or Tier 2" but the pattern itself uses only Tier 1
`{var}` captures. Fill it in during Phase 2:

```clojure
(deftest nginx-style-log-pattern-extracts-all-fields
  (testing "Nginx-style log pattern extracts all fields (Tier 1 captures)"
    (is (= {:ip "127.0.0.1" :method "GET" :url "/index.html" :status "200"}
           (eval-dt-last
            "log-pat is #p\"{ip} - {user} [{time}] \\\"{method} {url} HTTP/{ver}\\\" {status} {bytes}\""
            "line is \"127.0.0.1 - alice [10/Jan/2024:13:55:36 +0000] \\\"GET /index.html HTTP/1.1\\\" 200 1024\""
            "line |> (| log-pat -> {ip: ip method: method url: url status: status} | _ -> nil)")))))
```

Note: the backslash-escaped `\"` in the DT source string (`\\\"`) needs careful escaping in the
Clojure string literal. Verify exact escaping at implementation time.

---

## 10. Phase 3 Scope Preview

Phase 3 implements the full constraint mini-language inside `{name: <expr>}`. Its grammar:

```
constraint    = alt-expr
alt-expr      = seq-expr ('|' seq-expr)*
seq-expr      = quant-expr+
quant-expr    = quantifier? atom-expr
quantifier    = 'many' | 'some' | 'maybe' | INT | INT '..' INT
atom-expr     = char-class | string-lit | '(' alt-expr ')' | 'not' atom-expr
char-class    = 'digit' | 'letter' | 'word' | 'space' | 'any' | 'rest'
string-lit    = "'" [^']* "'"
```

Mapping to Java regex:

| Constraint expr | Regex fragment |
|-----------------|----------------|
| `digit` | `\d` |
| `letter` | `[a-zA-Z]` |
| `word` | `\w` |
| `space` | `\s` |
| `any` | `.` |
| `rest` | `[\s\S]*` (anchored to end by `$`) |
| `many X` | `X+` |
| `some X` | `X+` (same; `some` is alias for `many`) |
| `maybe X` | `X?` |
| `N X` | `X{N}` |
| `N..M X` | `X{N,M}` |
| `not X` | `[^...]` where `...` is the negated class (only works for char classes and single chars) |
| `'lit'` | `Pattern/quote("lit")` |
| `A \| B` | `(?:A\|B)` |
| `(expr)` | `(?:expr)` |

Phase 3 will:
1. Add `compile-full-constraint [constraint-str] -> regex-fragment` in `pattern_compiler.clj`
2. Remove the `DT-P013` temporary throw from `validate-segments!`
3. Add `rest`-in-non-final-position validation to `validate-segments!`
4. Add nested-quantifier validation to `validate-segments!`
5. Unlock 11 Section 3 stubs + 2 remaining Section 8 stubs + Section 9 partial stubs

The Phase 2 `compile-type-hint` function returns `nil` for unrecognised constraints, which is
the hook point where Phase 3's `compile-full-constraint` will be inserted in `segment->regex`.
The two phases compose cleanly:

```clojure
;; Phase 2 + 3 final segment->regex :capture branch:
inner (or (compile-type-hint constraint)       ; Tier 2: shorthand
          (compile-full-constraint constraint)  ; Tier 3: mini-language (Phase 3)
          (if is-last? ".*" ".*?"))             ; Tier 1: unconstrained fallback
```

---

## 11. File Change Summary

| File | Change | Size |
|------|--------|------|
| `src/datatwist/pattern_compiler.clj` | Add `compile-type-hint`; update `segment->regex` `:capture` branch; add hint-error guard to `validate-segments!` | ~30 lines added/changed |
| `src/datatwist/errors.clj` | Add `DT-P013` | ~4 lines |
| `test/datatwist/pattern_destructuring_test.clj` | Fill in 7 Section 2 stubs + 1 Section 10 stub | ~50 lines changed |

**No changes to:** `resources/datatwist.grammar`, `src/datatwist/evaluator.clj`,
`src/datatwist/stdlib.clj`, `src/datatwist/parser.clj`, `src/datatwist/env.clj`.

Total complexity: **Small**. All changes are in the constraint-to-regex mapping layer.
The evaluator, grammar, and stdlib are untouched.
