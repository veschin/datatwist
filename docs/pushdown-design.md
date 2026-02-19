# Pushdown Optimization Design for DataTwist

## 1. Problem Statement

### What is pushdown?

Pushdown optimization means moving computation from the client (DataTwist evaluator running on JVM) to the data source (PostgreSQL, file reader, API server). Instead of fetching all rows into memory and filtering/sorting/limiting locally, the evaluator analyzes the pipeline, determines which operations the source can handle natively, and sends a single optimized request.

### Why it matters

Consider this DataTwist pipeline:

```
db is connect "postgres://localhost/mydb"
db |> table "users" |> filter _.age > 18 |> sort-by _.name |> take 10 |> collect
```

**Without pushdown (current behavior):**
1. `table "users"` executes `SELECT * FROM users` -- fetches 1,000,000 rows
2. `filter _.age > 18` iterates all 1M rows in JVM heap, keeping ~700,000
3. `sort-by _.name` sorts 700K records in memory (O(n log n), ~2GB allocation)
4. `take 10` discards 699,990 rows
5. Result: 10 rows. Cost: transferred 1M rows, allocated ~3GB, took 12 seconds.

**With pushdown:**
1. Pipeline analysis detects: filter, sort-by, take are all SQL-translatable
2. Single query: `SELECT * FROM users WHERE age > 18 ORDER BY name ASC LIMIT 10`
3. PostgreSQL uses its B-tree index on `age`, sorts via index on `name`, returns 10 rows
4. Result: 10 rows. Cost: transferred 10 rows, allocated ~4KB, took 8ms.

That is a **1500x reduction in data transfer** and **1500x reduction in memory**. For analytical workloads where the selectivity is high (filter removes 99%+ of rows), pushdown is the difference between "works" and "crashes with OutOfMemoryError".

### The BDD spec requires it

The BDD feature file `bdd/8-lazy-eval-data-sources.feature` (Section 7, line 524) explicitly defines expected SQL pushdown behavior:

```gherkin
Scenario: Combined push-down for filter, sort, and limit in one SQL query
  Given the source code:
    """
    db is connect "postgres://localhost/mydb"
    result is db |> table "users"
      |> filter _.active
      |> filter _.age >= 18
      |> sort-by _.score
      |> take 20
      |> collect
    """
  Then the executed SQL is:
    """
    SELECT * FROM users WHERE active = true AND age >= 18 ORDER BY score ASC LIMIT 20
    """
  And all operations are pushed to a single SQL query
```

This is not optional. It is part of the language specification.

---

## 2. State of the Art

### 2.1 Apache Spark Catalyst Optimizer

Spark's Catalyst is the most sophisticated open-source pushdown system. Its architecture:

**Logical Plan -> Optimized Logical Plan -> Physical Plan -> Code Generation**

Key mechanisms relevant to DataTwist:

1. **Predicate pushdown**: Catalyst pushes `WHERE` predicates through joins, aggregations, and projections. Rule: `Filter(Project(Scan))` is rewritten to `Project(Filter(Scan))`, then `Filter` is pushed into the `Scan` node as a data source filter.

2. **Projection pruning**: If the query only accesses `_.name` and `_.age`, Catalyst rewrites `SELECT *` to `SELECT name, age`. This is done by analyzing which columns are referenced in downstream operations.

3. **Connector SPI (V2)**: Data sources implement `SupportsPushDownFilters` and `SupportsPushDownRequiredColumns`. The optimizer asks the source "can you handle this filter?", and the source returns which filters it accepted vs. which must be evaluated post-scan.

```java
// Spark V2 source interface (simplified)
interface SupportsPushDownFilters {
    Filter[] pushFilters(Filter[] filters);    // returns filters NOT handled
    Filter[] pushedFilters();                  // returns filters that WERE handled
}
```

**What is relevant for DataTwist:** The connector SPI pattern (source declares capabilities), predicate/projection pushdown, and the "longest pushable prefix" strategy. What is overkill: Catalyst's join reordering, cost-based optimization, and whole-stage codegen (DataTwist pipelines are simple linear chains, not DAGs).

### 2.2 LINQ (C# / .NET)

LINQ's approach is the closest analog to DataTwist's design:

1. **IQueryable vs IEnumerable**: `IEnumerable<T>` evaluates locally. `IQueryable<T>` builds an expression tree that a provider translates to a query. The type system itself distinguishes pushable from local.

2. **Expression trees**: When you write `users.Where(u => u.Age > 18)`, the C# compiler does not compile the lambda to IL. Instead it builds an `Expression<Func<User,bool>>` -- a data structure representing the lambda's AST. The LINQ provider walks this tree and translates it to SQL.

3. **Provider pattern**: Each database has a LINQ provider that implements `IQueryProvider.Execute(Expression)`. The provider receives the full expression tree and translates what it can.

```csharp
// This builds an expression tree, not a delegate
IQueryable<User> query = db.Users
    .Where(u => u.Age > 18)      // Expression<Func<User,bool>>
    .OrderBy(u => u.Name)        // Expression<Func<User,string>>
    .Take(10);

// Execution happens here -- provider translates tree to SQL
List<User> result = query.ToList();
```

**What is relevant for DataTwist:** The expression tree concept maps directly to DataTwist's AST analysis. The lazy-until-materialized model (`ToList()` = `collect`) is exactly what the PRD specifies. The key insight: LINQ succeeds because lambdas remain as analyzable data (expression trees), not opaque compiled functions.

### 2.3 Presto/Trino Connector SPI

Trino's connector architecture is the most relevant for DataTwist's multi-source story:

1. **ConnectorMetadata**: Provides table schemas, column types. Used for projection pushdown (knowing which columns exist).

2. **ConnectorSplitManager**: Handles partitioning. Not relevant for DataTwist's single-query model.

