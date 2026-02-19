# Error Reporting Design for DataTwist

Research into best-in-class compiler/interpreter error messages, with concrete recommendations
for DataTwist's error reporting system.

---

## 1. Survey of Best Implementations

### Elm — The Gold Standard

Elm's error philosophy, articulated by Evan Czaplicki: the compiler should talk to the developer
as a collaborator, not dump internal state at them.

**Structural pattern:**
```
-- TYPE MISMATCH --------------------------------- src/Main.elm

The 1st argument to `showAge` is not what I expect:

9|   Html.text (showAge "invalid argument")
                        ^^^^^^^^^^^^^^^^^
This argument is a string of type:

    String

But `showAge` needs the 1st argument to be:

    Int

Hint: I always figure out argument types left to right. If an argument is
acceptable I assume it is correct and move on. So the problem may actually be
elsewhere in the call chain.
```

Key characteristics:
- Header: `-- ERROR NAME ------- filepath` (dashes fill the terminal width, cyan)
- First-person voice: "I expect", "I always", "I see"
- Plain English, no jargon ("argument", not "token" or "identifier")
- Source snippet with `^` carets on the offending span
- Types displayed clearly with blank lines for breathing room
- Hint section (lowercase `Hint:`) with forward-looking guidance
- No error codes — Elm trades codes for prose quality

### Rust (rustc) — Industrial-Strength Diagnostics

Rust's diagnostic system is the most complete in production use. It separates:
- **Primary spans** (`^^^` underline, error-colored) — what triggered the problem
- **Secondary spans** (`---` underline, blue) — related locations (prior definition, etc.)
- **Notes** (`= note:`) — factual context (e.g., "trait bound not satisfied")
- **Helps** (`= help:`) — actionable suggestions
- **Machine-applicable suggestions** — IDE-auto-fixable rewrites

**Structural pattern:**
```
error[E0499]: cannot borrow `foo` as mutable more than once at a time
  --> src/main.rs:29:22
   |
28 |     let bar1 = &mut foo;
   |                     --- first mutable borrow occurs here
29 |     let bar2 = &mut foo;
   |                     ^^^ second mutable borrow occurs here
30 |     *bar1;
   |     ----- first borrow later used here
   |
   = help: try using a different variable name
```

Key characteristics:
- `error[E0499]:` — level, code, message on one line
- `-->` separator to filepath:line:col
- Line-numbered gutter with `|` wall character
- Multiple annotated spans on the same snippet
- Distinct visual treatment: `^^^` for primary, `---` for secondary
- Structured `= note:` / `= help:` appendices below the snippet
- `rustc --explain E0499` fetches extended prose explanation
- Applicability levels on suggestions (machine-applicable = auto-fixable by IDE)

### Zig — Minimal and Direct

Zig compiles to a very terse format: `file:line:col: level: message`. It includes
source line + caret and a `note:` for call chains. The philosophy is extreme minimalism.

**Structural pattern:**
```
main.zig:4:14: error: expected type 'u32', found 'f32'
    const x: u32 = 3.14;
             ^~~~
note: called from here
```

Key characteristics:
- No color for the message text — just the bare location prefix
- Single source line + `^~~~` underline (caret + tilde wave for span)
- `note:` lines for call-chain context
- Very short — one or two lines of prose, maximum
- Weakness: sometimes too terse (no file line in some format-string errors)

### Gleam — Modern BEAM Ergonomics

Gleam takes Elm's friendliness and maps it onto Rust's span structure. It uses
Rust's `codespan-reporting` crate, so the visual output is Rust-like but the
prose is Elm-like.

Key characteristics:
- Primary label on the offending span, secondary label for context
- "Did you mean `name`?" with edit-distance gating (threshold: distance ≤ 30% of string length)
- Friendly prose: "I found a variable starting with `_` which suggests you may have wanted..."
- Reports multiple errors per file in one pass (fault-tolerant parser)
- Warning format identical to error format but with `warning:` level and yellow color
- Suggests wrapping in `Ok`/`Error` for type-mismatch in Result contexts

### Roc — Elm Successor with Refined Semantics

Roc (the Elm successor by Richard Feldman) refines Elm's format:

**Structural pattern:**
```
── TYPE MISMATCH ─────── /home/my-roc-project/main.roc ─

This expression is used in an unexpected way:

7│      result = calculate tax
                          ^^^
The `tax` value is a Dec (fraction), but `calculate` expects an I64 (integer).

Tip: You can convert between integers and fractions using functions like
`Num.toFrac` and `Num.round`.
```

