# Range Semantics Decision

## The Conflict

Two BDD scenarios in `bdd/8-lazy-eval-data-sources.feature` describe contradictory behaviour
for the same one-argument syntax `range N`:

**Scenario: "range with one argument produces a lazy range from zero" (line 231)**
```
result is range 5 |> force!
```
Expected result: `[0 1 2 3 4]`
Semantics: `range N` = finite, `0` to `N` exclusive.

**Scenario: "range with no upper bound produces an infinite lazy sequence" (line 238)**
```
naturals is range 1
result is naturals |> take 4 |> force!
```
Expected result: `[1 2 3 4]`
Semantics: `range N` = infinite lazy sequence starting at `N`.

Both scenarios use exactly `range <single-integer>`. They cannot coexist. The conflict was
already flagged in the test stub at `test/datatwist/lazy_eval_test.clj` lines 198-209, which
left `range-with-no-upper-bound-produces-an-infinite-lazy-sequence` as a dead stub pending
user resolution.

---

## Current Implementation

`src/datatwist/stdlib.clj` lines 556-559:

```clojure
"range"  (fn
           ([n]          (clojure.core/range n))
           ([start end]  (clojure.core/range start end))
           ([start end step] (clojure.core/range start end step)))
```

The current implementation follows the first BDD scenario (finite, 0..N). It delegates
directly to `clojure.core/range`, which has identical arity semantics: `(range n)` = 0..n,
`(range start end)` = start..end, `(range)` = infinite from 0.

The second BDD scenario (infinite from N) is therefore unimplemented and broken by design —
the current `range 1` call returns `[0]`, not an infinite sequence starting at 1.

---

## Comparison with Other Languages

| Language  | 1-arg form             | Semantics               | Infinite form                       |
|-----------|------------------------|-------------------------|-------------------------------------|
| Python    | `range(5)`             | `0..4` (finite)         | `itertools.count(1)` (separate fn)  |
| Clojure   | `(range 5)`            | `0..4` (finite)         | `(range)` (0-arg), `(iterate inc 1)` |
| Haskell   | `[1..]`                | infinite from 1         | Syntax, not a function              |
| Kotlin    | `1..5`                 | `1` to `5` (inclusive)  | No built-in infinite range          |
| Ruby      | `(0...5).to_a`         | `0..4` (finite)         | `(1..)` (endless range, Ruby 2.6+)  |
| JavaScript| No built-in range      | —                       | Generators                          |

Key observations:
- The finite-from-zero semantics (Python/Clojure) is the dominant convention for a 1-arg `range`.
- Infinite sequences from an arbitrary start are universally handled by a separate mechanism
  (different function, different arity, or syntax).
- No mainstream language uses a single integer argument to mean "infinite from N" when the same
  language also has a 2-arg finite range. The ambiguity would be unresolvable at a glance.
- Clojure's own `(range)` (zero-arg = infinite from 0) is different from `(range n)` (finite).
  DataTwist already follows this arity split for finite ranges; extending it for infinite
  ranges would require either a 0-arg form or a different function name.

---

## Options

### Option A — `range N` = finite 0..N; add `range-from N` for infinite

`range` keeps its current semantics unchanged. A new function `range-from` (or `from`) is
added to produce an infinite lazy sequence starting at `N`.

```
result is range 5 |> force!          ; [0 1 2 3 4]
naturals is range-from 1             ; infinite: 1, 2, 3, ...
first10 is range-from 1 |> take 10 |> force!  ; [1 2 3 4 5 6 7 8 9 10]
```

**Pros:**
- Zero breaking change to `range`. Existing tests and BDD scenario 1 stay intact.
- Explicit naming removes all ambiguity. `range-from 1` cannot be confused with `range 1`.
- Consistent with Python/Clojure precedent for 1-arg range meaning finite.
- Implementation is a one-liner: `(clojure.core/iterate inc n)` or `(clojure.core/range n Long/MAX_VALUE)`.