3. **Pushdown negotiation**: The engine calls `applyFilter(ConnectorSession, TableHandle, Constraint)` on the connector. The connector returns a new `TableHandle` with the filter baked in, plus a `RemainingPredicate` for anything it could not handle.

```java
// Trino pushdown negotiation
Optional<ConstraintApplicationResult<TableHandle>> result =
    metadata.applyFilter(session, tableHandle, constraint);
// result contains: newTableHandle (with pushed filter) + remainingFilter
```

**What is relevant for DataTwist:** The negotiation model (try to push, get back what remains) is cleaner than Spark's approach. DataTwist should adopt this: offer each operation to the source, the source accepts or rejects, anything rejected executes locally.

### 2.4 dbt / Malloy

These are compile-to-SQL languages, not pushdown optimizers:

- **dbt**: Templates that generate SQL strings. No runtime, no local execution. Everything is SQL or nothing.
- **Malloy**: A query language that compiles entirely to SQL. Every operation has a SQL translation. If it cannot be expressed in SQL, it is a compilation error.

**What is relevant for DataTwist:** Malloy's SQL generation patterns (especially for nested aggregations and window functions) are useful reference material. But DataTwist fundamentally differs: it must support mixed pipelines where some steps push down and others execute locally. Pure compile-to-SQL is not an option because DataTwist supports user-defined functions, closures, and Clojure interop.

### 2.5 Summary: What DataTwist should adopt

| System | Concept to adopt | Concept to skip |
|--------|-----------------|-----------------|
| Spark Catalyst | Connector SPI (capability declaration), predicate pushdown rules | Cost-based optimization, join reordering, codegen |
| LINQ | Expression trees (AST-based predicate analysis), lazy-until-materialized | Static type system dependency, provider compilation |
| Trino | Pushdown negotiation (try/accept/reject), remaining predicate | Distributed execution, split management |
| Malloy | SQL generation patterns for filter/sort/limit/aggregate | Pure-SQL-only constraint |

---

## 3. DataTwist-Specific Challenges

### 3.1 Instaparse AST is deeply nested

The grammar produces a tree where every expression is wrapped in multiple layers. A simple `_.age > 18` parses to something like:

```clojure
[:PipeAtom
  [:CompExpr
    [:FieldAccess [:Wildcard] [:FieldName "age"]]
    [:CompOp ">"]
    [:Integer "18"]]]
```

But in reality, Instaparse wraps each level in the precedence chain:

```clojure
[:PipeAtom
  [:OrExpr
    [:AndExpr
      [:NilCoalesce
        [:NotExpr
          [:CompExpr
            [:InExpr
              [:AddExpr
                [:MulExpr
                  [:UnaryExpr
                    [:FnCallExpr
                      [:FieldAccess [:Wildcard] [:FieldName "age"]]]]]]]
            [:CompOp ">"]
            [:InExpr
              [:AddExpr
                [:MulExpr
                  [:UnaryExpr
                    [:FnCallExpr
                      [:FieldAccess [:Atom [:Integer "18"]]]]]]]]]]]]]]
```

The evaluator already has a `descend-to-inner` helper (line ~1229 of `evaluator.clj`) that strips these wrappers. The pushdown analyzer will need a similar "unwrap to meaningful node" utility, or better yet, should operate on a simplified/normalized IR rather than the raw parse tree.

### 3.2 Implicit lambda extraction (`_` context)

In `filter _.age > 18`, the predicate is not an explicit lambda `[u -> u.age > 18]`. Instead, `_` is a context-dependent placeholder. The evaluator handles this by:

1. Detecting wildcards in the pipe atom (`contains-wildcard?`)
2. Finding the "pipeline FnCall" (`find-pipeline-fncall`) -- the FnCall node where `filter` is the call target and `_` appears in an argument
3. Extracting the predicate AST by replacing the FnCall with the wildcard expression
4. Creating a lambda: `(fn [x] (eval-node predicate-ast (bind "_" x)))`

For pushdown, we need to go further. We need to **statically analyze** the predicate AST to determine:
- Which fields are accessed (`_.age` -> column "age")
- Which comparison operators are used (`>` -> SQL `>`)
- Whether the predicate contains only pushable constructs (field access, literals, comparison, and/or/not) or includes non-pushable constructs (function calls, closures, Clojure interop)

This is doable because the AST is available before evaluation. The current evaluator creates a lambda and discards the AST. The pushdown system must intercept before that point.

### 3.3 Heterogeneous pipeline steps

A DataTwist pipeline can mix pushable and non-pushable operations:

```
db |> table "users"
  |> filter _.active              // pushable: WHERE active = true
  |> filter _.age > 18            // pushable: AND age > 18
  |> map [u -> custom-score u]    // NOT pushable: user-defined function
  |> sort-by _.score              // NOT pushable (after non-pushable step)
  |> take 10                      // NOT pushable (after non-pushable step)
  |> collect
```

Once a non-pushable operation appears, all subsequent operations must also execute locally, even if they are individually pushable. This is because the data changes in a way the source cannot predict. The pushdown boundary is the **longest prefix** of pushable operations.

However, there is a subtlety: `take 10` at the end could theoretically still be pushed if we can prove the non-pushable steps do not change the number of rows. In practice, for Phase 1 we should use the simple "longest prefix" rule and optimize later.

### 3.4 Schema is unknown at analysis time

DataTwist is dynamically typed. When we see `filter _.age > 18`, we do not know:
- Whether column "age" exists in the source
- Whether "age" is an integer, string, or date
- Whether the source is even a database (it might be a CSV file or in-memory vector)

This means pushdown analysis must be **speculative**: we analyze the AST as if pushdown is possible, then check at execution time whether the source actually supports it. If the source is a plain vector, no pushdown happens and the pipeline executes locally as before.

### 3.5 Side-effect functions break pushdown

Functions ending with `!` (`tap!`, `log!`, `save!`) are passthrough side-effects. They break pushdown because:
- They must execute in the JVM (not in the database)
- They observe intermediate data (so the data must be materialized at that point)
- Even though they are passthrough, they create a materialization boundary

