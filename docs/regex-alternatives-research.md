# Regex Alternatives Research

Research into alternatives to regular expressions for text pattern matching in DataTwist.

## 1. Verbal Expressions

Builder/fluent API that chains method calls to construct regex patterns.

```javascript
// JavaScript (JSVerbalExpressions)
VerEx()
  .startOfLine()
  .then("http")
  .maybe("s")
  .then("://")
  .maybe("www.")
  .anythingBut(" ")
  .endOfLine()
```

Java version (JavaVerbalExpressions) is directly usable from Clojure via interop.

**Pros:** Extremely readable, low learning curve, 37+ language ports, JVM-ready.
**Cons:** Verbose for complex patterns, still regex-level thinking, not a new paradigm.
**JVM fit:** Yes (Java lib). **Analyst-friendly:** Moderate.
**Adoption:** ~12k GitHub stars (JS version).

## 2. Rosie Pattern Language (RPL)

PEG-based pattern language designed as regex replacement.

```
year = [0-9]{4,4}
month = {"1" [0-2]} / {"0"? [1-9]}
day = {"3" [01]} / {[12] [0-9]} / {"0"? [1-9]}
rfc3339_date = { year "-" month "-" day }
```

**Pros:** More powerful than regex (recursive structures), named composable patterns, built-in standard library + unit tests, JSON output, linear-time matching.
**Cons:** Small community, PEG semantics can surprise, written in C (FFI needed for JVM).
**JVM fit:** Weak (C/FFI). **Analyst-friendly:** Moderate.
**Adoption:** Niche. Strange Loop 2018 talk. Active development.

## 3. Melody

Language that compiles to ECMAScript regex with English keywords.

```
capture major {
  some of <digit>;
}
"."
capture minor {
  some of <digit>;
}
```

Key syntax: `some of` (1+), `any of` (0+), `option of` (0/1), `<digit>`, `<word>`, `capture { }`, `either { A | B }`, `let .name = { }`.

**Pros:** Very readable (almost English), low learning curve, variables for DRY, compiles to standard regex.
**Cons:** ECMAScript-only output, written in Rust, less actively maintained since 2022.
**JVM fit:** Weak (Rust/ES). **Analyst-friendly:** Good.
**Adoption:** ~4.6k GitHub stars.

## 4. Pomsky (formerly Rulex)

Modern regex language with variables, Unicode support, multi-flavor compilation.

```
let hex = ['0'-'9' 'a'-'f' 'A'-'F'];
let byte = hex{2};
byte (':' byte){5}   // MAC address
```

Compiles to PCRE, JavaScript, **Java**, .NET, Python, Ruby, Rust, RE2.

**Pros:** Java regex output (JVM-usable), variables + composition, modern design, active development (~1.3k stars), good errors, Unicode-first.
**Cons:** Still regex-level power, written in Rust, programmer-oriented syntax.
**JVM fit:** Moderate (Java output). **Analyst-friendly:** Moderate.

## 5. PEG (Parsing Expression Grammars)

Named composable rules. Janet PEG example:

```janet
(def ip-address
  '{:dig (range "09")
    :byte (choice (sequence "25" (range "05"))
                  (sequence "2" (range "04") :dig)
                  (sequence "1" :dig :dig)
                  (between 1 2 :dig))
    :main (sequence :byte "." :byte "." :byte "." :byte)})
```

**Pros:** Strictly more powerful than regex, no backtracking ambiguity, DataTwist already uses Instaparse (PEG-based).
**Cons:** Higher learning curve, ordered choice semantics surprise users, overkill for simple patterns, too technical for analysts.
**JVM fit:** Excellent (Instaparse). **Analyst-friendly:** Low.
**Adoption:** High in language implementation (Instaparse, LPeg, Tree-sitter).

## 6. Glob-like Patterns

`*` any string, `?` any char, `[abc]` char class, `[a-z]` range.

