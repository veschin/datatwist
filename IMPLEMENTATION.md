# DataTwist Evaluator — Implementation Checklist

## Phase 1: Literals + Operators + Simple Binding

### Setup
- [ ] Create `src/datatwist/env.clj` — string-keyed map, `lookup`, `bind`, `bind-many`
- [ ] Create `src/datatwist/stdlib.clj` — `default-env` (пока пустой)
- [ ] Create `src/datatwist/evaluator.clj` — `evaluate [input]`, `eval-node [node env]`
- [ ] Wire `parser.clj:eval-dt` → `evaluator/evaluate`

### Core dispatch
- [ ] `Program` — eval exprs sequentially, thread env, return last value
- [ ] Transparent wrappers — `Expr`, `PipeExpr`, `OrExpr`, `AndExpr`, `NotExpr`, `NilCoalesce`, `CompExpr`, `InExpr`, `AddExpr`, `MulExpr`, `UnaryExpr`, `FnCallExpr`, `FieldAccess`, `Atom`, `PipeAtom` — 1 child = recurse
- [ ] `ParenExpr` — eval inner expression

### Literals
- [ ] `Integer` — parse to Long, auto-promote to BigInt on overflow
- [ ] `Float` — parse to Double
- [ ] `String` — unescape `\\n`, `\\t`, `\\\"`, `\\\\`
- [ ] `Boolean` — `"true"` → true, `"false"` → false
- [ ] `Nil` — → nil
- [ ] `Keyword` — `":status"` → `:status`

### Variables + Binding
- [ ] `Identifier` — env lookup, nil if undefined
- [ ] `Binding` (simple) — `[:Binding [:Identifier "x"] value-expr]` → bind in env

### Arithmetic (nil-tolerant)
- [ ] `AddExpr` — left-assoc `+`/`-`, nil → identity, string concat, auto-promote `+'`
- [ ] `MulExpr` — left-assoc `*`/`/`/`%`, nil → identity, division → Double
- [ ] `UnaryExpr` — negation `-x`

### Comparison
- [ ] `CompExpr` + `CompOp` — `=` `!=` `>` `<` `>=` `<=`
- [ ] Numeric `=` uses `==` (5 = 5.0 → true)
- [ ] String comparison via `compare`
- [ ] nil comparisons: `nil > x` → false

### Logical (short-circuit)
- [ ] `AndExpr` — return first falsy or last value
- [ ] `OrExpr` — return first truthy or last value
- [ ] `NotExpr` — `"not"` prefix → boolean negation

### Other operators
- [ ] `InExpr` — `contains?` for maps, `some` + equality for vectors
- [ ] `NilCoalesce` — left-to-right, first non-nil

### Verify
- [ ] `literals_test.clj` passes (~17 deftests, ~100 assertions)

---

## Phase 2: Data Structures + Field Access

### Data structures
- [ ] `Object` (empty) — `[:Object]` → `{}`
- [ ] `Object` (standard entries) — `[:StandardEntry [:Identifier "k"] value]` → `{:k value}`
- [ ] `Object` (nested) — objects as values
- [ ] `List` (empty) — `[:List]` → `[]`
- [ ] `List` (with elements) — eval `FieldAccess` children → vector

### Field access
- [ ] `FieldAccess` — `[:FieldAccess atom [:FieldName "name"] ...]` → nil-tolerant keyword-get chain
- [ ] `_.field` — wildcard + field = `(get _ :field)` then chain

### Stdlib (collection)
- [ ] `count`, `first`, `last`, `nth`, `rest`
- [ ] `keys`, `values`, `get`, `contains?`, `empty?`
- [ ] `merge`, `assoc`, `dissoc`, `conj`, `concat`, `into`
- [ ] `select-keys`, `update`
- [ ] `append`, `prepend`, `length`/`size` (aliases for conj/count)
- [ ] `type-of` — return type string

### Verify
- [ ] `data_structures_test.clj` passes (~40 deftests, non-pipeline)

---

## Phase 3: Functions + Closures

### Function definition
- [ ] `FnDef` with params — `[:FnDef [:FnParams ...] [:FnBody ...]]` → Clojure fn capturing env
- [ ] `FnDef` zero params — `[:FnDef [:FnBody ...]]` → `(fn [] ...)`
- [ ] `FnBody` — eval exprs sequentially, return last (like Program)
- [ ] `FnParams`/`FnParam` — positional binding, `RestParam` for `& rest`
- [ ] Closures — capture lexical env at definition time

### Function calls
- [ ] `FnCall` — resolve `CallTarget`, eval `CallArg`s, apply
- [ ] `CallTarget` — Identifier + optional FieldName chain
- [ ] Zero-param call — `f()` → `[:FnCall [:CallTarget [:Identifier "f"]]]` (no CallArg children)
- [ ] `MultiArityFn` — dispatch on arg count

### Side-effects
- [ ] `!` suffix auto-wrapping — on binding, wrap fn to execute + return first arg