```
db |> table "users"
  |> filter _.active          // pushable
  |> tap!                     // BOUNDARY: must materialize filter results
  |> sort-by _.name           // could be pushable but comes after boundary
  |> take 10                  // same
```

The `tap!` forces a split: push `filter` to SQL, execute locally from `tap!` onward.

---

## 4. Proposed Architecture

### 4.1 Pipeline IR (Intermediate Representation)

Before pushdown analysis, translate the raw AST pipeline into a flat list of typed operations. This is the critical normalization step that makes everything else tractable.

```clojure
(ns datatwist.pushdown.ir)

;; A pipeline operation -- one step in the pipeline
(defrecord PipeOp [type     ; keyword: :filter, :map, :sort-by, :take, :drop, :tap, :custom
                   ast      ; original AST node (for fallback to local evaluation)
                   pred-ast ; for :filter -- the predicate AST (the part after `filter`)
                   key-ast  ; for :sort-by -- the sort key AST
                   n        ; for :take/:drop -- the integer literal (or nil if dynamic)
                   fields   ; for :map projection -- list of field names (or nil if complex)
                   pushable ; boolean: can this be pushed in isolation?
                   ])

(defn pipeline-ast->ir
  "Convert a Pipeline AST node into a vector of PipeOp records.
   Input:  [:Pipeline source-node step1 step2 ...]
   Output: {:source source-node, :steps [PipeOp PipeOp ...]}"
  [pipeline-node env]
  (let [children (rest pipeline-node)
        source   (first children)
        steps    (rest children)]
    {:source source
     :steps  (mapv (fn [step-node] (classify-step step-node env)) steps)}))
```

**Step classification** -- the core of the IR builder:

```clojure
(defn- classify-step
  "Analyze a PipeAtom AST node and return a PipeOp."
  [step-node env]
  (let [inner     (descend-to-inner step-node)
        fncall    (find-pipeline-fncall step-node)]
    (cond
      ;; FnCall: filter _.pred, sort-by _.key, take N, drop N, map _.fields
      fncall
      (let [call-target (-> fncall rest first)   ; CallTarget node
            fn-name     (extract-identifier call-target)
            call-args   (-> fncall rest rest)]    ; CallArg nodes
        (case fn-name
          "filter"  (->PipeOp :filter step-node
                              (extract-predicate-ast step-node fncall)
                              nil nil nil
                              (analyzable-predicate? step-node))
          "sort-by" (->PipeOp :sort-by step-node nil
                              (extract-sort-key-ast (first call-args))
                              nil nil
                              (analyzable-sort-key? (first call-args)))
          "take"    (->PipeOp :take step-node nil nil
                              (extract-integer-literal (first call-args))
                              nil
                              (integer-literal? (first call-args)))
          "drop"    (->PipeOp :drop step-node nil nil
                              (extract-integer-literal (first call-args))
                              nil
                              (integer-literal? (first call-args)))
          "map"     (->PipeOp :map step-node nil nil nil
                              (extract-projection-fields step-node)
                              (pure-projection? step-node))
          "count"   (->PipeOp :count step-node nil nil nil nil true)
          ;; tap!, log!, etc. -- side effect, never pushable
          (if (clojure.string/ends-with? (or fn-name "") "!")
            (->PipeOp :side-effect step-node nil nil nil nil false)
            (->PipeOp :custom step-node nil nil nil nil false))))

      ;; No FnCall -- bare expression (e.g., object literal for smart map)
      :else
      (->PipeOp :custom step-node nil nil nil nil false))))
```

### 4.2 Pushdown Protocol

The protocol that data sources implement to declare and accept pushdown operations:

```clojure
(ns datatwist.pushdown.protocol)

(defprotocol PushdownSource
  "Protocol for data sources that support query pushdown.
   Implementations accumulate operations into an internal query plan,
   then execute the plan as a single request."

  (source-type [this]
    "Returns a keyword identifying the source type, e.g. :sql, :csv, :api.
     Used for debugging and explain output.")

  (supports-op? [this op-type]
    "Returns true if this source supports pushing down the given operation type.
     op-type is one of: :filter, :sort, :limit, :offset, :project, :aggregate, :count.")

  (push-filter [this pred-ir]
    "Push a filter predicate. pred-ir is a normalized predicate map
     (see section 4.3). Returns a new PushdownSource with the filter added,
     or nil if this specific predicate cannot be pushed.")

  (push-sort [this key-field direction]
    "Push a sort operation. key-field is a string column name.
     direction is :asc or :desc. Returns a new PushdownSource or nil.")

  (push-limit [this n]
    "Push a LIMIT. n is a positive integer. Returns a new PushdownSource or nil.")

  (push-offset [this n]
    "Push an OFFSET. n is a non-negative integer. Returns a new PushdownSource or nil.")

  (push-project [this fields]
    "Push a projection (SELECT specific columns). fields is a vector of string
     column names. Returns a new PushdownSource or nil.")

  (push-count [this]
    "Push a COUNT(*) aggregation. Returns a new PushdownSource that, when
     executed, returns a single integer instead of rows. Returns nil if unsupported.")

  (execute [this]
    "Execute the accumulated query plan and return results.
     For SQL: runs the generated query, returns a lazy seq of maps.
     For files: opens reader with appropriate streaming strategy.
     For count: returns a single integer.")

  (explain-plan [this]
    "Return a human-readable string describing the query plan.
     For SQL: the generated SQL string.
     For files: the file path + streaming strategy description."))
```

**Immutable accumulation**: Each `push-*` method returns a new source instance with the operation added. This is critical for two reasons: (1) the optimizer can speculatively try pushing and backtrack if a later step fails, (2) the same source can be reused in multiple pipelines without interference.

### 4.3 Predicate Analysis

This is the hardest part: converting a DataTwist AST predicate into a structured, source-translatable representation.

