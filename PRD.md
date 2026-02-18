# DataTwist Language PRD

## Vision

DataTwist is a functional data processing language built on Clojure/JVM.
The power of Clojure without the parentheses, purpose-built for data pipelines.

- **Runtime**: Clojure/JVM. DataTwist source -> AST -> compiled/interpreted via Clojure
- **Interop**: Full access to Clojure ecosystem (next.jdbc, honey sql, etc.)
- **REPL**: nREPL with IDE integration (CIDER, Calva)
- **Parser**: Instaparse (EBNF grammar)
- **Target audience**: Developers who find SQL too limited and Clojure too scary

## Design Decisions (locked)

| Decision | Choice | Rationale |
|---|---|---|
| Assignment | `is` | Frees `=` for comparison |
| Equality | `=` | SQL-style, familiar to target audience |
| Pipe operator | `\|>` | Elixir/F# standard, eliminates indent-parsing |
| Functions | `[params -> body]` | Square brackets only, uniform syntax |
| Object keys | `name: value` (postfix colon) | More readable than `:name value` |
| Default case | `_` | Not `otherwise` |
| Wildcard `_` | Context-overloaded | Pipeline current element / pattern default / destruct skip |
| Side effects `!` | Passthrough (doto) | `log! data "msg"` returns data. `!` = side-effect only |
| Materialization | `force!` | Materializes lazy pipeline. `count`, `collect` are regular functions |
| Strings | Plain + `format` | No interpolation |
| Errors | try-catch | Classical approach |
| Destructuring | Clojure parity | `&` for rest, `?` for defaults, `as` for whole binding |
| Pattern matching | Context-based | `\| {pattern}` = structural, `\| expr` = guard |
| Pipe semantics | Pipe-first (Elixir) | `data \|> f` = `f(data, ...)` |
| Nil tolerance | Yes | Missing field access = nil, not error |
| Comments | `//` | Universal, no conflict with language constructs |
| Nil coalescing | `??` | `value ?? default` -- triggers on nil only |
| Nil in arithmetic | Coercion to identity | `nil + 5` = `5`, `nil + ""` = `""` |
| Object field ops | `+`/`-` prefixes | `{+field: expr}` adds, `{-field}` removes |
| Object shorthand | `{name, age}` | = `{name: _.name age: _.age}` in pipeline |
| Modules | Deferred | Core language first |

## Feature Areas

### 1. Literals, Types & Operators

```
42              // integer (Long)
3.14            // float (Double)
-10             // negative
"hello world"   // string
true false      // boolean
nil             // nil/null
```

**Arithmetic:** `+`, `-`, `*`, `/`, `%`
**Comparison:** `=`, `!=`, `>`, `<`, `>=`, `<=`
**Logical:** `and`, `or`, `not`
**Membership:** `in` -- `"premium" in _.tags`
**Nil coalescing:** `??` -- `value ?? default`

Operator precedence (high to low): parens > unary `-` > `* / %` > `+ -` > `in` > comparisons > `not` > `??` > `and` > `or` > `|>` > `is`

Whitespace required around operators to disambiguate `user-name` (identifier) from `user - name` (subtraction).

### 2. Data Structures

**Objects (Maps):**
```
user is {name: "Alice" age: 25}
nested is {profile: {address: {city: "Moscow"}}}
empty is {}
```

**Lists (Vectors):**
```
numbers is [1 2 3 4 5]
mixed is ["Alice" 25 true]
nested is [[1 2] [3 4]]
empty is []
```

**Field access (dot notation, nil-tolerant):**
```
user.name                     // "Alice"
user.profile.address.city     // "Moscow"
user.nonexistent              // nil
user.nonexistent.deep.chain   // nil (not error)
```

**Dynamic access:** `get user key-name`