Key characteristics:
- Header uses `──` (Unicode box-drawing) as a visual separator, cyan
- Filepath embedded in the header rule (after the title)
- Blank line before snippet, line numbers with `│` gutter character
- `^^^` on the offending span
- Prose describes the problem in domain terms (e.g., "fraction", "integer"), not type-system terms
- `Tip:` section (not `Hint:`) — very short, 1–3 lines
- No error codes — relies on prose quality, like Elm

---

## 2. Common Patterns Across All Best Implementations

After surveying all five, the following principles appear in every implementation that
developers praise:

1. **One-line header that names the error** — the developer must immediately understand
   the category ("TYPE MISMATCH", "UNDEFINED VARIABLE") before reading the detail.

2. **Source snippet with line numbers and a gutter separator** — always show the actual
   code. Never make the developer look it up.

3. **Precise span underline** — a `^` or `~` under the specific token(s), not the whole
   line. Length of the underline matches the token length.

4. **Expected vs. actual** — for type errors, always show both sides. "Expected X, got Y."

5. **Plain English prose, no internal terminology** — "identifier", "token", "AST node",
   "ClassCastException" never appear.

6. **Suggestions gated by confidence** — "did you mean" only when edit distance is low
   (≤ 2 edits for short names). Otherwise suppress.

7. **Errors and warnings are visually distinct** — different colors, different prefixes.
   Warnings never say "error".

8. **No raw host-language exceptions** — Clojure/Java stack traces would be the cardinal
   sin for DataTwist, as they expose the implementation to the user.

9. **Tip/hint is actionable, not descriptive** — "Try `x is 42` instead of `x = 42`" is
   a tip. "Assignment uses `is`" is documentation. Tips must show correct code.

---

## 3. Recommendations for DataTwist

### 3.1 Error Message Template

Every DataTwist error follows this exact structure (5 sections):

```
── <CATEGORY> [<DT-CODE>] ──────────────────────── <filepath>:<line> ─

<one-sentence description, first person>

<line-N>│  <source line here>
        │  <^^^^^  span underline + label>

<expected vs. actual block, if applicable>

Hint: <one actionable sentence showing correct code>
```

Rules:
- Header line fills terminal width (default 80 cols). Use `─` (U+2500).
- Category: ALL CAPS, 2–4 words max.
- `[DT-CODE]` in the header, not a separate line.
- Filepath is the script name/`:repl` for interactive mode.
- Prose block: 1–3 sentences, first-person ("I see...", "I found...").
- Snippet: always shown, even for runtime errors (show the expression being evaluated).
- Hint: prefixed `Hint:` exactly. If no good hint, omit entirely rather than write a bad one.
- Warnings use the same format but with `── WARNING` prefix and yellow color.

### 3.2 Error Code Scheme

Codes follow `DT-XNNN` format as defined in the BDD spec (feature 9):

| Prefix | Category        | Range     | Examples |
|--------|-----------------|-----------|---------|
| DT-P   | Parse errors    | P001–P099 | DT-P001: unexpected token, DT-P010: unclosed string |
| DT-T   | Type errors     | T001–T099 | DT-T001: type mismatch in arithmetic, DT-T010: division by zero |
| DT-R   | Runtime errors  | R001–R099 | DT-R001: undefined identifier, DT-R010: wrong arity |
| DT-D   | Data warnings   | D001–D099 | DT-D001: nil values in pipeline, DT-D010: nil sort key |
| DT-C   | Connection err  | C001–C099 | DT-C001: file not found, DT-C010: DB connection failed |

Gaps are intentional — leave room for additions within each category.
Specific assignments should be decided during implementation and locked in a
`docs/error-codes.md` registry.

### 3.3 Source Snippet Display

**Format:**
```
<line-N>│  <source text>
        │  <underline>  <optional inline label>
```

Rules:
- Line number is right-aligned to a fixed width based on the max line number in the file.
- Gutter: `│` (U+2502), one space on each side.
- Underline uses `^` for the primary error span, `~` for secondary spans.
- Underline length = token/span length. Minimum 1 `^`.
- For multi-line expressions, show the first and last lines with `...` elision between.
- Maximum context: 3 lines above and 3 lines below the error line.
- The gutter row with underline has spaces in the line-number column (not a number).

**Multi-line error:**
```
 3│  tier is
 4│    | amount > 1000 "gold"
  │               ^^^^^^  missing '->' after guard condition
 5│    | _ -> "bronze"
```

### 3.4 Suggestion / Hint System

**"Did you mean" (for undefined identifiers):**