#### Predicate IR

```clojure
(ns datatwist.pushdown.predicate)

;; Predicate IR: a recursive structure describing filter conditions
;;
;; {:op :comparison
;;  :comparator ">"       ; ">" "<" ">=" "<=" "=" "!="
;;  :field "age"          ; column name (from _.field access)
;;  :value 18}            ; literal value
;;
;; {:op :and
;;  :clauses [pred1 pred2 ...]}
;;
;; {:op :or
;;  :clauses [pred1 pred2 ...]}
;;
;; {:op :not
;;  :clause pred}
;;
;; {:op :is-nil
;;  :field "name"}
;;
;; {:op :is-not-nil
;;  :field "name"}
;;
;; {:op :in
;;  :field "status"
;;  :values ["active" "pending"]}
;;
;; {:op :field-comparison     ; _.age > _.score (two columns)
;;  :comparator ">"
;;  :left-field "age"
;;  :right-field "score"}
;;
;; nil = not analyzable (contains function calls, closures, etc.)
```

#### AST-to-Predicate Translation

Walking the predicate AST and converting to the IR:

```clojure
(defn analyze-predicate
  "Analyze a predicate AST node and return a predicate IR map, or nil
   if the predicate cannot be analyzed (contains opaque function calls, etc.)."
  [pred-ast]
  (let [inner (descend-to-inner pred-ast)]
    (when (vector? inner)
      (case (first inner)
        ;; CompExpr: two operands + comparison operator
        :CompExpr
        (let [children (rest inner)
              left     (first children)
              op-node  (second children)     ; [:CompOp ">"]
              right    (nth children 2 nil)]
          (when (and op-node right)
            (let [op        (second op-node)
                  left-val  (analyze-operand left)
                  right-val (analyze-operand right)]
              (cond
                ;; _.field > literal
                (and (:field left-val) (:literal right-val))
                {:op :comparison
                 :comparator op
                 :field (:field left-val)
                 :value (:literal right-val)}

                ;; literal < _.field  ->  flip to _.field > literal
                (and (:literal left-val) (:field right-val))
                {:op :comparison
                 :comparator (flip-comparator op)
                 :field (:field right-val)
                 :value (:literal left-val)}

                ;; _.field > _.other_field
                (and (:field left-val) (:field right-val))
                {:op :field-comparison
                 :comparator op
                 :left-field (:field left-val)
                 :right-field (:field right-val)}

                :else nil))))

        ;; AndExpr: conjunction of clauses
        :AndExpr
        (let [clause-results (map analyze-predicate (rest inner))]
          (when (every? some? clause-results)
            (if (= 1 (count clause-results))
              (first clause-results)
              {:op :and :clauses (vec clause-results)})))

        ;; OrExpr: disjunction
        :OrExpr
        (let [clause-results (map analyze-predicate (rest inner))]
          (when (every? some? clause-results)
            (if (= 1 (count clause-results))
              (first clause-results)
              {:op :or :clauses (vec clause-results)})))

        ;; NotExpr: negation (KW-NOT child)
        :NotExpr
        (let [children (rest inner)]
          ;; NotExpr = KW-NOT __ NotExpr | CompExpr
          ;; If first child is KW-NOT, the second child is the negated expr
          (if (and (vector? (first children))
                   (= :KW-NOT (ffirst children)))
            (when-let [inner-pred (analyze-predicate (second children))]
              {:op :not :clause inner-pred})
            ;; Otherwise it's a plain CompExpr pass-through
            (analyze-predicate (first children))))

        ;; FieldAccess with no comparison: bare truthy check (_.active)
        :FieldAccess
        (let [field (extract-field-name inner)]
          (when field
            {:op :comparison
             :comparator "="
             :field field
             :value true}))

        ;; InExpr: _.status in ["a" "b"]
        :InExpr
        (let [children (rest inner)
              left     (first children)
              right    (second children)]
          (when right
            (let [left-val  (analyze-operand left)
                  list-vals (extract-literal-list right)]
              (when (and (:field left-val) list-vals)
                {:op :in
                 :field (:field left-val)
                 :values list-vals}))))

        ;; Anything else: not analyzable
        nil))))

(defn- analyze-operand
  "Analyze one side of a comparison. Returns {:field \"name\"} for field access,
   {:literal value} for literals, or nil if not analyzable."
  [node]
  (let [inner (descend-to-inner node)]
    (when (vector? inner)
      (case (first inner)
        :FieldAccess
        (let [children (rest inner)]
          ;; Must be: Wildcard.FieldName or Wildcard.Field1.Field2
          (when (and (>= (count children) 2)
                     (vector? (first children))
                     (= :Wildcard (ffirst children)))
            ;; Extract field name(s). For _.a.b -> "a.b" or just "a" for _.a
            {:field (clojure.string/join "." (map second (rest children)))}))

        :Integer {:literal (Long/parseLong (second inner))}
        :Float   {:literal (Double/parseDouble (second inner))}
        :String  {:literal (second inner)}
        :Boolean {:literal (= "true" (second inner))}
        :Nil     {:literal nil}
        :Keyword {:literal (keyword (subs (second inner) 1))}

        ;; Wildcard alone (bare _): represents the whole row, not a field
        :Wildcard nil

        nil))))

(defn- flip-comparator [op]
  (case op
    ">"  "<"
    "<"  ">"
    ">=" "<="
    "<=" ">="
    "="  "="
    "!=" "!="
    op))
```

#### What predicates CANNOT be pushed

The `analyze-predicate` function returns `nil` for anything it cannot translate. Concrete examples:

```
filter [u -> some-clojure-fn u.data]    ;; explicit lambda with user fn -- nil
filter _.age > compute-threshold         ;; non-literal right side -- nil
filter _.name = upper-case _.name        ;; function call in predicate -- nil
filter (_.age + _.bonus) > 100           ;; arithmetic in field position -- nil (Phase 1)
```