**Object field operations (in pipeline `map`):**
```
// Add/update fields (keeps existing)
users |> map {+score: _.points * 2}
users |> map {+tax: _.price * 0.1  +total: _.price + tax}  // forward-referencing

// Remove fields
users |> map {-internal-id  -tmp}

// Shorthand (in pipeline context)
users |> map {name, age, city: _.address.city}
// equivalent to: {name: _.name  age: _.age  city: _.address.city}

// Plain object = new structure (no + prefix)
users |> map {name: _.name  age: _.age}  // only these fields
```

Under the hood: `+` compiles to `assoc`, `-` compiles to `dissoc`. No commas. Space/newline separated. Objects = Clojure maps with keyword keys, lists = Clojure vectors.

### 3. Functions & Closures

```
double is [x -> x * 2]
add is [a b -> a + b]
greet is [name -> format "Hello, %s!" name]
```

**Predicates (`?`):** `even? is [n -> n % 2 = 0]`
**Side effects (`!`):** passthrough -- execute effect, return first argument
**Variadic:** `[a b & rest -> ...]`
**Zero-param:** `[-> 42]`
**Closures:** lexical scope, capture outer variables

```
data
|> log! "processing started"
|> transform
|> save! "output.json"
|> log! "done"
```

### 4. Pipeline (`|>`)

The core abstraction. Not syntax sugar -- a first-class runtime object.

**Pipe-first semantics:** `data |> f args` = `f(data, args)`

```
// Multi-line
users
|> filter _.age > 18
|> map {name: _.name age: _.age}
|> sort-by _.age
|> take 10

// Inline
users |> filter _.active |> count

// Nested
users
|> map {
  name: _.name
  top-scores: _.scores |> filter [s -> s > 80] |> take 3
}
```

**`_` as current element:**
```
users |> filter _.age > 18      // _.age = field access
users |> filter _ != nil         // _ = whole element
numbers |> filter [n -> n > 5]   // explicit function (same thing)
```

Each `|>` creates a new scope for `_`. Inner pipes shadow outer `_`.

### 5. Binding & Destructuring (`is`)

```
// Simple
x is 42
result is users |> filter _.active |> count

// Object destructuring
{name age} is user
{name: n  age: a} is user            // rename
{name ? "anon"  age ? 0} is user     // defaults (missing key only)
{address: {city country}} is user     // nested
{name age} as u is user               // whole binding

// List destructuring
[a b c] is [1 2 3]
[first & rest] is [1 2 3 4 5]        // & for rest
[_ _ third] is [1 2 3]                // skip with _
[head & tail] as all is items          // & + as

// Combined
{name  scores: [best & rest]} is player

// In function params
add-ages is [{age: a1} {age: a2} -> a1 + a2]
```

Lexical scope. Sequential `is` bindings (no separate `let` needed). Shadowing allowed.

### 6. Pattern Matching & Guards

**Guards (conditions):**
```
tier is
  | amount > 1000 -> "gold"
  | amount > 100  -> "silver"
  | _             -> "bronze"
```

**Structural matching (data shape):**
```
classify is [data ->
  | {type: "book"  pages: p} when p > 500 -> "epic"
  | {type: "book"}                         -> "book"
  | {type: "movie" rating: r} when r > 8  -> "great film"
  | [x]                                    -> "single"
  | [x & rest]                             -> "collection"
  | nil                                    -> "nothing"
  | _                                      -> "unknown"
]
```

**In object fields (pipeline context):**
```
users |> map {
  name: _.name
  tier:
    | _.spending > 1000 -> "gold"
    | _                 -> "bronze"
}
```

Context disambiguation: `| {...}` or `| [...]` = structural, `| expression` = guard.
`when` adds a guard condition after structural pattern. First match wins.

### 7. Clojure Interop

**Direct qualified calls:**
```
clojure.string/upper-case "hello"
```

**Require with alias:**
```
require clojure.string as str
str/upper-case "hello"
```

**Java interop:**
```
.method object          // instance method
Class/staticMethod args // static method
ClassName. args         // constructor
```

**Keywords:** `:keyword` syntax supported for interop. Object keys are keywords under the hood.