Use Levenshtein distance to find the nearest name in the current environment.
Only emit the suggestion if `distance(query, candidate) ≤ max(2, floor(len(query) / 3))`.
Never suggest more than one candidate. If multiple candidates tie, pick the one defined
most recently in scope.

```clj
(defn best-suggestion [name env-names]
  (let [threshold (max 2 (Math/floor (/ (count name) 3)))
        candidates (->> env-names
                        (map (fn [n] [n (levenshtein name n)]))
                        (filter (fn [[_ d]] (<= d threshold)))
                        (sort-by second))]
    (ffirst candidates)))  ;; nil if no candidates within threshold
```

**Common-mistake hints (parser level):**

These are hard-coded pattern detectors run after a parse failure to produce
higher-quality hints than the grammar error alone:

| Pattern detected                   | DT-P Code | Hint shown |
|------------------------------------|-----------|------------|
| `x = expr` (standalone)            | DT-P020   | `Hint: Use 'is' for assignment: x is expr` |
| `x := expr`                        | DT-P021   | `Hint: Use 'is' for assignment: x is expr` |
| `[params => body]`                 | DT-P022   | `Hint: Use '->' not '=>': [params -> body]` |
| `expr &&  expr`                    | DT-P023   | `Hint: Use 'and' instead of '&&'` |
| `!expr` (prefix bang)              | DT-P024   | `Hint: Use 'not' instead of '!'` |
| `[x, y, z]` (comma in list)        | DT-P025   | `Hint: DataTwist uses spaces: [x y z]` |
| `{k: v, k: v}` (comma in object)  | DT-P026   | `Hint: DataTwist uses spaces: {k: v k: v}` |
| Missing `->` in lambda             | DT-P030   | `Hint: Correct syntax: [x -> x * 2]` |
| Missing `->` in guard branch       | DT-P031   | `Hint: Correct syntax: | amount > 1000 -> "gold"` |

**Data-aware hints (DT-D warnings):**

For nil-prevalence warnings, always quantify and offer two remedies:
```
Hint: 23 of 150 rows had nil at _.address.city
      Filter first:    users |> filter _.address != nil |> map _.address.city
      Coalesce nils:   users |> map (_.address.city ?? "unknown")
```

### 3.5 Color Scheme (ANSI Codes)

DataTwist uses 8 standard ANSI colors only (works in all terminals, no 256-color required):