Phase 1 handles: `_.field OP literal`, `_.field OP _.field`, `and`/`or`/`not` of the above, `_.field in [literals]`, bare truthy `_.field`. Everything else falls through to local evaluation.

### 4.4 SQL Generation

Given a `PushdownSource` that has accumulated operations, generate a SQL string:

```clojure
(ns datatwist.pushdown.sql
  (:require [clojure.string :as str]))

(defrecord SqlSource
  [table-name     ; string: "users"
   connection     ; JDBC connection/datasource
   filters        ; vector of predicate IR maps
   sort-key       ; string field name or nil
   sort-dir       ; :asc or :desc
   limit-n        ; integer or nil
   offset-n       ; integer or nil
   projections    ; vector of field name strings, or nil (= SELECT *)
   count?         ; boolean: if true, SELECT COUNT(*) instead of rows
   ])

(defn generate-sql
  "Generate a SQL string and parameter vector from a SqlSource."
  [{:keys [table-name filters sort-key sort-dir limit-n offset-n projections count?]}]
  (let [params    (atom [])
        add-param (fn [v] (swap! params conj v) "?")

        ;; SELECT clause
        select (cond
                 count?      "SELECT COUNT(*)"
                 projections (str "SELECT " (str/join ", " projections))
                 :else       "SELECT *")

        ;; FROM clause
        from (str "FROM " table-name)

        ;; WHERE clause
        where (when (seq filters)
                (str "WHERE " (str/join " AND "
                               (map #(pred-ir->sql % add-param) filters))))

        ;; ORDER BY clause
        order-by (when sort-key
                   (str "ORDER BY " sort-key " " (name (or sort-dir :asc))))

        ;; LIMIT / OFFSET
        limit  (when limit-n (str "LIMIT " limit-n))
        offset (when offset-n (str "OFFSET " offset-n))

        ;; Assemble
        parts (remove nil? [select from where order-by limit offset])
        sql   (str/join " " parts)]
    {:sql sql :params @params}))

(defn- pred-ir->sql
  "Convert a predicate IR map to a SQL fragment string.
   add-param is a function that registers a parameter and returns '?'."
  [pred add-param]
  (case (:op pred)
    :comparison
    (let [{:keys [comparator field value]} pred]
      (if (nil? value)
        (case comparator
          "="  (str field " IS NULL")
          "!=" (str field " IS NOT NULL")
          (str field " " comparator " " (add-param value)))
        (str field " " (sql-comparator comparator) " " (add-param value))))

    :field-comparison
    (let [{:keys [comparator left-field right-field]} pred]
      (str left-field " " (sql-comparator comparator) " " right-field))

    :and
    (str "(" (str/join " AND " (map #(pred-ir->sql % add-param) (:clauses pred))) ")")

    :or
    (str "(" (str/join " OR " (map #(pred-ir->sql % add-param) (:clauses pred))) ")")

    :not
    (str "NOT (" (pred-ir->sql (:clause pred) add-param) ")")

    :is-nil
    (str (:field pred) " IS NULL")

    :is-not-nil
    (str (:field pred) " IS NOT NULL")

    :in
    (let [{:keys [field values]} pred
          placeholders (str/join ", " (map add-param values))]
      (str field " IN (" placeholders ")"))))

(defn- sql-comparator [op]
  (case op
    "="  "="
    "!=" "<>"
    ">"  ">"
    "<"  "<"
    ">=" ">="
    "<=" "<="
    op))
```

**Example output** for the combined scenario:

```
Input pipeline:
  table "users" |> filter _.active |> filter _.age >= 18 |> sort-by _.score |> take 20

Generated:
  {:sql    "SELECT * FROM users WHERE active = ? AND age >= ? ORDER BY score asc LIMIT 20"
   :params [true 18]}
```

### 4.5 Pushdown Boundary Detection

The optimizer walks the IR steps, trying to push each one to the source. When a step cannot be pushed, the boundary is found: everything before executes remotely, everything from that point executes locally.

```clojure
(ns datatwist.pushdown.optimizer)

(defn find-pushdown-boundary
  "Given a PushdownSource and a vector of PipeOp steps, find the longest
   prefix that can be pushed down to the source.

   Returns {:pushed-source  <PushdownSource with ops accumulated>
            :pushed-count   <int: number of steps pushed>
            :local-steps    <vector of remaining PipeOp steps>}"
  [source steps]
  (loop [current-source source
         remaining      steps
         pushed-count   0]
    (if (empty? remaining)
      ;; All steps pushed
      {:pushed-source current-source
       :pushed-count  pushed-count
       :local-steps   []}
      (let [step (first remaining)]
        (if-not (:pushable step)
          ;; Step not pushable at all -- boundary found
          {:pushed-source current-source
           :pushed-count  pushed-count
           :local-steps   (vec remaining)}
          ;; Step is structurally pushable -- try to push to source
          (let [new-source (try-push current-source step)]
            (if new-source
              ;; Source accepted it -- continue
              (recur new-source (rest remaining) (inc pushed-count))
              ;; Source rejected it -- boundary found
              {:pushed-source current-source
               :pushed-count  pushed-count
               :local-steps   (vec remaining)})))))))

(defn- try-push
  "Attempt to push a single PipeOp to the source. Returns the new source
   if successful, or nil if the source cannot handle this operation."
  [source step]
  (case (:type step)
    :filter
    (when-let [pred-ir (analyze-predicate (:pred-ast step))]
      (push-filter source pred-ir))

    :sort-by
    (when-let [field (extract-simple-field (:key-ast step))]
      (push-sort source field :asc))

    :take
    (when-let [n (:n step)]
      (push-limit source n))

    :drop
    (when-let [n (:n step)]
      (push-offset source n))

    :map
    (when-let [fields (:fields step)]
      (push-project source fields))

    :count
    (push-count source)

    ;; Anything else: cannot push
    nil))
```