**Try-catch:**
```
data is try
  read-csv "data.csv"
catch err -> []

// Typed catch
try
  risky-operation
catch java.io.FileNotFoundException e -> "not found"
catch _ -> "unknown error"
finally
  cleanup
```

### 8. Lazy Evaluation, Data Sources & REPL Audit

**Core principle: everything is lazy. REPL shows samples. Equal speed on 10 and 10M elements.**

**Data sources (first-class):**
```
db is connect "postgres://localhost/mydb"
users is db |> table "users"
data is read-csv "sales.csv"
bucket is connect "s3://my-bucket/"
```

**Lazy pipelines:**
```
// Instant -- only builds execution plan
active is users |> filter _.active |> sort-by _.score

// REPL auto-shows sample (~100 rows)
// => lazy<active> ~7,500 rows
// => | name  | score |
// => | Alice | 95    |
// => | ...   |       |
```

**Audit at any point:**
```
users
|> filter _.active
|> tap!                    // show sample here
|> map {name: _.name}
|> tap!                    // and here
```

**Materialization:**
```
data |> force!              // materialize lazy pipeline, return data
data |> collect             // all into memory (vector)
data |> count               // exact count
data |> save! "out.json"    // write to file (side-effect, returns data)
data |> into! db "results"  // write to DB (side-effect, returns data)
```

Note: `save!` and `into!` have `!` because they are side-effects (passthrough -- return data). `collect`, `count`, `first`, `reduce` are regular functions (return results, no `!`).

**Explore/describe:**
```
data |> describe            // field statistics
data |> sample 20           // 20 random rows
data |> histogram _.age     // ASCII histogram
data |> freq _.status       // frequency table
data |> explain             // show execution plan
```

**Performance model:**
- REPL: micro-sampling (~100-1000 elements) for instant preview
- DB sources: push-down (filter/sort -> WHERE/ORDER BY in SQL)
- File sources: streaming (never load entire file into memory)
- `force!` materializes the full pipeline; `collect` returns as vector

### 9. Error Reporting

**Principle: Elm/Rust-style errors. No stack traces. Point to the right place. Suggest the fix.**

```
users |> filter _.age >

  Unexpected end of expression

  1 | users |> filter _.age >
                              ^^^
  Expected: a value after '>' (number, string, identifier)
  Example: users |> filter _.age > 18
```

**Data-aware warnings (unique to DataTwist):**
```
users |> map _.address.city

  Nil values detected: 3 of 100 sampled rows returned nil at _.address

  Hint: users |> filter _.address != nil |> map _.address.city
  Or: users |> map (_.address.city ?? "unknown")
```

**Common mistake detection:**
```
x = 42

  Unknown operator '='
  Hint: Use 'is' for assignment: x is 42
```

Error codes: `DT-PXXX` (parse), `DT-TXXX` (type), `DT-RXXX` (runtime), `DT-DXXX` (data), `DT-CXXX` (connection).

No Java/Clojure stack traces -- all errors mapped to DataTwist source positions.

### 10. Pipeline as First-Class Runtime Object (architectural)

**Pipeline is not syntax sugar -- it's an inspectable runtime construct.**

Compilation target:
```clojure
// users |> filter _.active |> map _.name |> sort-by _
(dtw/pipeline
  {:source users :loc {:line 1 :col 1}}
  [(dtw/step filter-fn {:loc {:line 2} :label "filter _.active"})
   (dtw/step map-fn    {:loc {:line 3} :label "map _.name"})
   (dtw/step sort-fn   {:loc {:line 4} :label "sort-by _"})])
```

**Compilation**: The compiler wraps `_` expressions into lambdas. `data |> filter _.age > 18` compiles to `(filter (fn [e] (> (:age e) 18)) data)`. This avoids the thread-first vs thread-last problem -- argument position doesn't matter.

Properties:
- **Lazy** -- nothing computes until forced (use `force!` to materialize)
- **Inspectable** -- `dtw/inspect pipeline step sample-size` returns data after step N
- **Metadata** -- source locations, labels per step
- **Fusible** -- runtime optimizes (transducers) while preserving inspection points
- **Explainable** -- `explain` shows execution plan (like SQL EXPLAIN)