**Cons:**
- Introduces a new stdlib symbol (`range-from`). Surface area grows by one.
- Users must learn two functions for the "range" concept.

**BDD/test changes required:**
- Delete or rewrite the "range with no upper bound" BDD scenario to use `range-from 1`.
- Add a new BDD scenario for `range-from`.
- Add a new test `range-from-produces-infinite-sequence-from-n`.
- No changes to the existing `range-with-one-argument-produces-a-lazy-range-from-zero` test.

---

### Option B — `range N` = infinite from N; `range 0 N` = finite

Flip 1-arg semantics: `range N` becomes infinite, and finite ranges always require two args.

```
naturals is range 1               ; infinite: 1, 2, 3, ...
result is range 0 5 |> force!    ; [0 1 2 3 4]
```

**Pros:**
- The "range with no upper bound" BDD scenario is satisfied as-is.
- Haskell-flavoured: `range N` reads as "the range beginning at N".

**Cons:**
- Breaks the "range with one argument produces a lazy range from zero" BDD scenario — that
  test would need to change from `range 5` to `range 0 5`.
- Contradicts Python/Clojure convention. Any developer familiar with those languages will
  be surprised by `range 5` producing an infinite sequence.
- `range 5` producing an infinite sequence is a sharp footgun: accidentally materializing
  it with `force!` hangs indefinitely.
- The 2-arg form `range 0 5` is more verbose for a very common operation.
- `range 0 N` is slightly less readable than `range N` for the finite case.

**BDD/test changes required:**
- Rewrite the "range with one argument" scenario: change `range 5 |> force!` to `range 0 5 |> force!`.
- Rewrite the existing test `range-with-one-argument-produces-a-lazy-range-from-zero`.
- Implement the "range with no upper bound" test stub using new semantics.

---

### Option C — `range N` = finite 0..N; `range N ..` or syntax sugar for infinite

Add syntax-level notation for infinite ranges, similar to Haskell or Ruby 2.6.

```
result is range 5 |> force!    ; [0 1 2 3 4]
naturals is range 1..           ; infinite from 1 (new syntax)
```

**Pros:**
- Disambiguates at the syntax level — no function overloading ambiguity.
- Readable: `range 1..` visually communicates "open-ended".

**Cons:**
- Requires a grammar change (new production rule for `..` or `...` as a sentinel).
- Adds complexity to the parser for a rarely-used feature.
- `..` would need disambiguation from potential future range-literal syntax (`1..5`).
- Out of scope for the current BDD scope without a grammar design session.

**BDD/test changes required:**
- Grammar change: add `range N..` production.
- New BDD scenario for the syntax form.
- All existing tests unchanged.
- High implementation cost.

---

### Option D — Keep `range N` = finite 0..N; use `iterate` for infinite from N

Remove the "range with no upper bound" scenario entirely. The existing `iterate` function
already covers the use case.

```
result is range 5 |> force!                            ; [0 1 2 3 4]
naturals is iterate [n -> n + 1] 1                     ; infinite: 1, 2, 3, ...
first4 is iterate [n -> n + 1] 1 |> take 4 |> force!  ; [1 2 3 4]
```

**Pros:**
- No new functions or syntax. `iterate` is already in stdlib (line 584 of stdlib.clj).
- Perfectly consistent with Clojure: `(iterate inc 1)` is the idiomatic infinite natural
  number sequence.
- `iterate` is the more general form (works for any step, not just +1).
- Reduces stdlib surface area by eliminating a redundant concept.

**Cons:**
- `iterate [n -> n + 1] 1` is verbose compared to `range-from 1` for the simple case.
- `iterate` exposes a function argument, which is overkill for "integers from N".
- Users expecting `range-from` or `range 1` for the infinite case may find `iterate`
  unintuitive.

**BDD/test changes required:**
- Delete the "range with no upper bound" BDD scenario entirely.
- Delete the dead stub test `range-with-no-upper-bound-produces-an-infinite-lazy-sequence`.
- No other changes.