### 4.6 Integration with the Evaluator

The key integration point is `eval-pipeline` in `evaluator.clj` (line 1293). Currently it is a simple `reduce` that evaluates each step sequentially. With pushdown, it must first check if the source is a `PushdownSource` and, if so, run the optimizer before falling through to local evaluation.

```clojure
;; Modified eval-pipeline (conceptual -- actual integration in evaluator.clj)
(defn- eval-pipeline
  "Evaluate a sequence of PipeAtom nodes against initial data."
  [data steps env]
  (if (satisfies? PushdownSource data)
    ;; Source supports pushdown -- optimize
    (let [ir           (mapv #(classify-step % env) steps)
          {:keys [pushed-source pushed-count local-steps]}
                       (find-pushdown-boundary data ir)
          ;; Execute the pushed portion
          remote-data  (execute pushed-source)
          ;; Continue with local steps
          local-nodes  (mapv :ast local-steps)]
      (if (empty? local-nodes)
        remote-data
        (reduce (fn [d step-node]
                  (let [step-fn (eval-pipe-atom-with-fn-call step-node env)]
                    (step-fn d)))
                remote-data
                local-nodes)))
    ;; Not a PushdownSource -- evaluate locally as before
    (reduce (fn [d step-node]
              (let [step-fn (eval-pipe-atom-with-fn-call step-node env)]
                (step-fn d)))
            data
            steps)))
```

This is a minimal change: existing local evaluation is completely preserved. Pushdown is additive -- it only activates when the source opts in.

### 4.7 Whole-Block Pushdown Example

Walking through the BDD scenario from the spec (feature file line 593):

```
db is connect "postgres://localhost/mydb"
result is db |> table "users"
  |> filter _.active
  |> filter _.age >= 18
  |> sort-by _.score
  |> take 20
  |> collect
```

**Step 1: Parse.** The parser produces:

```clojure
[:Pipeline
  [:FnCall [:CallTarget [:Identifier "table"]] [:CallArg [:String "users"]]]
  [:FnCall [:CallTarget [:Identifier "filter"]] [:CallArg [:FieldAccess [:Wildcard] [:FieldName "active"]]]]
  [:FnCall [:CallTarget [:Identifier "filter"]] [:CallArg [:CompExpr [:FieldAccess [:Wildcard] [:FieldName "age"]] [:CompOp ">="] [:Integer "18"]]]]
  [:FnCall [:CallTarget [:Identifier "sort-by"]] [:CallArg [:FieldAccess [:Wildcard] [:FieldName "score"]]]]
  [:FnCall [:CallTarget [:Identifier "take"]] [:CallArg [:Integer "20"]]]
  [:FnCall [:CallTarget [:Identifier "collect"]]]]
```

(Simplified -- actual AST has more wrapper nodes.)

**Step 2: Source evaluation.** `db |> table "users"` evaluates `table` with `db` as first argument. `table` returns a `SqlSource`:

```clojure
(SqlSource. "users" jdbc-conn [] nil nil nil nil nil false)
```

**Step 3: IR classification.** The remaining steps become:

```clojure
[(->PipeOp :filter  ... pred-ast1 nil nil nil true)   ; _.active
 (->PipeOp :filter  ... pred-ast2 nil nil nil true)   ; _.age >= 18
 (->PipeOp :sort-by ... nil key-ast nil nil true)      ; _.score
 (->PipeOp :take    ... nil nil 20 nil true)           ; 20
 (->PipeOp :custom  ... nil nil nil nil false)]         ; collect
```

Note: `collect` is classified as `:custom` (not pushable). That is fine -- it triggers materialization.

**Step 4: Pushdown boundary.** The optimizer pushes filter, filter, sort-by, take. Stops at `collect`.

```clojure
{:pushed-source (SqlSource. "users" jdbc-conn
                  [{:op :comparison :comparator "=" :field "active" :value true}
                   {:op :comparison :comparator ">=" :field "age" :value 18}]
                  "score" :asc 20 nil nil false)
 :pushed-count 4
 :local-steps [(->PipeOp :custom ...)]}  ; collect
```

**Step 5: SQL generation.**

```clojure
{:sql    "SELECT * FROM users WHERE active = ? AND age >= ? ORDER BY score asc LIMIT 20"
 :params [true 18]}
```

**Step 6: Execution.** The SQL is sent to PostgreSQL. It returns 20 rows as a lazy seq of maps. Then `collect` (the local step) materializes the lazy seq into a vector.

---

## 5. Implementation Phases

### Phase 1: Pipeline IR + Classification (no actual pushdown)

**Goal:** Build the IR layer and classify every pipeline step, without changing execution behavior. This is a pure analysis layer that runs alongside the evaluator, producing debug output.

**Deliverables:**
- `datatwist.pushdown.ir` namespace: `PipeOp` record, `classify-step`, `pipeline-ast->ir`
- `datatwist.pushdown.predicate` namespace: `analyze-predicate`, `analyze-operand`
- `explain` function that prints the IR classification for any pipeline
- Tests: parse various pipelines, assert correct classification and predicate IR

**Effort estimate:** 2-3 weeks. This is foundational -- getting predicate analysis right is the hardest part.

**Testable without a database:** Feed in-memory ASTs, verify IR output matches expected structure.

### Phase 2: SQL Source with Filter + Sort + Limit Pushdown

**Goal:** Implement `SqlSource` record, `PushdownSource` protocol for SQL, and the optimizer loop. Wire into `eval-pipeline`.

**Deliverables:**
- `datatwist.pushdown.protocol` namespace: the `PushdownSource` protocol
- `datatwist.pushdown.sql` namespace: `SqlSource` record, `generate-sql`, `pred-ir->sql`
- `datatwist.pushdown.optimizer` namespace: `find-pushdown-boundary`, `try-push`
- Modified `eval-pipeline` in `evaluator.clj` with pushdown branch
- `connect` and `table` stdlib functions that return `SqlSource` instances
- Integration with `next.jdbc` for actual query execution