| Element                      | ANSI code    | Appearance |
|------------------------------|--------------|-----------|
| Error header rule `──`       | `\033[36m`   | Cyan |
| Error category/code          | `\033[1;31m` | Bold red |
| Warning header rule `──`     | `\033[33m`   | Yellow |
| Warning category/code        | `\033[1;33m` | Bold yellow |
| Source line numbers          | `\033[90m`   | Dark gray |
| Gutter `│`                   | `\033[36m`   | Cyan |
| Primary underline `^^^`      | `\033[1;31m` | Bold red (error) or bold yellow (warning) |
| Secondary underline `~~~`    | `\033[34m`   | Blue |
| `Hint:` label                | `\033[32m`   | Green |
| Hint text                    | `\033[0m`    | Default |
| Inline code in prose `` ` `` | `\033[1m`    | Bold |
| Reset                        | `\033[0m`    | — |

Color is suppressed when `System.console()` is nil (piped output) or when the
`DT_NO_COLOR` / `NO_COLOR` environment variable is set.

---

## 4. Mock-ups: Common DataTwist Errors

### Mock-up 1: Parse Error — Wrong Assignment Operator (DT-P020)

```
── PARSE ERROR [DT-P020] ────────────────────────── script.dt:1 ─

I don't know how to parse this expression. DataTwist uses `is` for
assignment, not `=`.

 1│  x = 42
  │  ^^^^^^

Hint: Try this instead: x is 42
```

### Mock-up 2: Runtime Error — Undefined Identifier with Suggestion (DT-R001)

```
── UNDEFINED IDENTIFIER [DT-R001] ───────────────── script.dt:2 ─

I found `user-name` but it isn't defined in this scope.

 1│  username is "Alice"
 2│  result is user-name
  │            ^^^^^^^^^

Hint: Did you mean `username`?
```

### Mock-up 3: Type Error — Arithmetic on Incompatible Types (DT-T001)

```
── TYPE MISMATCH [DT-T001] ──────────────────────── script.dt:1 ─

I can't add a string and a number. The `+` operator requires both
sides to be the same type.

 1│  result is "hello" + 5
  │            ~~~~~~~^~~~
  │            │       └ number (Long)
  │            └ string (String)

Expected: both operands to be numbers, or both to be strings
     Got: String + Long

Hint: To join strings, use the `format` function: format "%s%s" "hello" 5
```

### Mock-up 4: Data Warning — Nil Values in Pipeline (DT-D001)

```
── NIL VALUES IN PIPELINE [DT-D001] ──────────────── script.dt:1 ─

I detected nil values while evaluating `_.address.city`. Execution
continues, but some rows produced nil results.

 1│  result is users |> map _.address.city
  │                         ~~~~~~~~~~~~~~

23 of 150 rows had nil at `_.address.city`.

Hint: Filter nils first:  users |> filter _.address != nil |> map _.address.city
      Or coalesce to default: users |> map (_.address.city ?? "unknown")
```

### Mock-up 5: Parse Error — Missing Arrow in Guard Branch (DT-P031)

```
── PARSE ERROR [DT-P031] ────────────────────────── script.dt:2 ─

I see a guard condition but no `->` separating it from the result.

 2│    | amount > 1000 "gold"
  │      ^^^^^^^^^^^^^^  missing '->'

Hint: Correct syntax:
        | amount > 1000 -> "gold"
```

---

## 5. Implementation Architecture

### Clojure Implementation Notes

DataTwist is an interpreter on the JVM. Errors are thrown as `ex-info` maps,
not Java exceptions. The key is to:

1. **Catch all Java/Clojure exceptions at the top-level eval boundary** and translate
   them into DataTwist error maps before any output.

2. **Thread source location through the evaluator** using a dynamic var or metadata
   on AST nodes (Instaparse attaches `{:instaparse.gll/start-index, :end-index}` to
   every node — use these to compute line/col).

3. **Error map structure:**
   ```clj
   {:dt/error true
    :code      "DT-R001"
    :category  "UNDEFINED IDENTIFIER"
    :message   "I found `user-name` but it isn't defined in this scope."
    :source    "result is user-name"
    :line      2
    :col-start 10
    :col-end   19
    :hint      "Did you mean `username`?"}
   ```

4. **Rendering is separate from throwing.** The evaluator throws the map; the
   REPL/runner catches and calls a `render-error` function that applies ANSI
   formatting. This keeps the evaluator testable without terminal concerns.

5. **Java exception translation table** (catch at top-level boundary):

   | Java Exception            | Maps to    | Message |
   |---------------------------|------------|---------|
   | `ArithmeticException`     | DT-T010    | "Division by zero" |
   | `ClassCastException`      | DT-T001    | "Type mismatch: ..." |
   | `NullPointerException`    | DT-R020    | "Nil value where a value was required" |
   | `FileNotFoundException`   | DT-C001    | "File not found: ..." |
   | `java.sql.SQLException`   | DT-C010    | "Database error: ..." |

6. **Instaparse failure rendering:** When the grammar rejects input, Instaparse
   returns a failure object. Run the common-mistake detector (pattern table in
   §3.4) first; if it matches, emit the specific hint. If not, fall back to the
   Instaparse failure location with a generic DT-P001 message.

### Testing Contract

The BDD scenarios in `bdd/9-error-reporting.feature` specify the observable contract.
The test helpers in `test_helpers.clj` should expose:

```clj
(error-code source)    ;; -> "DT-P001" or nil
(error-output source)  ;; -> rendered string (no ANSI in tests)
(no-java-trace? source) ;; -> true if no "at java." in error-output
```

Colors are stripped in test mode by binding `*use-color* false`.

---

## Sources Consulted

- [Writing Good Compiler Error Messages — Caleb Mer](https://calebmer.com/2019/07/01/writing-good-compiler-error-messages.html)
- [Elm Error Message Style — Elm Discourse](https://discourse.elm-lang.org/t/error-messages-style/7828)
- [Understanding Elm's Type Mismatch Error — Thoughtbot](https://thoughtbot.com/blog/understanding-elms-type-mismatch-error)
- [Rust Compiler Diagnostics Guide](https://rustc-dev-guide.rust-lang.org/diagnostics.html)
- [RFC 1644 — Default and Expanded Rustc Errors](https://rust-lang.github.io/rfcs/1644-default-and-expanded-rustc-errors.html)
- [Roc Friendly Errors](https://www.roc-lang.org/friendly)
- [Gleam Compiler Issues — Did You Mean Threshold](https://github.com/gleam-lang/gleam/issues/2682)
- [Zig Compiler Messages — Heig Zig Docs](https://pismice.github.io/HEIG_ZIG/docs/compiler-messages/)
- [Elm Amazing Informative Paternalistic Error Messages — Jamalambda](https://jamalambda.com/posts/2021-06-13-elm-errors.html)
