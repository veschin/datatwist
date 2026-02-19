# String Pattern Destructuring: `#pattern"..."` Research

Research document for extending DataTwist's destructuring to strings via a `#pattern"..."` syntax. This builds on the existing [regex alternatives research](regex-alternatives-research.md) which recommended a Melody-inspired mini-language (Tier 2) compiled to Java regex via Regal.

**Status:** Research / Design phase. No implementation yet.

---

## Table of Contents

1. [Syntax Design --- Detailed Rules](#1-syntax-design--detailed-rules)
2. [Compilation Strategy](#2-compilation-strategy)
3. [Integration with Existing Language Features](#3-integration-with-existing-language-features)
4. [Edge Cases and Failure Modes](#4-edge-cases-and-failure-modes)
5. [Comparison with Existing Systems](#5-comparison-with-existing-systems)
6. [Risks and Open Questions](#6-risks-and-open-questions)
7. [Recommended Design](#7-recommended-design)

---

## 1. Syntax Design -- Detailed Rules

### 1.1 Basic Capture: `{var}`

A bare `{var}` captures text between the surrounding literal segments. The fundamental question is: **what does it match?**

**Proposed rule: non-greedy, up to next literal.**

```
#pattern"Hello {who}"
; Applied to "Hello World" -> {who: "World"}
; Applied to "Hello "      -> {who: ""}       (empty capture allowed)
; Applied to "Goodbye"     -> nil             (literal "Hello " doesn't match)
```

The capture `{var}` without a constraint matches `.*?` (non-greedy any). This is the Python `parse` library's approach: always match the shortest text that allows the rest of the pattern to succeed, scanning left to right.

**Why non-greedy by default:**
- Greedy `{a}-{b}` on `"x-y-z"` would capture `a="x-y", b="z"`. Non-greedy gives `a="x", b="y-z"`. Non-greedy is more intuitive for human expectations: "capture up to the next separator."
- Python's `parse` library made this choice and it works well in practice.
- Users who want greedy can use `{var: rest}` (see constraints below).

### 1.2 Adjacent Captures: `#pattern"{a}{b}"`

Adjacent captures with no literal separator between them are **ambiguous by definition**. Two options:

**Option A: Reject at compile time.** Adjacent captures without constraints are a syntax error. This is the safest choice.

```
#pattern"{a}{b}"           ; COMPILE ERROR: adjacent captures need constraints
#pattern"{a: 3 any}{b}"   ; OK: a captures exactly 3 chars, b gets the rest
#pattern"{a: digit}{b}"   ; OK: a captures digits, b starts at first non-digit
```

**Option B: Split at midpoint.** Too magical, too surprising.

**Recommendation: Option A.** Adjacent captures without constraints or without a literal separator between them are a compile-time error with a clear message: *"Adjacent captures `{a}` and `{b}` need either a separator or constraints to determine where one ends and the other begins."*

### 1.3 Escaping: Literal `{` and `}`

Use `{{` and `}}` to escape braces, matching the convention from Python's `format()` and `parse`, Rust's `format!()`, and C#'s string interpolation.

```
#pattern"{{escaped}} {var}"
; Matches "{escaped} hello" -> {var: "hello"}
```

This is consistent with the existing DataTwist design: strings use `"..."` with backslash escapes, so `{{`/`}}` inside `#pattern"..."` is a natural extension.

### 1.4 Constraint Mini-Language: `{var: constraint}`

The constraint goes after the colon inside a capture. It describes what characters the capture accepts.

#### Character Classes

| Keyword    | Matches               | Regex equivalent       |
|------------|-----------------------|------------------------|
| `digit`    | `[0-9]`               | `\d`                   |
| `letter`   | `[a-zA-Z]`            | `[a-zA-Z]`             |
| `word`     | `[a-zA-Z0-9_]`        | `\w`                   |
| `space`    | `[ \t\n\r]`           | `\s`                   |
| `any`      | Any character (`.`)   | `.`                    |
| `rest`     | Everything remaining  | `.*` (greedy, anchored)|

These align with the Melody/Pomsky research from the regex alternatives doc.

#### Quantifiers

Quantifiers prefix or follow the character class:

| Syntax              | Meaning            | Regex equivalent |
|---------------------|--------------------|------------------|
| `many X`            | One or more of X   | `X+`             |
| `some X`            | Zero or more of X  | `X*`             |
| `maybe X`           | Zero or one of X   | `X?`             |
| `N X`               | Exactly N of X     | `X{N}`           |
| `N..M X`            | N to M of X        | `X{N,M}`         |
| (bare `X`)          | One of X           | `X`              |

Quantifiers are non-greedy by default (consistent with `{var}` semantics). This means `many digit` compiles to `\d+?` by default. In practice, because captures are anchored between literals, the regex engine resolves this correctly.

**Explicit greediness:** Append `!` for greedy: `many! digit` compiles to `\d+`. This is rarely needed because the anchoring literals already constrain the match, but it exists as an escape hatch.

#### Literals Inside Constraints

Single-quoted strings match exact text:

```
{proto: 'http' maybe 's'}   ; matches "http" or "https"
{sep: '/' | '-' | '.'}      ; matches one of these separators
```

This uses `'...'` (single quotes) to avoid confusion with the outer `"..."` of the pattern string.

#### Negation

`not` negates a character class:

```
{host: many (not '/')}      ; match everything that isn't '/'
{word: many (not space)}     ; match non-whitespace
```

`not` applied to a character class produces a negated character class: `not digit` becomes `[^0-9]`.

`not` applied to a literal produces a negated lookahead is NOT the right approach. Instead, `not 'x'` means "any character that is not 'x'" --- a character class `[^x]`.

For multi-character literals in `not`, we use: `not '/'` = `[^/]`, `not '/:'` = `[^/:]` (neither `/` nor `:`).

#### Alternation

`|` separates alternatives within a constraint:

```
{ext: 'jpg' | 'png' | 'gif'}   ; matches one of these strings
{kind: digit | letter}          ; matches a digit or letter
```

Parentheses group alternatives:

```
{proto: 'http' maybe 's' | 'ftp'}  ; ambiguous! Is it (http maybe s) | ftp?
{proto: ('http' maybe 's') | 'ftp'} ; explicit: matches "http", "https", or "ftp"
```

**Rule: `|` has lowest precedence.** Without parens, `'http' maybe 's' | 'ftp'` means `('http' (maybe 's')) | 'ftp'`. This matches how DataTwist's `or` works at the expression level.

#### Composition: How Constraints Chain

Constraint elements within a capture are concatenated (sequential). There is no implicit quantifier.

```
{ip: digit '.' digit '.' digit '.' digit}
; This is wrong! digit matches ONE digit, not "one or more digits"
; Correct:
{ip: many digit '.' many digit '.' many digit '.' many digit}
```

This is explicit but verbose. The trade-off is correctness and clarity over brevity. Users who need complex patterns should use raw regex (`#"..."`).

#### The `rest` Capture

`rest` is special: it matches everything remaining in the string (greedy `.*` with no following pattern). It can only appear as the last capture's constraint:

```
#pattern"/{path: rest}"
; Applied to "/foo/bar/baz" -> {path: "foo/bar/baz"}
```

`rest` in a non-final position is a compile-time error.

### 1.5 Optional Sections

Optional sections use `maybe` at the constraint level, not at the pattern level. There is no `[optional]` syntax at the pattern level --- that would conflict with DataTwist's function/list syntax.

```
; Match with optional port
#pattern"{host}:{port: many digit}"
; Does NOT match "example.com" (no colon)

; To make port optional, you need two patterns or a guard:
url | #pattern"{host}:{port: many digit}" -> {host, port: to-int port}
    | #pattern"{host}"                     -> {host, port: 80}
```

This is a deliberate limitation. Trying to express optional *literal segments* inside a single pattern (like `#pattern"{host}(?::{port})?"`-style) adds enormous complexity. Let guards handle it --- that's what they're for.

### 1.6 Summary of Pattern Grammar

```
pattern       = segment*
segment       = literal | capture
literal       = (char | '{{' | '}}')+     ; any non-{ char, or escaped braces
capture       = '{' name (':' constraint)? '}'
name          = identifier                  ; same as DataTwist identifiers
constraint    = alt-expr
alt-expr      = seq-expr ('|' seq-expr)*
seq-expr      = quant-expr+
quant-expr    = quantifier? atom-expr
quantifier    = 'many' '!'? | 'some' '!'? | 'maybe' | INT | INT '..' INT
atom-expr     = char-class | string-lit | '(' alt-expr ')' | 'not' atom-expr
char-class    = 'digit' | 'letter' | 'word' | 'space' | 'any' | 'rest'
string-lit    = "'" [^']* "'"
```

---

## 2. Compilation Strategy

### 2.1 Compilation Pipeline

```
#pattern"..."  -->  parse pattern string  -->  Regal data  -->  java.util.regex.Pattern
                    (custom parser)            (intermediate)    (compiled regex)
```

**Step 1: Parse the pattern string.** This happens at eval time, not read time (see 2.2). The pattern string is parsed into an intermediate representation (IR) --- a vector of literal/capture segments.

**Step 2: Convert IR to Regal data.** Each segment becomes a Regal form:
- Literals become strings (Regal auto-escapes them via `\Q...\E`)
- Captures become `[:capture ...]` with named groups

**Step 3: Compile Regal to regex.** `lambdaisland.regal/regex` produces a `java.util.regex.Pattern`.

### 2.2 Read Time vs Eval Time

**Critical design question: When does `#pattern"..."` get processed?**

**Option A: Clojure reader macro (read time).** DataTwist uses Instaparse for parsing, NOT the Clojure reader. The `#pattern"..."` syntax would need to survive Instaparse parsing as a single token, then get compiled later. This is feasible: add a grammar rule like:

```
Pattern = <'#pattern"'> #'(?:[^"\\]|\\.)*' <'"'>
```

This treats `#pattern"..."` as a single atom in the grammar, with the inner content as a raw string. The evaluator then parses and compiles the inner string.

**Option B: Pure eval-time processing.** The grammar captures `#pattern"..."` as an atom. The evaluator calls a `compile-pattern` function that:
1. Parses the pattern string into segments
2. Generates a regex with named capture groups
3. Returns a compiled `PatternMatcher` value (a map/record)

**Recommendation: Option B (eval time, grammar-level token).** Reasons:
- Instaparse already handles the grammar; no Clojure `data_readers.clj` needed
- Clojure tagged literals require namespace-qualified tags (`#dt/pattern"..."`), which is ugly
- Eval-time processing means pattern compilation can use the environment (though we probably won't need this initially)
- Consistent with how `#"..."` (regex) is already handled: grammar captures the content, evaluator compiles it

### 2.3 Named Capture Groups and Object Mapping

Java regex named groups: `(?<name>pattern)`. The capture `{who}` becomes `(?<who>.*?)`.

After matching, the evaluator extracts groups into a DataTwist object:

```clojure
;; Evaluator pseudocode
(let [m (re-matcher compiled-pattern input-string)]
  (when (.matches m)
    (into {}
      (for [name capture-names]
        [(keyword name) (.group m name)]))))
```

Result: `{who: "World"}` --- a standard DataTwist object with keyword keys.

**Important:** All captured values are strings. Type conversion is explicit:

```
text | #pattern"{y: 4 digit}-{m: 2 digit}-{d: 2 digit}" -> {
  year: to-int y
  month: to-int m
  day: to-int d
}
```

This is consistent with DataTwist's philosophy: explicit over implicit.

### 2.4 Constraint-to-Regex Translation Table

| Constraint        | Regal Form                               | Java Regex            |
|-------------------|------------------------------------------|-----------------------|
| (none)            | `[:*? :any]`                             | `.*?`                 |
| `digit`           | `:digit`                                 | `\d`                  |
| `letter`          | `[:class [\a \z] [\A \Z]]`              | `[a-zA-Z]`            |
| `word`            | `:word`                                  | `\w`                  |
| `space`           | `:whitespace`                            | `\s`                  |
| `any`             | `:any`                                   | `.`                   |
| `rest`            | `[:* :any]`                              | `.*`                  |
| `many digit`      | `[:+? :digit]`                           | `\d+?`                |
| `many! digit`     | `[:+ :digit]`                            | `\d+`                 |
| `some digit`      | `[:*? :digit]`                           | `\d*?`                |
| `maybe 's'`       | `[:? "s"]`                               | `(?:s)?`              |
| `4 digit`         | `[:repeat :digit 4]`                     | `\d{4}`               |
| `2..4 letter`     | `[:repeat [:class [\a \z] [\A \Z]] 2 4]`| `[a-zA-Z]{2,4}`       |
| `not digit`       | `[:not :digit]`                          | `[^\d]` = `\D`        |
| `not '/'`         | `[:not \/]`                              | `[^/]`                |
| `'http' maybe 's'`| `[:cat "http" [:? "s"]]`                 | `http(?:s)?`           |
| `'a' \| 'b'`      | `[:alt "a" "b"]`                         | `(?:a\|b)`            |

### 2.5 Should Instaparse Parse the Constraint Mini-Language?

**No.** The constraint mini-language is inside a string literal (`#pattern"..."`). Instaparse parses DataTwist source code, not the contents of string literals. The pattern string is parsed by a separate, purpose-built parser.

Options for the constraint parser:
1. **Hand-written recursive descent.** Simple, no dependencies, ~100 lines of Clojure. The constraint language is tiny (see grammar in 1.6).
2. **A second Instaparse grammar.** Possible but overkill --- Instaparse is designed for complex grammars, not 10-rule mini-languages.
3. **Regex-based tokenizer + precedence parser.** Works but fragile.

**Recommendation: Hand-written parser.** The constraint language is small enough that a recursive descent parser in ~100 lines is cleaner than adding a second grammar file. It also avoids the dependency of loading Instaparse twice at different levels.

### 2.6 Integration with Regal

[Regal](https://github.com/lambdaisland/regal) (`lambdaisland/regal`) provides regex-as-data. It would be a natural compilation target:

```clojure
;; #pattern"Hello {who}" compiles to:
[:cat "Hello " [:capture [:*? :any]]]  ; Regal form
;; which produces: #"Hello (.*?)"      ; but with named groups
```

**Problem:** Regal does not support named capture groups as of v0.0.143. It only has `[:capture ...]` which produces unnamed groups `(...)`.

**Workaround options:**
1. **Post-process Regal output.** Generate with `[:capture ...]`, then string-replace `(` with `(?<name>` at known positions. Fragile.
2. **Skip Regal, generate regex strings directly.** The constraint-to-regex translation is simple enough that building regex strings directly (with proper escaping via `Pattern/quote`) is straightforward.
3. **Fork/extend Regal.** Not worth it for one feature.

**Recommendation: Generate regex strings directly.** Regal's value is composability and cross-platform support; we don't need either. Direct regex generation with `java.util.regex.Pattern/quote` for literal escaping is simpler and gives us full control over named groups.

```clojure
(defn compile-pattern [pattern-str]
  (let [segments (parse-pattern-string pattern-str)
        regex-str (build-regex-string segments)
        capture-names (extract-names segments)]
    {:regex (re-pattern regex-str)
     :names capture-names}))
```

---

## 3. Integration with Existing Language Features

### 3.1 Guards and Pattern Matching

The most natural integration point. `#pattern` becomes a new kind of guard pattern alongside objects, lists, and literals:

```
classify is [input ->
  | #pattern"{name}@{domain}"       -> {type: "email", name, domain}
  | #pattern"http://{host}/{path}"  -> {type: "url", host, path}
  | _                               -> {type: "text", value: input}
]
```

**How it works in `eval-guard-block`:**

Currently the evaluator checks guard patterns in this order (from `evaluator.clj:1146-1204`):
1. Wildcard (`_`)
2. DestructListPattern (`[x & rest]`)
3. DestructObjPattern (`{name: n}`)
4. List literal (`[1 2 3]`)
5. Object literal (`{type: "book"}`)
6. Bare literal (`42`, `"ok"`, `true`, `nil`)
7. Boolean expression (fallback)

`#pattern` would insert as a new case, checked before boolean expressions:

```
8. Pattern literal (#pattern"...") --- match string against compiled regex, bind captures
```

When the current context (`_`) is a string and the pattern matches, the captured variables are bound in `match-env` and the result arm is evaluated with those bindings. If the context is not a string, the pattern doesn't match (returns `[false env]`).

**Grammar change:** Add a `Pattern` production to `Atom`:

```
Atom = Float / Integer / String / Boolean / Nil / Keyword / Regex / Pattern / ...
Pattern = <'#pattern"'> #'(?:[^"\\]|\\.)*' <'"'>
```

And add `Pattern` to `GuardPattern`:

```
GuardPattern = DestructListPattern / DestructObjPattern / Pattern / OrExpr
```

### 3.2 Destructuring with `is`

Patterns can be used on the left side of `is`, like object and list destructuring:

```
{name, domain} is ("alice@example.com" | #pattern"{name}@{domain}" -> {name, domain})
```

But this is clunky. A cleaner approach: allow `#pattern` directly in destructuring position:

```
#pattern"{y}-{m}-{d}" is "2024-01-15"
; Binds y = "2024", m = "01", d = "15"
```

**Implementation:** In `eval-expr` for `:Binding`, when the target is a `Pattern` node, compile the pattern, match against the value, and bind the captured names in the environment.

**Failure behavior:** If the pattern doesn't match, throw an exception (same as object destructuring on a non-map: `"Cannot destructure string as pattern ..."`). This is consistent with existing destructuring semantics.

### 3.3 Nesting: Pattern Inside Object Destructuring

This would be powerful but complex:

```
; Hypothetical: pattern inside object destruct
{email: #pattern"{name}@{domain}"} is user
; Destructures user.email via pattern, binds name and domain
```

**Recommendation: Defer this.** It requires the destructuring system to recognize `Pattern` nodes as sub-patterns and invoke the pattern matcher recursively. The implementation cost is moderate but the design questions are significant (what if the field is nil? what if it's not a string?). Start with top-level pattern destructuring only.

### 3.4 Pipeline Functions: `extract`, `match`, `replace`

Patterns should work with pipeline functions:

```
; extract: apply pattern, return matched object or nil
logs |> map (extract _.message #pattern"[{level}] {text}")
; Each log line becomes {level: "ERROR", text: "disk full"} or nil

; match?: test if pattern matches (boolean)
data |> filter (match? _.email #pattern"{name}@{domain}")

; replace: substitute captures (reverse operation)
; This is more complex and probably not needed in v1
```

These are stdlib functions that take a pattern value and a string:

```clojure
;; In stdlib
(defn dt-extract [s pattern]
  (when (string? s)
    (let [m (re-matcher (:regex pattern) s)]
      (when (.matches m)
        (into {} (for [n (:names pattern)]
                   [(keyword n) (.group m n)]))))))

(defn dt-match? [s pattern]
  (boolean (dt-extract s pattern)))
```

### 3.5 Named Patterns via `is`

Patterns are first-class values. When bound with `is`, the runtime value is a map:

```
date-fmt is #pattern"{y: 4 digit}-{m: 2 digit}-{d: 2 digit}"
```

At runtime, `date-fmt` holds:

```clojure
{:dt/type :pattern
 :regex   #"(?<y>\d{4})-(?<m>\d{2})-(?<d>\d{2})"
 :names   ["y" "m" "d"]
 :source  "{y: 4 digit}-{m: 2 digit}-{d: 2 digit}"}
```

The `:source` field enables readable error messages and REPL display.

When a bound pattern is used in a guard position, the evaluator detects the value is a `:pattern` type and applies the regex match:

```
text | date-fmt -> {y, m, d}
     | _        -> "not a date"
```

This requires the evaluator's guard block to check if the evaluated guard expression is a pattern value (not just a boolean). If so, apply the pattern match against `_`.

---

## 4. Edge Cases and Failure Modes

### 4.1 Pattern Doesn't Match

**In guards:** Fall through to the next arm. Return `nil` if no arm matches (same as existing behavior in `eval-guard-block`).

```
"hello" | #pattern"{a}@{b}" -> "email"
        | _                  -> "not email"
; Result: "not email"
```

**In `is` destructuring:** Throw a runtime error:

```
#pattern"{a}@{b}" is "hello"
; ERROR: Pattern does not match: "{a}@{b}" applied to "hello"
```

**In `extract`:** Return `nil`.

```
extract "hello" #pattern"{a}@{b}"
; Result: nil
```

This mirrors the existing behavior of DataTwist: destructuring failure is an error, guard failure is a fall-through, function failure returns nil.

### 4.2 Ambiguous Patterns

`#pattern"{a}-{b}"` applied to `"x-y-z"`:

Non-greedy left-to-right: `a = "x"`, `b = "y-z"`.

This is deterministic and predictable. The rule is always: **each capture matches the minimum text needed, scanning left to right.** This is the same as Python's `parse` library.

For the reverse (`a` gets everything up to the LAST `-`), users must be explicit:

```
#pattern"{a: many (not '-')}-{b}"
; a = "x", b = "y-z" (same result, but explicit)

#pattern"{a: many! any}-{b}"
; a = "x-y", b = "z" (greedy! forces longest match for a)
```

### 4.3 Empty String Matching

```
#pattern"{a}-{b}" applied to "-"
; a = "", b = "" (both capture empty strings)

#pattern"Hello {who}" applied to "Hello "
; who = "" (empty capture)

#pattern"{a}" applied to ""
; a = "" (captures entire empty string)
```

Empty captures are valid. The result is an empty string `""`, not `nil`. This is consistent: the pattern matched, and the captured text happened to be empty.

### 4.4 Non-String Input

When a pattern is used in a guard and `_` is not a string:

```
42 | #pattern"{x}" -> x
   | _             -> "not a string"
; Result: "not a string"
```

Pattern guards only match string values. If the context is not a string, the pattern does not match (no error, just fall-through).

In `extract`, a non-string input returns `nil`:

```
extract 42 #pattern"{x}"
; Result: nil
```

### 4.5 Unicode Handling

Java's `Pattern` class has full Unicode support. Character classes need clear semantics:

- `digit` = `\d` = Unicode digits (includes non-ASCII digits by default in Java with `UNICODE_CHARACTER_CLASS` flag). **Recommendation: use `[0-9]` explicitly for ASCII-only digits**, which is what most users expect. Document this clearly.
- `letter` = `\p{L}` (Unicode letter category) or `[a-zA-Z]` (ASCII only)? **Recommendation: ASCII-only by default.** Add `unicode-letter`, `unicode-digit` for explicit Unicode matching. This avoids surprises.
- `word` = `[a-zA-Z0-9_]` (ASCII). Add `unicode-word` for `[\p{L}\p{N}_]`.

### 4.6 Multiline Strings

By default, patterns match single-line strings. The `.` metacharacter does NOT match `\n`.

For multiline matching, the user should use raw regex `#"(?s)..."` which enables `DOTALL` mode. `#pattern` is designed for simple structured text parsing, not multiline document processing.

If needed in the future, a flag could be added: `#pattern/m"..."` or a function parameter. Defer this.

### 4.7 Catastrophic Backtracking

The non-greedy default significantly reduces backtracking risk. However, certain patterns can still cause exponential behavior:

**Dangerous:** `#pattern"{a: many (digit | letter)}{b: many (digit | letter)}"` --- adjacent captures with overlapping character classes. This is caught by the compile-time adjacent capture check (Section 1.2).

**Also dangerous:** `#pattern"{a: many (many digit)}"` --- nested quantifiers. **Mitigation:** Forbid nested quantifiers at compile time. `many (many X)` is always a bug.

**Safe patterns:** Most real-world patterns have literal separators between captures (`-`, `/`, `@`, `:`, etc.), which anchor the regex and prevent backtracking.

### 4.8 Unsatisfiable Constraints

```
#pattern"{x: 5 digit}" applied to "abc"
; No match (too few characters, wrong class). Returns nil / falls through.
```

Unsatisfiable constraints just mean the pattern doesn't match. There's no special error.

### 4.9 Full Match vs Partial Match

**Patterns are anchored by default (full match).** The entire input string must match the entire pattern.

```
#pattern"Hello {who}" applied to "Hello World, how are you?"
; who = "World, how are you?" (matches everything after "Hello ")
; NOT a partial match --- the pattern consumes the whole string

#pattern"Hello {who: many letter}" applied to "Hello World, how are you?"
; NO MATCH --- "World" matches, but ", how are you?" is left over
```

**Rationale:** Full matching (anchored `^...$`) is more predictable and safer. Partial matching (search) is a separate operation:

```
; Use search/find for partial matching
find-in "Hello World, how are you?" #pattern"Hello {who: many letter}"
; Result: {who: "World"} (finds first match within string)
```

---

## 5. Comparison with Existing Systems

### 5.1 Python `parse` Library

**Syntax:** `parse("Hello {who}", "Hello World")` returns `Result` with `who = "World"`.

**Strengths:**
- Reverse of `format()` --- instantly familiar to Python users
- Type specifiers: `{age:d}` for integers, `{ratio:f}` for floats
- Non-greedy by default (shortest match left-to-right)
- `compile()` for pre-compilation
- Width specifiers for fixed-width parsing: `{:.2}` for 2 chars

**Weaknesses:**
- No character class constraints beyond type specifiers
- No alternation or negation
- Adjacent captures need width specifiers
- No named groups in output (uses positional or field names)

**Relevance to DataTwist:** The `parse` library is the closest analog to `#pattern`. Our design extends it with a richer constraint language while keeping the `{var}` capture syntax. Key learnings:
- Non-greedy default is the right choice
- Width/type specifiers are heavily used --- our quantifier syntax (`4 digit`) covers this
- The simplicity of `{var}` for unconstrained capture is valuable

### 5.2 Elixir Binary Pattern Matching

**Syntax:** `<<header::binary-size(4), rest::binary>> = "HELLO"`

**Strengths:**
- First-class language feature, not a library
- Type annotations on captures: `binary-size(4)`, `integer`, `float`
- Works in function heads (pattern matching dispatch)
- Handles binary data, not just text

**Weaknesses:**
- Byte-level, not character-level --- awkward for text parsing
- All segments except the last must have known size
- No literal matching within the pattern (must use `<<>>` with guards)
- Can't express "match until delimiter"

**Relevance to DataTwist:** Elixir's approach is powerful for binary protocols but too low-level for text parsing. Our constraint language is higher-level (character classes, quantifiers). The key lesson is that **fixed-width captures** (`4 digit`) are important for parsing structured formats like dates.

### 5.3 Raku (Perl 6) Grammars

**Syntax:**
```raku
grammar URL {
    token TOP { <proto> '://' <host> '/' <path> }
    token proto { 'http' 's'? }
    token host { <-[/]>+ }
    token path { .* }
}
```

**Strengths:**
- Most powerful string pattern matching in any language
- Named rules, recursive grammars, action classes
- Built-in character classes: `<alpha>`, `<digit>`, `<ws>`
- Negation: `<-[/]>` = any char except `/`
- Quantifiers: `+`, `*`, `?`, `** 2..5`
- Composition: rules call other rules
- First-class in the language syntax

**Weaknesses:**
- Extremely complex --- full PEG + extensions
- Unique syntax that doesn't transfer to other languages
- Steep learning curve
- Overkill for simple pattern matching

**Relevance to DataTwist:** Raku is the gold standard for what's possible. We should NOT try to match its power, but we can borrow:
- Named capture syntax (our `{var}`)
- Character class keywords (`digit`, `letter`)
- Negation syntax (`not`)
- The idea of composable named patterns (our `date-fmt is #pattern"..."`)

### 5.4 Rust `nom` Parser Combinators

**Syntax:**
```rust
fn parse_date(input: &str) -> IResult<&str, (u32, u32, u32)> {
    let (input, year) = take(4usize)(input)?;
    let (input, _) = char('-')(input)?;
    let (input, month) = take(2usize)(input)?;
    ...
}
```

**Strengths:**
- Zero-copy parsing, very fast
- Type-safe: each parser returns a typed result
- Composable via combinators
- Handles streaming/incomplete input

**Weaknesses:**
- Verbose --- not suitable for inline pattern expressions
- Requires understanding of parser combinators
- Rust-specific

**Relevance to DataTwist:** `nom` is a library, not a syntax. It confirms that **streaming parsing** and **typed results** are valuable, but the combinator approach is too verbose for DataTwist's target audience (data analysts).

### 5.5 Lua `string.match` with Captures

**Syntax:** `string.match("alice@example.com", "(%a+)@(%a+)%.(%a+)")`

**Strengths:**
- Simple, built into the language
- `%a` for letters, `%d` for digits, `%w` for word chars
- `+`, `*`, `-` (non-greedy `*`), `?` quantifiers
- Character classes: `[%a%d]`, `[^%s]` (negation)

**Weaknesses:**
- No named captures (positional only)
- Lua-specific `%` escaping (not standard regex)
- No alternation within patterns
- Limited power compared to PCRE

**Relevance to DataTwist:** Lua's character class shorthand (`%a`, `%d`) inspired our keyword approach (`letter`, `digit`). The named captures in our design are a significant improvement over Lua's positional returns.

### 5.6 F# Active Patterns

**Syntax:**
```fsharp
let (|ParseDate|_|) input =
    let m = Regex.Match(input, @"(\d{4})-(\d{2})-(\d{2})")
    if m.Success then Some (m.Groups.[1].Value, m.Groups.[2].Value, m.Groups.[3].Value)
    else None

match input with
| ParseDate (y, m, d) -> sprintf "Date: %s-%s-%s" y m d
| _ -> "Not a date"
```

**Strengths:**
- First-class pattern matching integration
- Type-safe: active patterns return `Option<T>`
- Composable: partial active patterns can be combined
- Parameterized: `(|Regex|_|) pattern input`

**Weaknesses:**
- The pattern itself is still regex --- no high-level syntax
- Boilerplate: must define each active pattern as a function
- F#-specific

**Relevance to DataTwist:** F# active patterns show how custom matchers integrate with pattern matching. Our approach is similar in spirit: `#pattern` is essentially an inline active pattern. The key difference is that we provide a built-in DSL instead of requiring users to write regex + extraction code.

### 5.7 Summary Comparison

| System | Named Captures | Constraints | Composable | Inline Syntax | Complexity |
|--------|---------------|-------------|------------|---------------|------------|
| Python `parse` | Yes (field names) | Type specifiers only | No | Yes | Low |
| Elixir binary | Yes (var names) | Size/type annotations | No | Yes | Medium |
| Raku grammars | Yes (rule names) | Full PEG + extensions | Yes (rules) | Yes | Very High |
| Rust `nom` | By combinator | Type-safe combinators | Yes | No (library) | High |
| Lua `string.match` | No (positional) | `%` classes | No | Yes | Low |
| F# active patterns | By deconstruction | Regex inside | Yes (compose) | Partial | Medium |
| **DataTwist `#pattern`** | **Yes (`{var}`)** | **Mini-language** | **Yes (bind)** | **Yes** | **Low-Medium** |

---

## 6. Risks and Open Questions

### 6.1 Syntax Overload: `{var: constraint}` Looks Like Object Syntax

**Risk level: Medium.**

DataTwist objects use `{key: value}` with a postfix colon. Pattern captures use `{var: constraint}` with the same syntax. Inside `#pattern"..."` this is unambiguous because the entire thing is a string. But conceptually, users might confuse the two.

**Mitigation:**
- The `#pattern"..."` prefix makes the context clear
- Document the parallel explicitly: "Pattern captures use the same `name: spec` syntax as object fields, but inside `#pattern`, the spec describes *what to match*, not a value."
- Syntax highlighting in the editor can color pattern captures differently

**Verdict:** Acceptable. The visual similarity is actually a feature --- it reinforces the idea that pattern matching produces an object.

### 6.2 Greedy vs Non-Greedy Default

**Decision: Non-greedy.**

This is the right default for the `#pattern` use case. Users expect `{name}@{domain}` to split at the `@`, not greedily consume everything. The `!` suffix for greedy (`many! any`) is the escape hatch.

**Risk:** Users familiar with regex (where `.*` is greedy) may be surprised. **Mitigation:** Document clearly. The target audience (data analysts) is less likely to have regex muscle memory.

### 6.3 Complexity Cliff in Constraint Language

**Risk level: High. This is the biggest design risk.**

The constraint mini-language starts simple (`digit`, `many letter`) but can grow complex fast:

```
; Simple - good
{y: 4 digit}

; Medium - still readable
{proto: 'http' maybe 's'}

; Complex - approaching regex complexity
{host: many (not '/' | not ':')}

; Too complex - users should use regex
{path: (many letter '/' | '_') maybe ('?' many (not '#')) maybe ('#' rest)}
```

**Where is the cliff?** When constraints need nested grouping and alternation, the mini-language becomes harder to read than regex. At that point, users should switch to raw regex.

**Mitigation:**
- Keep the constraint language small. Do NOT add features like backreferences, lookahead, or recursive patterns.
- Document the "complexity threshold": if your constraint has more than one level of nesting, use `#"..."` regex instead.
- Provide clear error messages when patterns get too complex.
- The constraint parser should reject patterns beyond a complexity limit (e.g., nesting depth > 2).

### 6.4 Relationship Between `#pattern` and Raw Regex `#"..."`

| Feature | `#pattern"..."` | `#"..."` |
|---------|-----------------|----------|
| Named captures | Yes (`{var}`) | Manual (`(?<name>...)`) |
| Returns | DataTwist object | Java `Matcher` |
| Readable | Yes | No |
| Powerful | Medium | Full |
| Use case | Structured text parsing | Complex regex, validation |

**They are complementary, not competing.** `#pattern` handles 80-90% of text parsing needs. Raw regex handles the rest. Document the guideline: "Start with `#pattern`. If the constraint language can't express what you need, switch to `#"..."` with manual group extraction."

### 6.5 Pattern Composition

**Question: Should `pattern-a + pattern-b` compose patterns?**

```
; Hypothetical composition
date-fmt is #pattern"{y: 4 digit}-{m: 2 digit}-{d: 2 digit}"
time-fmt is #pattern"{h: 2 digit}:{min: 2 digit}:{sec: 2 digit}"
datetime-fmt is date-fmt + "T" + time-fmt
; Would produce #pattern"{y: 4 digit}-{m: 2 digit}-{d: 2 digit}T{h: 2 digit}:{min: 2 digit}:{sec: 2 digit}"
```

**Recommendation: Defer.** Composition is powerful but adds significant complexity:
- What does `+` mean for patterns? Concatenation of the underlying regex?
- How do overlapping variable names work?
- This is Raku-level power, which we explicitly want to avoid.

For v1, users can write longer patterns or use multiple sequential matches. Composition can be added later if there's demand.

### 6.6 Reader Macro Implementation Feasibility

**`#pattern"..."` in Instaparse: Feasible.**

The grammar rule is straightforward:

```
Pattern = <'#pattern"'> #'(?:[^"\\]|\\.)*' <'"'>
```

This captures `#pattern"..."` as a single token. The inner content (between the quotes) is a raw string that the evaluator parses separately.

**Potential issue:** The `#` character. In the current grammar, `#"..."` is already used for regex literals (line 160 of the grammar). Adding `#pattern"..."` requires the grammar to distinguish between `#"..."` (regex) and `#pattern"..."` (pattern). This is possible because Instaparse uses ordered choice (`/`):

```
Atom = ... / Pattern / Regex / ...
Pattern = <'#pattern"'> #'(?:[^"\\]|\\.)*' <'"'>
Regex   = <'#"'> #'(?:[^"\\]|\\.)*' <'"'>
```

`Pattern` is tried first because `#pattern"` is a longer prefix than `#"`. Instaparse will correctly distinguish them.

**Alternative syntax considered:**
- `p"..."` --- shorter but `p` is a valid identifier, causing ambiguity
- `~"..."` --- tilde prefix, no conflict but unfamiliar
- `pattern("...")` --- function call syntax, but then it's a runtime string not a literal
- `@"..."` --- `@` conflicts with potential future uses (decorators, deref)

**`#pattern"..."` is the best option.** It follows the Clojure convention (`#` prefix for reader macros), avoids conflicts with existing syntax, and is self-documenting.

---

## 7. Recommended Design

### 7.1 Minimal Viable Design (v1)

The goal: handle 90% of structured text parsing use cases with a clean, simple syntax. Leave the remaining 10% to raw regex.

#### Grammar Addition

```
(* --- String Pattern --- *)
Pattern = <'#pattern"'> #'(?:[^"\\]|\\.)*' <'"'>
```

Add `Pattern` to `Atom` (before `Regex` for ordered choice priority) and to `GuardPattern`.

#### Core Capture Syntax

```
{var}                      ; unconstrained, non-greedy (.*?)
{var: constraint}          ; constrained capture
```

#### Constraint Language (v1 --- Minimal)

```
constraint = alt
alt        = seq ('|' seq)*
seq        = element+
element    = quantifier? atom
quantifier = 'many' | 'some' | 'maybe' | INT | INT '..' INT
atom       = 'digit' | 'letter' | 'word' | 'space' | 'any' | 'rest'
           | 'not' atom
           | STRING                    ; single-quoted literal
           | '(' alt ')'
```

**Deliberately excluded from v1:**
- Greedy modifier (`!`) --- non-greedy only, simplifies mental model
- `unicode-letter`, `unicode-digit` --- ASCII only
- Nested quantifiers --- compile-time error
- Pattern composition (`+`) --- write longer patterns
- Partial match / search --- full match only
- Optional literal sections --- use guards instead

#### Runtime Value

```clojure
{:dt/type :pattern
 :regex   <compiled java.util.regex.Pattern>
 :names   ["var1" "var2" ...]
 :source  "original pattern string"}
```

#### Matching Semantics

1. Input must be a string. Non-string inputs: no match (in guards) or `nil` (in functions).
2. Pattern is anchored (full match: `^...$`).
3. Captures are non-greedy left-to-right.
4. Adjacent unconstrained captures are a compile-time error.
5. All captured values are strings. Type conversion is explicit.
6. Empty captures are valid (empty string `""`).

#### Integration Points

**Guards (pattern matching):**
```
input | #pattern"{name}@{domain}" -> {type: "email", name, domain}
      | _                         -> {type: "unknown"}
```

**Binding (`is`):**
```
date-fmt is #pattern"{y: 4 digit}-{m: 2 digit}-{d: 2 digit}"
```

**Destructuring:**
```
#pattern"{y}-{m}-{d}" is "2024-01-15"
```

**Pipeline functions:**
```
data |> map (extract _.text #pattern"[{level}] {msg}")
data |> filter (match? _.email #pattern"{_}@{_}")
```

Note: `{_}` as a wildcard capture (not bound, just matched) uses the existing DataTwist convention for `_` as "don't care".

#### Stdlib Functions

| Function | Signature | Returns |
|----------|-----------|---------|
| `extract` | `extract string pattern` | Object or nil |
| `match?` | `match? string pattern` | Boolean |

#### Error Messages

```
; Compile-time
  Adjacent captures need constraints

  1 | date-fmt is #pattern"{a}{b}"
                           ^^^^^^
  Captures {a} and {b} have no separator or constraints.
  Add a literal between them or constrain each: {a: 4 digit}{b: rest}

; Runtime (destructuring)
  Pattern does not match

  3 | #pattern"{y: 4 digit}-{m}-{d}" is input
      ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  The pattern did not match the string "not-a-date"
```

### 7.2 Implementation Plan

**Phase 1: Grammar + Parser (est. 1 day)**
- Add `Pattern` production to grammar
- Write pattern string parser (hand-written, ~100-150 lines)
- Unit tests for pattern parsing

**Phase 2: Regex Compilation (est. 1 day)**
- Constraint-to-regex translation
- Named capture group generation
- Compile-time validation (adjacent captures, nested quantifiers, etc.)
- Unit tests for regex generation

**Phase 3: Evaluator Integration (est. 2 days)**
- `eval-node` for `:Pattern` --- compile and return pattern value
- Guard block integration --- string pattern matching
- `is` binding with pattern destructuring
- Stdlib functions: `extract`, `match?`
- Unit tests for all integration points

**Phase 4: BDD + Polish (est. 1 day)**
- Write BDD feature file for string pattern destructuring
- Error message formatting
- Edge case tests (empty strings, Unicode, non-string inputs)
- Documentation

**Total estimate: 5 days.**

### 7.3 Dependencies

- **No new library dependencies.** Pattern compilation uses `java.util.regex.Pattern` directly (no Regal). The pattern string parser is hand-written (no second Instaparse grammar).
- **Grammar change:** One new production rule, added to `Atom` and `GuardPattern`.
- **Evaluator change:** New case in `eval-node`, new case in `eval-guard-block`, new case in binding evaluation.
- **Stdlib change:** Two new functions (`extract`, `match?`).

### 7.4 What v1 Does NOT Handle (Escape to Regex)

- Backreferences (`\1`, `\k<name>`)
- Lookahead/lookbehind
- Unicode category matching (`\p{L}`)
- Multiline / DOTALL mode
- Possessive quantifiers
- Recursive patterns
- Conditional patterns

For all of these, use `#"..."` raw regex with manual `re-find` / `re-matches`:

```
; Complex pattern that #pattern can't express
result is re-find #"(?<year>\d{4})(?=\s+Q[1-4])" input
```

### 7.5 Future Extensions (v2+)

If `#pattern` proves useful, possible extensions:

1. **Greedy modifier** (`many! digit`) --- straightforward regex change
2. **Pattern composition** (`date-fmt + "T" + time-fmt`) --- concatenation of underlying regex + name merging
3. **Nested pattern in destructuring** (`{email: #pattern"{name}@{domain}"}`)
4. **Partial match function** (`find-in text pattern` for search/scan)
5. **Replace function** (`replace-pattern text pattern template`)
6. **Type coercion constraints** (`{age: int}`, `{ratio: float}`) that auto-convert captured strings
7. **Unicode character classes** (`unicode-letter`, `unicode-digit`)
8. **Multiline flag** (`#pattern/m"..."`)

Each of these is a self-contained addition that doesn't break v1.

---

## Appendix A: Full Example Walkthrough

```
; Define a log line pattern
log-fmt is #pattern"[{level: many letter}] {timestamp: 4 digit '-' 2 digit '-' 2 digit} {message: rest}"

; Sample data
lines is [
  "[ERROR] 2024-01-15 Disk full"
  "[INFO] 2024-01-15 Server started"
  "[WARN] 2024-01-16 Memory low"
  "malformed line"
]

; Parse all lines, filtering out non-matches
parsed is lines
  |> map (extract _ log-fmt)
  |> filter _ != nil

; Result:
; [
;   {level: "ERROR", timestamp: "2024-01-15", message: "Disk full"}
;   {level: "INFO",  timestamp: "2024-01-15", message: "Server started"}
;   {level: "WARN",  timestamp: "2024-01-16", message: "Memory low"}
; ]

; Filter to errors only
errors is parsed |> filter _.level = "ERROR"

; Pattern in guards
classify is [line ->
  | #pattern"[ERROR] {_} {msg: rest}"  -> {severity: "critical", msg}
  | #pattern"[WARN] {_} {msg: rest}"   -> {severity: "warning", msg}
  | #pattern"[INFO] {_} {msg: rest}"   -> {severity: "info", msg}
  | _                                   -> {severity: "unknown", msg: line}
]

lines |> map classify
```

## Appendix B: Regex Generation Examples

| Pattern String | Generated Regex |
|---|---|
| `Hello {who}` | `^Hello (?<who>.*?)$` |
| `{y: 4 digit}-{m: 2 digit}-{d: 2 digit}` | `^(?<y>\d{4})-(?<m>\d{2})-(?<d>\d{2})$` |
| `{name}@{domain}` | `^(?<name>.*?)@(?<domain>.*?)$` |
| `{proto: 'http' maybe 's'}://{host: many (not '/')}/{path: rest}` | `^(?<proto>http(?:s)?)://(?<host>[^/]+?)/(?<path>.*)$` |
| `[{level: many letter}] {msg: rest}` | `^\[(?<level>[a-zA-Z]+?)\] (?<msg>.*)$` |
| `{a: 3 any}-{b}` | `^(?<a>.{3})-(?<b>.*?)$` |
| `{x: 'yes' \| 'no'}` | `^(?<x>(?:yes\|no))$` |
| `{{escaped}} {var}` | `^\{escaped\} (?<var>.*?)$` |

---

## References

- [Python `parse` library](https://pypi.org/project/parse/) --- primary inspiration for `{var}` syntax and non-greedy semantics
- [lambdaisland/regal](https://github.com/lambdaisland/regal) --- Clojure regex-as-data (evaluated but not adopted for v1 due to missing named groups)
- [Raku rules](https://en.wikipedia.org/wiki/Raku_rules) --- gold standard for language-integrated pattern matching
- [Elixir binary pattern matching](https://hexdocs.pm/elixir/binaries-strings-and-charlists.html) --- binary-level destructuring
- [F# active patterns](https://learn.microsoft.com/en-us/dotnet/fsharp/language-reference/active-patterns) --- custom pattern matching integration
- [Melody](https://github.com/yoav-lavi/melody) --- English-like regex syntax (from prior regex alternatives research)
- [Pomsky](https://github.com/pomsky-lang/pomsky) --- modern regex language with Java output
- [Java named capture groups](https://docs.oracle.com/javase/tutorial/essential/regex/groups.html) --- compilation target
- DataTwist [regex alternatives research](regex-alternatives-research.md) --- prior art within this project