**Effort estimate:** 3-4 weeks. The SQL generation is straightforward; the tricky part is the `eval-pipeline` integration and making sure local fallback works perfectly.

**Testing strategy:** Use an embedded H2 or SQLite database in tests. Create tables, insert data, run DataTwist pipelines, assert both the generated SQL and the result data.

### Phase 3: Projection Pushdown

**Goal:** Analyze `map` steps to determine which fields are accessed, and push `SELECT field1, field2` instead of `SELECT *`.

**Deliverables:**
- Field usage analysis: walk downstream pipeline steps, collect all `_.field` accesses
- `push-project` implementation in `SqlSource`
- Handle `map {name: _.name age: _.age}` as a pure projection (all values are simple field accesses)
- Handle `map {name: _.name score: _.grade * 2}` as partial projection (push `SELECT name, grade`, compute `score` locally)

**Effort estimate:** 2 weeks. Projection analysis is simpler than predicate analysis because we only need to find field accesses, not translate arbitrary expressions.

### Phase 4: Aggregation Pushdown

**Goal:** Push `count`, `sum`, `average`, `group-by` to SQL.

**Deliverables:**
- `push-count` and `push-aggregate` in `SqlSource`
- SQL generation for `COUNT(*)`, `SUM(field)`, `AVG(field)`, `GROUP BY field`
- Handle `group-by _.status |> map {status: _.key count: _.value |> count}` as `SELECT status, COUNT(*) FROM ... GROUP BY status`

**Effort estimate:** 3 weeks. Aggregation pushdown is complex because DataTwist's `group-by` returns maps of grouped values, and the `map` over groups is a separate step that must be analyzed together with the `group-by`.

### Phase 5: Multi-Source (Joins)

**Goal:** Support joins between two pushed sources.

**Deliverables:**
- `join`, `left-join`, `inner-join` aware of pushdown
- When both sides are `SqlSource` on the same connection, generate `JOIN ... ON`
- When sides are different connections, fall back to local join

**Effort estimate:** 4+ weeks. This is the most complex phase because it requires analyzing two pipelines together, aligning their schemas, and generating correct join SQL.

---

## 6. Trade-offs and Open Questions

### 6.1 Eager vs. Lazy Pushdown

**Option A: Always push (eager).** If the source supports it, push. The user never thinks about it. This is what Spark does.

**Option B: Opt-in pushdown.** The user explicitly requests pushdown (e.g., `|> push!`). Local evaluation is the default.

**Recommendation: Option A.** The PRD already specifies that `explain` shows the execution plan. Users who want to verify pushdown use `explain`. Users who want to force local evaluation can `collect` early:

```
db |> table "users" |> collect |> filter _.age > 18  // forces local
```

### 6.2 Schema Inference

**Problem:** Pushdown generates SQL with column names. If the column does not exist, the database returns an error. DataTwist is nil-tolerant for field access, but SQL is not.

**Options:**
1. **Query metadata first.** When `table "users"` is called, execute `SELECT * FROM users LIMIT 0` or query `information_schema.columns` to learn the schema. Cache it.
2. **Fail at execution time.** Generate SQL optimistically, let the database error propagate. Catch the error and fall back to local evaluation.
3. **Hybrid.** Query metadata lazily (on first pushdown attempt), cache for subsequent operations.

**Recommendation: Option 3 (hybrid).** Query `information_schema.columns` on first `push-filter` call. Cache the result in the `SqlSource`. Use cached schema to validate field names before generating SQL. If a field does not exist in the schema, reject the pushdown and fall back to local (where nil-tolerant field access returns nil as expected).

```clojure
(defn- fetch-schema [connection table-name]
  ;; Returns {"id" :bigint, "name" :varchar, "age" :integer, ...}
  (let [rs (jdbc/execute! connection
             ["SELECT column_name, data_type FROM information_schema.columns
               WHERE table_name = ?" table-name])]
    (into {} (map (fn [row] [(:column_name row) (keyword (:data_type row))]) rs))))
```

### 6.3 Error Handling for Unpushable Functions

**Problem:** `filter _.name = upper-case _.name` -- `upper-case` is a DataTwist stdlib function. The predicate analyzer returns `nil` (not analyzable). The optimizer correctly falls back to local evaluation. No issue.

But what about SQL functions that exist in both DataTwist and SQL? For example, if DataTwist had a `lower` function, should `filter lower _.name = "alice"` push to `WHERE LOWER(name) = 'alice'`?

**Recommendation: No.** Phase 1 should only push predicates where both sides are field accesses or literals. Function calls in predicates always trigger local fallback. This is safe and predictable. A future phase could add a whitelist of functions with known SQL equivalents (`lower`/`LOWER`, `upper`/`UPPER`, `length`/`LENGTH`, etc.).

### 6.4 Caching Pushdown Plans

**Problem:** If the same pipeline shape is evaluated repeatedly (e.g., in a loop or REPL), re-analyzing the AST each time is wasteful.

**Recommendation: Defer.** Predicate analysis is cheap (microseconds). AST walking is not a bottleneck compared to actual query execution. Caching adds complexity (cache invalidation when env bindings change). Do not cache until profiling shows it matters.

### 6.5 Testing Strategy

How to test pushdown without a real database:

1. **Unit tests for predicate analysis:** Feed AST nodes, assert predicate IR output. No database needed. This covers 60% of the logic.

2. **Unit tests for SQL generation:** Feed predicate IR, assert SQL string output. No database needed.

3. **Integration tests with in-memory SQLite:** Use SQLite via JDBC (zero-install, pure-Java driver `org.xerial/sqlite-jdbc`). Create tables, insert test data, run full pipelines, assert results AND generated SQL.