---

## Recommendation: Option A

**Add `range-from N` for infinite sequences. Keep `range N` as finite 0..N.**

### Justification

1. **Finite 0..N is the universal 1-arg convention.** Python, Clojure, and every other
   language with a `range` function use 1-arg for finite from zero. DataTwist's current
   stdlib already implements this. Flipping it (Option B) would violate the principle of
   least surprise for anyone who has ever used Python or Clojure.

2. **`range 5` producing an infinite sequence is dangerous.** If `range N` means infinite,
   then `range 5 |> force!` hangs forever. This is an easy mistake to make and a hard one
   to debug. Explicitness matters for footgun-prone operations.

3. **`range-from` is self-documenting.** The name communicates intent in plain English:
   "a range starting from N, going to infinity". There is no ambiguity. Compare: `range 1`
   (ambiguous — finite or infinite?) vs `range-from 1` (unambiguous).

4. **`iterate` (Option D) is already available but too general for a simple case.**
   `iterate [n -> n + 1] 1` requires the user to write a lambda for a conceptually trivial
   operation. `range-from 1` is the right level of abstraction.

5. **`range-from` fits the naming pattern of the language.** DataTwist uses hyphenated,
   descriptive identifiers (`sort-by`, `starts-with?`, `group-by`). `range-from` is
   idiomatic by that convention.

6. **Minimal disruption to existing code.** The only BDD change is rewriting the infinite
   scenario; the existing finite scenario is untouched. The existing test passes unchanged.
   Implementation is trivial.

### Implementation

In `src/datatwist/stdlib.clj`, add after the `range` entry:

```clojure
"range-from"  (fn [start] (clojure.core/iterate inc start))
```

`clojure.core/iterate` with `inc` produces `start, start+1, start+2, ...` lazily and
infinitely. No step parameter is needed for the standard use case; users who need a custom
step can use `iterate` directly.

If a step parameter is desired in future, `range-from` can be extended:

```clojure
"range-from"  (fn ([start]      (clojure.core/iterate inc start))
                ([start step]  (clojure.core/iterate #(+ % step) start)))
```

### Required BDD Changes

1. **Rewrite** "range with no upper bound produces an infinite lazy sequence" (line 238):
   ```gherkin
   Scenario: range-from produces an infinite lazy sequence starting at a given value
     Given the source code:
       """
       naturals is range-from 1
       result is naturals |> take 4 |> force!
       """
     Then naturals is an infinite lazy sequence starting at 1
     And result is [1 2 3 4]
   ```

2. **Keep** "range with one argument produces a lazy range from zero" (line 231) unchanged.

3. **Optionally add** a second `range-from` scenario for a non-1 start:
   ```gherkin
   Scenario: range-from 0 is equivalent to range with no arguments in Clojure
     Given the source code:
       """
       naturals is range-from 0
       result is naturals |> take 5 |> force!
       """
     Then result is [0 1 2 3 4]
   ```

### Required Test Changes

In `test/datatwist/lazy_eval_test.clj`:

1. **Replace** the dead stub `range-with-no-upper-bound-produces-an-infinite-lazy-sequence`
   with a live test for `range-from`:
   ```clojure
   (deftest range-from-produces-an-infinite-lazy-sequence-starting-at-a-given-value
     (let [result (eval-dt-last
                   "naturals is range-from 1"
                   "naturals |> take 4 |> force!")]
       (is (= [1 2 3 4] result)
           "range-from 1 must produce an infinite sequence starting at 1")))
   ```

2. **Keep** `range-with-one-argument-produces-a-lazy-range-from-zero` unchanged.

---

## Decision Log

| Date       | Decision                                                   |
|------------|------------------------------------------------------------|
| 2026-02-20 | Conflict identified; this document written for user review |
| TBD        | User confirms Option A (or selects alternative)            |
| TBD        | BDD updated, stub test replaced, stdlib extended           |