**Pros:** Zero learning curve, familiar, fast to type.
**Cons:** Very limited — no quantifiers, groups, captures.
**JVM fit:** Trivial. **Analyst-friendly:** Excellent.
**Adoption:** Universal (shells, Redis, SQL LIKE).

## 7. Datalog/Logic-Based

Declarative logic programming for data querying. FC-Datalog (ICDT 2025) extends with string ops.

**Pros:** Natural for structured data, declarative, strong Clojure ecosystem (Datomic, DataScript).
**Cons:** Not designed for text patterns, overly complex for simple matching, academic.
**JVM fit:** Excellent (core.logic). **Analyst-friendly:** Low.

## 8. Natural Language Patterns

```
"starts with 'Hello' then any word then a number"
```

**Pros:** Maximum readability, zero learning curve, self-documenting.
**Cons:** Ambiguity, no established standard, hard to compose, edge cases awkward.
**JVM fit:** Custom impl needed. **Analyst-friendly:** Excellent.

## Bonus: Regal (Clojure-specific)

Regex as Clojure data structures:

```clojure
[:cat
  [:+ [:class [\a \z]]]     ; one or more lowercase letters
  "="
  [:+ [:not \=]]]
;; Compiles to: #"[a-z]+\Q=\E[^=]+"
```

**Pros:** Pure Clojure, data-driven, composable, cross-platform.
**Cons:** Still regex-level power, Clojure-native syntax (not user-facing).

## Comparative Summary

| Approach | Readability | Power | Learning Curve | JVM Fit | Analyst-Friendly |
|---|---|---|---|---|---|
| Verbal Expressions | Good | Regex-level | Low | Yes | Moderate |
| Rosie (RPL) | Very good | Beyond regex | Medium | Weak (C) | Moderate |
| Melody | Excellent | Regex-level | Low | Weak (Rust) | Good |
| Pomsky | Good | Regex-level | Low-Medium | Moderate | Moderate |
| PEG | Good | Beyond regex | Medium-High | Excellent | Low |
| Glob | Excellent | Very limited | Minimal | Trivial | Excellent |
| Datalog | Good | Structured data | High | Excellent | Low |
| Natural Language | Excellent | Variable | Minimal | Custom | Excellent |
| Regal | Good | Regex-level | Low (Clojure) | Perfect | Low |

## Recommendation: Three-Tier Pattern Expressions

### Tier 1: Built-in pattern predicates (80% of use cases)

```
data |> filter (starts-with _.name "A")
data |> filter (ends-with _.email ".com")
data |> filter (contains _.bio "engineer")
data |> filter (matches-any _.phone "###-###-####")
```

Implementation: Clojure string functions + simple glob-to-regex compiler.

### Tier 2: Melody-inspired pattern mini-language

```
email-pattern is pattern [
  capture name { some of <word> }
  "@"
  capture domain { some of <word> }
  "."
  capture tld { 2 to 4 of <letter> }
]

data |> filter (match _.email email-pattern)
data |> map (extract _.email email-pattern)  ; returns {name: ..., domain: ..., tld: ...}
```

Syntax inspired by Melody: `some of`, `any of`, `option of`, `N of`, `N to M of`, `<digit>`, `<letter>`, `<word>`, `capture name { }`, `either { A | B }`, `not`.

### Tier 3: Raw regex escape hatch

```
data |> filter (regex-match _.phone #"^\+?[0-9]{1,3}[-.\s]?")
```

### Implementation Strategy

- **Tier 1:** Trivial stdlib functions wrapping `clojure.string/*`.
- **Tier 2:** New Instaparse grammar for pattern expressions → compile to Regal data → compile to `java.util.regex.Pattern`.
- **Tier 3:** Direct Java regex via Clojure `#"..."` literals.
- **PEG powers compilation** (consistent with DataTwist's architecture).
- **Regal as backend** for programmatic regex construction.

### What to avoid

- Don't build a PEG engine for users (too technical).
- Don't parse arbitrary English (ambiguity).
- Don't depend on external Rust/C tools (deployment complexity).
- Don't reinvent Datalog for text (overkill).