**IDE Inspection (primary UX):**
```
users                          // inspect: raw users (10,000 rows)
|> filter _.status = "active"  // inspect: 7,500 rows
|> map {name: _.name}          // inspect: 7,500 rows, 1 column
|> sort-by _.name              // inspect: sorted, 7,500 rows
```

nREPL middleware `dtw/inspect` accepts `{:file :line}`, returns sample data.
IDE plugin shows table inline on click/hover.
`tap!` is the programmatic equivalent for scripts/CI.

## Nil Semantics

| Expression | Result | Rationale |
|---|---|---|
| `nil.field` | `nil` | Nil-tolerant field access |
| `nil.a.b.c` | `nil` | Chain propagates |
| `nil = nil` | `true` | Identity |
| `nil != 5` | `true` | Not equal |
| `nil > 5` | `false` | No ordering for nil |
| `nil and x` | `nil` | Short-circuit (Clojure) |
| `nil or x` | `x` | Short-circuit (Clojure) |
| `value ?? default` | `default` if value is nil | Nil coalescing |
| `nil + 5` | `5` | Nil coerces to identity element (0 for numbers) |
| `nil + "hi"` | `"hi"` | Nil coerces to identity element ("" for strings) |
| `nil * 5` | `0` | Nil coerces to 0 |
| `nil \|> filter _` | `[]` | Nil source = empty collection |

Truthiness: only `nil` and `false` are falsy. `0`, `""`, `[]`, `{}` are truthy (Clojure semantics).

## Program Structure

A DataTwist program is a sequence of top-level forms:

```
// Imports
require clojure.string as str

// Bindings
db is connect "postgres://localhost/mydb"
users is db |> table "users"

// Processing (bindings with pipelines)
active is users |> filter _.active |> sort-by _.score

// Side effects
active |> save! "active-users.json"

// Last expression = program result
active |> count
```

**Top-level forms:** `require`, `name is expr`, bare expressions.
**Function bodies:** sequential `is` bindings + final expression (implicit `let`):
```
process is [data ->
  filtered is data |> filter _.active
  total is filtered |> count
  {items: filtered  total: total}  // return value
]
```

## Standard Library (overview)

The syntactic core is small. Everything else is functions in the standard library:

**Collections:** `filter`, `map`, `reduce`, `sort-by`, `group-by`, `take`, `drop`, `count`, `first`, `last`, `nth`, `distinct`, `flatten`, `reverse`, `concat`, `zip`, `partition`, `frequencies`, `conj`, `assoc`, `dissoc`, `merge`, `keys`, `vals`, `get`, `contains?`, `empty?`, `type`

**Aggregation:** `summarize`, `sum`, `average`, `min`, `max`, `median`

**Data Sources:** `connect`, `table`, `query`, `read-csv`, `read-json`, `read-jsonl`, `read-parquet`, `read-lines`, `close!`

**IO / Side Effects:** `save!`, `into!`, `log!`, `tap!`, `force!`

**Nil Handling:** `fill-nil`, `skip-nil`, `coerce`

**Exploration:** `describe`, `schema`, `sample`, `histogram`, `freq`, `explain`

**Multi-Source:** `join`, `left-join`, `inner-join`, `outer-join`, `define`

## BDD Feature Files

Detailed scenarios in `bdd/` directory:
1. `1-literals-types-operators.feature` -- 131 scenarios
2. `2-data-structures.feature` -- 59 scenarios
3. `3-functions-closures.feature` -- 40 scenarios
4. `4-pipeline.feature` -- ~65 scenarios
5. `5-binding-destructuring.feature` -- 67 scenarios
6. `6-pattern-matching.feature` -- 38 scenarios
7. `7-interop-misc.feature` -- 97 scenarios
8. `8-lazy-eval-data-sources.feature` -- 105 scenarios
9. `9-error-reporting.feature` -- 89 scenarios