### Stdlib (math + utility)
- [ ] `format`, `partial`, `identity`, `comp`, `apply`, `not=`
- [ ] `print`, `println`
- [ ] `abs`, `max`, `min`, `round`, `ceil`, `floor`, `sqrt`, `pow`
- [ ] `even?`, `odd?`, `inc`, `dec`
- [ ] `sum`, `avg`, `clamp`
- [ ] `to-string`, `to-int`, `to-float`
- [ ] `range`

### Verify
- [ ] `functions_test.clj` passes (~25 deftests)
- [ ] `binding_test.clj` simple binding tests pass

---

## Phase 4: Pipelines

### Pipeline evaluation
- [ ] `Pipeline` — eval first PipeAtom, thread through remaining
- [ ] Pipe-first semantics — `data |> f args` = `f(data, args)`
- [ ] `_` detection — walk PipeAtom for `:Wildcard`, build implicit lambda
- [ ] Lambda extraction — FnCall body replacement: `filter _.age > 18` → `(fn [_] (> (get _ :age) 18))`
- [ ] Bare identifier step — `|> count` → call with piped data
- [ ] `SourcelessPipeline` — `|> f |> g` → returns composed function
- [ ] `_` scoping — nested pipelines shadow outer `_`

### Smart objects (pipeline context)
- [ ] `AddField` — `{+score: expr}` → assoc onto `_`
- [ ] `RemoveField` — `{-field}` → dissoc from `_`
- [ ] Forward-referencing — `{+tax: _.price * 0.1 +total: _.price + tax}`
- [ ] `ShorthandContent`/`ShorthandEntry` — `{name, age}` → lookup from env

### Stdlib (collections HOF)
- [ ] `filter`, `map`, `reduce`
- [ ] `sort`, `sort-by`, `group-by`
- [ ] `take`, `drop`, `flatten`, `distinct`, `reverse`
- [ ] `each`, `zip`, `partition`, `frequencies`

### Stdlib (strings)
- [ ] `replace`, `upper-case`, `lower-case`, `trim`
- [ ] `split`, `join`
- [ ] `starts-with?`, `ends-with?`, `includes?`, `substring`

### Stdlib (side-effects)
- [ ] `log!`, `tap!`

### Verify
- [ ] `pipeline_test.clj` passes (~35 deftests)
- [ ] Pipeline-dependent tests in other files pass

---

## Phase 5: Destructuring

### Object destructuring
- [ ] `DestructObjPattern` — bare `{name age}` → bind from map keys
- [ ] Rename — `{name: n}` → bind `n` from `:name`
- [ ] Default — `{name ? "anon"}` → default if key missing
- [ ] Nested — `{address: {city}}` → recursive destructure
- [ ] `as` — `{name age} as u` → also bind whole value

### List destructuring
- [ ] `DestructListPattern` — positional `[a b c]` → bind by index
- [ ] `Wildcard` skip — `[_ _ third]` → skip positions
- [ ] `RestBinding` — `[first & rest]` → rest of list
- [ ] `as` — `[head & tail] as all`

### Combined
- [ ] Object + list — `{name scores: [best & rest]}`
- [ ] In function params — `[{age: a1} {age: a2} -> a1 + a2]`

### Verify
- [ ] `binding_test.clj` passes fully (~30 deftests)

---

## Phase 6: Pattern Matching + Guards

### Guard evaluation
- [ ] `GuardBlock` — iterate GuardArms, first match wins, nil if none
- [ ] `GuardArm` — 2 children `[pattern, result]` or 3 `[pattern, when, result]`

### Pattern types
- [ ] Boolean guard — eval expr, truthy = match
- [ ] Wildcard `_` — always matches
- [ ] Literal pattern — equality check (Integer, String, Boolean, Nil)
- [ ] Structural object — check keys present, bind variables
- [ ] Structural list — check length/shape, bind variables
- [ ] `when` clause — additional condition after structural match

### Context
- [ ] Guards in function bodies — `[x -> | x > 0 -> "pos" | _ -> "neg"]`
- [ ] Guards in object fields — `tier: | _.spending > 1000 -> "gold" | _ -> "bronze"`
- [ ] Guards in pipeline context — pass match-value as `_`
- [ ] Guards bound to variable — `tier is | amount > 1000 -> "gold" | ...`

### Verify
- [ ] `pattern_matching_test.clj` passes (~25 deftests)

---

## Phase 7: Advanced Features

### Recur
- [ ] `RecurSignal` record — sentinel for tail-call
- [ ] `Recur` node — eval args, return RecurSignal
- [ ] FnBody loop — detect RecurSignal, rebind params, repeat

### Composition
- [ ] `Compose` — `>>` left-to-right, `<<` right-to-left

### Try-Catch
- [ ] `TryCatch` — eval body in try, dispatch to CatchClauses
- [ ] `CatchClause` — `CatchTarget` variants: typed (`DotName Identifier`), generic, wildcard
- [ ] `FinallyClause` — always execute

### Interop
- [ ] `Require` — `require clojure.string as str` → add alias to env
- [ ] `QualifiedName` — `clojure.string/upper-case` → resolve var
- [ ] `InstanceMethod` — `.method obj` → `(.method obj)`
- [ ] `Constructor` — `ClassName. args` → `(ClassName. args)`

### Verify
- [ ] `make test` → 407 tests, 992 assertions, 0 failures, 0 errors