```clojure
(deftest pushdown-filter-to-sql
  (let [db  (create-test-db! {"users" [{"name" "Alice" "age" 25}
                                        {"name" "Bob"   "age" 17}
                                        {"name" "Carol" "age" 30}]})
        src (->SqlSource "users" db [] nil nil nil nil nil false)]
    ;; Test 1: predicate analysis
    (let [pred-ast (parse-predicate "_.age > 18")
          pred-ir  (analyze-predicate pred-ast)]
      (is (= {:op :comparison :comparator ">" :field "age" :value 18} pred-ir)))

    ;; Test 2: SQL generation
    (let [src2 (push-filter src {:op :comparison :comparator ">" :field "age" :value 18})]
      (is (= {:sql "SELECT * FROM users WHERE age > ?" :params [18]}
             (generate-sql src2))))

    ;; Test 3: end-to-end via eval-dt
    (let [result (eval-dt "db |> table \"users\" |> filter _.age > 18 |> collect"
                          {"db" db})]
      (is (= [{"name" "Alice" "age" 25} {"name" "Carol" "age" 30}] result)))))
```

4. **Mock source for capability testing:** Create a mock `PushdownSource` that records which operations were pushed, without executing anything. Use this to test the optimizer logic in isolation.

```clojure
(defrecord MockSource [ops]
  PushdownSource
  (supports-op? [_ op-type] (contains? #{:filter :sort :limit} op-type))
  (push-filter [this pred-ir] (->MockSource (conj ops [:filter pred-ir])))
  (push-sort [this key dir] (->MockSource (conj ops [:sort key dir])))
  (push-limit [this n] (->MockSource (conj ops [:limit n])))
  (push-offset [this n] nil)  ;; deliberately unsupported
  (push-project [this fields] nil)
  (push-count [this] nil)
  (execute [this] (throw (ex-info "MockSource cannot execute" {})))
  (explain-plan [this] (pr-str ops)))
```

### 6.6 Interaction with Lazy Evaluation

The PRD (Section 8, 10) specifies that pipelines are lazy. Pushdown and laziness interact:

- A `SqlSource` with accumulated operations is itself a lazy plan. It has not executed any SQL.
- `execute` is called only when materialization is triggered (`collect`, `count`, `first`, REPL sampling).
- This means the `SqlSource` flows through the pipeline as a value, accumulating operations, until something forces it.

This aligns naturally with the pushdown architecture: each `push-*` call returns a new `SqlSource` (still lazy), and `execute` is the materialization boundary.

**Open question:** Should `tap!` in a pushed pipeline force SQL execution for the pushed prefix? The BDD spec says `tap!` shows a sample without materializing everything. For pushed pipelines, `tap!` could execute the SQL with a `LIMIT 100` appended for sampling purposes, then continue the pipeline with the full (un-limited) source. This needs careful design to avoid executing the full query just for a debug sample.

### 6.7 File Source Pushdown

File sources (CSV, JSON, Parquet) have limited pushdown capabilities:

| Operation | CSV | JSON/JSONL | Parquet |
|-----------|-----|------------|---------|
| filter | No (must scan all rows) | No | Yes (row group filtering via stats) |
| sort | No | No | No |
| limit | Yes (stop reading early) | Yes | Yes |
| project | No (all columns per row) | No | Yes (column pruning) |

For CSV/JSON, the primary optimization is **streaming** (process row by row without loading all into memory) rather than pushdown. For Parquet, pushdown is significant: column pruning avoids reading unused columns from disk, and row group stats allow skipping entire chunks.

The `PushdownSource` protocol handles this naturally: CSV source implements `supports-op?` returning `true` only for `:limit`. Parquet source implements `:limit` and `:project`. The optimizer queries capabilities and pushes only what is supported.

---

## Appendix A: AST Examples for Reference

### A.1 Simple filter predicate: `filter _.age > 18`

The relevant portion of the pipeline step AST (after Instaparse, before simplification):

```clojure
[:PipeAtom
  [:OrExpr
    [:AndExpr
      [:NilCoalesce
        [:NotExpr
          [:CompExpr
            [:InExpr
              [:AddExpr
                [:MulExpr
                  [:UnaryExpr
                    [:FnCallExpr
                      [:FnCall
                        [:CallTarget [:Identifier "filter"]]
                        [:CallArg
                          [:FieldAccess [:Wildcard] [:FieldName "age"]]]]]]]]]
            [:CompOp ">"]
            [:InExpr
              [:AddExpr
                [:MulExpr
                  [:UnaryExpr
                    [:FnCallExpr
                      [:FieldAccess [:Atom [:Integer "18"]]]]]]]]]]]]]]
```

After predicate extraction (replacing FnCall with wildcard expr):

```clojure
[:CompExpr
  [:FieldAccess [:Wildcard] [:FieldName "age"]]
  [:CompOp ">"]
  [:Integer "18"]]
```

After `analyze-predicate`:

```clojure
{:op :comparison :comparator ">" :field "age" :value 18}
```

After `pred-ir->sql`:

```
"age > ?"  with params [18]
```

### A.2 Compound predicate: `filter _.active and _.age >= 18`

After predicate extraction:

```clojure
[:AndExpr
  [:FieldAccess [:Wildcard] [:FieldName "active"]]
  [:CompExpr
    [:FieldAccess [:Wildcard] [:FieldName "age"]]
    [:CompOp ">="]
    [:Integer "18"]]]
```

After `analyze-predicate`:

```clojure
{:op :and
 :clauses [{:op :comparison :comparator "=" :field "active" :value true}
            {:op :comparison :comparator ">=" :field "age" :value 18}]}
```

After `pred-ir->sql`:

```
"(active = ? AND age >= ?)"  with params [true 18]
```

### A.3 Sort key: `sort-by _.name`

The CallArg AST:

```clojure
[:CallArg [:FieldAccess [:Wildcard] [:FieldName "name"]]]
```

After `extract-simple-field`:

```
"name"
```

SQL:

```
ORDER BY name ASC
```
