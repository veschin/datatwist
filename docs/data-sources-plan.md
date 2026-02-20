# Data Sources Architecture Plan

**Date:** 2026-02-20
**Status:** Research complete — ready for implementation planning

---

## 1. Executive Summary

DataTwist Phase 3 implements 23 stub functions across two connector families:
file sources (read-csv, read-json, read-jsonl, read-lines, read-parquet, save!)
and database sources (connect, table, query, close!, into!).

The design principle is **lazy by default, bounded by design**: file sources
return lazy sequences backed by open file handles; database sources return lazy
reducible plans via `next.jdbc/plan`. Both families honor DataTwist's existing
`force!` as the sole materializer.

Recommended library stack:

| Purpose         | Library                              | Version  |
|-----------------|--------------------------------------|----------|
| CSV + JSON I/O  | `com.cnuernber/charred`              | 1.038    |
| JDBC            | `com.github.seancorfield/next.jdbc`  | 1.3.1093 |
| Connection pool | `hikari-cp/hikari-cp`                | 4.0.0    |
| PostgreSQL      | `org.postgresql/postgresql`          | 42.7.10  |
| Parquet         | `techascent/tech.ml.dataset`         | 8.002    |

---

## 2. Current State

### deps.edn (current)

```clojure
{:deps {instaparse/instaparse {:mvn/version "1.5.0"}}
 :paths ["src" "test" "resources"]}
```

### stdlib.clj stubs (current)

- `save!` — passthrough no-op (returns data unchanged, writes nothing)
- `read-csv` — throws `DT-C001` "FILE NOT FOUND" if path absent; throws
  "not yet implemented" if file exists
- `connect` — always throws `DT-C002` "CONNECTION ERROR"
- `read-json`, `read-jsonl`, `read-lines`, `read-parquet`, `table`, `query`,
  `close!`, `into!` — not yet defined in stdlib

### errors.clj (current)

- `DT-C001` — FILE NOT FOUND
- `DT-C002` — CONNECTION ERROR

New error codes needed:

- `DT-C003` — FORMAT ERROR (malformed CSV/JSON/Parquet)
- `DT-C004` — CONNECTION CLOSED (operation on closed connection)
- `DT-C005` — WRITE ERROR (save!/into! failure)

---

## 3. Library Recommendations

### 3.1 CSV + JSON: charred

**Recommendation: charred 1.038**

charred is a zero-dependency library (pure JVM, no native code) implementing the
same API as `clojure.data.csv` and `clojure.data.json` with significantly higher
throughput. It is as fast as univocity (CSV) or Jackson (JSON) without requiring
either as a dependency.

Key capabilities relevant to DataTwist:

- `read-csv` — returns a `clojure.data.csv`-compatible lazy seq of vectors
- `read-csv-supplier` — returns a `java.util.function.Supplier` that also
  implements `AutoCloseable`, `clojure.lang.Seqable`, and
  `clojure.lang.IReduce`. This is the primary streaming interface: the file
  handle is owned by the supplier and released when the supplier is closed or
  fully reduced.
- `read-json` — drop-in replacement for `clojure.data.json/read-str` / `read`
- `write-csv` / `write-json` — write to `java.io.Writer` or file path

**Why not clojure.data.csv?**

`clojure.data.csv` is correct and stable but slower. It returns lazy seqs of
string vectors — header-to-map association must be done manually. charred
provides the same interface plus a supplier-based streaming path.

**Why not tablecloth?**

tablecloth (built on `tech.ml.dataset`) is a columnar DataFrame library. Its
CSV reader loads entire files into columnar arrays — incompatible with the
row-by-row lazy streaming model required by the BDD spec. tablecloth is the
right tool for parquet (see 3.4).

**GraalVM note:** charred is zero-dependency (no Jackson, no reflection on
external types). The char-array parsing loop is pure Java/Clojure. GraalVM
compatibility is expected without reflection config, but should be verified with
the tracing agent during native-image builds. No known open issues.

### 3.2 JDBC: next.jdbc

**Recommendation: com.github.seancorfield/next.jdbc 1.3.1093**

next.jdbc is the modern Clojure JDBC wrapper, superseding `clojure.java.jdbc`.

Key capability for DataTwist: **`next.jdbc/plan`**

```clojure
(jdbc/plan ds ["SELECT * FROM users WHERE active = true"])
```

`plan` returns an `IReduceInit` that:
- Does NOT execute SQL until reduced
- Opens a connection, runs the query, and streams rows as an abstraction over
  the mutable `ResultSet` — rows are not materialized as Clojure maps unless
  explicitly requested
- Closes the connection and statement automatically when reduction completes
  (even on short-circuit via `reduced`)
- Is the foundation for DataTwist's lazy DB source semantics

For large PostgreSQL result sets, streaming requires:

```clojure
{:fetch-size 1000
 :concurrency :read-only
 :cursors :close
 :result-type :forward-only}
```

plus a transaction (`jdbc/with-transaction`), because PostgreSQL cursors require
autocommit to be off.

**GraalVM note:** next.jdbc was made GraalVM compatible in PR #178 (GraalVM
22+). It is included in the GraalVM CI test matrix. Still requires the
PostgreSQL driver's reflection config and `-initialize-at-run-time` for certain
`org.postgresql` classes.

### 3.3 Connection Pooling: hikari-cp

**Recommendation: hikari-cp/hikari-cp 4.0.0**

hikari-cp is a Clojure wrapper around HikariCP, the de facto standard
"zero-overhead" JDBC connection pool. HikariCP requires Java 11+.

Integration with next.jdbc is documented and straightforward: create a
`DataSource` via hikari-cp, pass it directly to `jdbc/plan`, `jdbc/execute!`,
etc.

**GraalVM note:** HikariCP uses reflection internally. Native-image builds
require reflection config and `-initialize-at-build-time` for HikariCP classes.
This is acceptable for the JVM target; the native target defers connection
pooling (see Phase 3 scope note).

### 3.4 Parquet: tech.ml.dataset

**Recommendation: techascent/tech.ml.dataset 8.002**

tech.ml.dataset (TMD) is the standard Clojure data science library. Its parquet
support (`tech.v3.libs.parquet` namespace) uses the Apache Parquet Java library
and supports:

- Reading parquet files as a sequence of datasets (row-groups)
- Lazy reading: large parquet files do not hold more memory than necessary;
  the reader is closed on sequence termination

Usage pattern for DataTwist:

```clojure
(require '[tech.v3.libs.parquet :as parquet])
(parquet/parquet->ds-seq "warehouse.parquet")
;; Returns a lazy seq of datasets (one per row-group)
;; Each dataset can be converted to a seq of maps
```

**GraalVM note:** TMD has significant transitive dependencies (Apache Parquet,
Arrow, Hadoop shim). It is NOT compatible with GraalVM native-image in any
practical sense. Parquet support is JVM-only. The native build target should
either omit parquet or throw an explicit "not available in native mode" error.

**Alternative for lightweight parquet:** `parquet-floor` (a minimal Java library
with no Hadoop dependency) could be wrapped for Clojure. However, it is
less mature and lacks row-group streaming. TMD is the recommended path for Phase 3.

### 3.5 PostgreSQL JDBC Driver

**Recommendation: org.postgresql/postgresql 42.7.10** (Maven Central, not Clojars)

Standard pgJDBC driver. Works with next.jdbc directly. GraalVM requires
`-initialize-at-run-time` for the `org.postgresql.Driver` class and reflection
config for several internal classes.

---

## 4. deps.edn Additions

```clojure
{:deps
 {instaparse/instaparse                    {:mvn/version "1.5.0"}

  ;; Phase 3a: File sources (CSV + JSON)
  com.cnuernber/charred                    {:mvn/version "1.038"}

  ;; Phase 3b: Database sources
  com.github.seancorfield/next.jdbc        {:mvn/version "1.3.1093"}
  hikari-cp/hikari-cp                      {:mvn/version "4.0.0"}
  org.postgresql/postgresql                {:mvn/version "42.7.10"}

  ;; Phase 3c: Parquet (JVM-only, large transitive deps)
  techascent/tech.ml.dataset               {:mvn/version "8.002"}}

 :paths ["src" "test" "resources"]

 ;; Optional: isolate heavy parquet deps behind an alias
 :aliases
 {:parquet
  {:extra-deps
   {techascent/tech.ml.dataset {:mvn/version "8.002"}}}}}
```

**Note on alias strategy:** tech.ml.dataset pulls in Apache Parquet, Hadoop
filesystem shims, and Arrow, adding ~50MB of transitive jars. Consider making
it an optional alias activated only when parquet features are needed, rather than
a default dependency. File and database sources (charred + next.jdbc) are
lightweight by comparison.

---

## 5. Architecture per Connector Type

### 5.1 File Source Architecture

All file sources follow the same pattern:

```
DataTwist read-csv "path" opts
    │
    ▼
stdlib/read-csv fn
    │
    ├─ validate path exists (throw DT-C001 if not)
    ├─ open BufferedReader (charred handles this internally)
    ├─ return LazySourceSeq record
    │      {:type :file-source
    │       :path path
    │       :opts opts
    │       :open-fn <fn that reopens reader>}
    │
    ▼ (when pipeline is reduced / force! called)
charred/read-csv-supplier
    │
    ├─ streams rows lazily via java.util.function.Supplier
    ├─ AutoCloseable: file handle closed on completion or short-circuit
    └─ IReduce: pipeline steps applied as reduction (no intermediate seqs)
```

**LazySourceSeq record** (proposed Clojure type):

```clojure
(defrecord LazySourceSeq [path open-fn opts])
```

Implement `clojure.lang.IReduceInit` so that pipeline steps composed via
transducers can reduce directly into the supplier without realizing intermediate
seqs. Implement `clojure.lang.Seqable` (call `open-fn` to get a fresh seq) for
compatibility with existing Clojure functions.

**Re-open semantics:** The BDD spec requires that re-materializing the same
source re-reads the file:

```
data is read-csv "f.csv"
a is data |> filter _.x |> force!
b is data |> filter _.y |> force!
```

Both `a` and `b` must succeed. This means the `LazySourceSeq` does NOT cache
the reader — `open-fn` opens a fresh `BufferedReader` each time `seq` or
`reduce` is called on it. charred's `read-csv-supplier` is `AutoCloseable`;
wrapping it in a `with-open` inside `open-fn` ensures the handle is closed
whether reduction succeeds or throws.

### 5.2 read-csv

**BDD scenarios covered:** 3 (basic, options, no-header)

```clojure
(defn impl-read-csv [path & [opts]]
  (let [f (java.io.File. (str path))]
    (when-not (.exists f)
      (dt-error {:code "DT-C001" :message (str "File not found: " path)}))
    (->LazySourceSeq
      path
      (fn []
        (let [sep   (get opts :separator \,)
              hdr?  (get opts :header true)
              enc   (get opts :encoding "UTF-8")]
          (charred/read-csv-supplier
            path
            {:separator sep
             :header? hdr?
             :charset enc
             :close-reader? true})))
      opts)))
```

- With `header: true` (default) → each row is a map `{:col-name val ...}`.
  charred handles header extraction automatically when `:header?` is true.
- With `header: false` → each row is a vector of strings.
- `:separator "\t"` for TSV files.

### 5.3 read-json

**BDD scenarios covered:** 1

```clojure
(defn impl-read-json [path]
  (let [f (java.io.File. (str path))]
    (when-not (.exists f)
      (dt-error {:code "DT-C001" :message (str "File not found: " path)}))
    ;; charred/read-json returns the parsed value immediately.
    ;; For arrays: wrap in a lazy-seq-compatible form.
    ;; For objects: return as a single map.
    (let [v (charred/read-json path {:key-fn keyword})]
      (if (sequential? v)
        ;; BDD: "if array, data is a lazy sequence of elements"
        ;; For JSON arrays the file is fully parsed; return as eduction for
        ;; consistency. True streaming JSON requires JSONL — see read-jsonl.
        (eduction identity v)
        v))))
```

**Note on laziness:** JSON arrays require the entire file to be parsed before
the first element can be yielded (the parser must scan to confirm array vs
object structure). True streaming JSON at the element level requires JSONL.
The BDD spec says "lazy sequence of elements" for arrays — this is satisfied by
returning an `eduction` (lazy, single-pass) over the already-parsed vector. The
file is not held open after parse. For truly large JSON arrays, users should
prefer JSONL.

### 5.4 read-jsonl

**BDD scenarios covered:** 1

JSONL (newline-delimited JSON) is the correct format for streaming. Each line is
an independent JSON object, parseable without looking ahead.

```clojure
(defn impl-read-jsonl [path]
  (let [f (java.io.File. (str path))]
    (when-not (.exists f)
      (dt-error {:code "DT-C001" :message (str "File not found: " path)}))
    (->LazySourceSeq
      path
      (fn []
        ;; Open a BufferedReader; parse each line as JSON on demand.
        (let [rdr (java.io.BufferedReader.
                    (java.io.InputStreamReader.
                      (java.io.FileInputStream. path) "UTF-8"))]
          (map #(charred/read-json-str % {:key-fn keyword})
               (line-seq rdr))))
      {})))
```

The `line-seq` returns a lazy seq of lines; each line is parsed independently.
The `BufferedReader` must be closed after consumption. The `LazySourceSeq`
wrapper handles re-open semantics (a fresh reader per materialization).

**Resource management concern:** `line-seq` over an open reader will leak the
handle if the seq is not fully consumed. Use a custom `IReduceInit` that wraps
the reader in `with-open` and reduces line-by-line, or use charred's
`read-json-supplier` if available. Alternatively, implement `AutoCloseable` on
the returned seq via a custom type.

### 5.5 read-lines (read-text)

**BDD scenarios covered:** 1 (called `read-lines` in BDD, `read-text` in PRD overview)

```clojure
(defn impl-read-lines [path]
  (let [f (java.io.File. (str path))]
    (when-not (.exists f)
      (dt-error {:code "DT-C001" :message (str "File not found: " path)}))
    (->LazySourceSeq
      path
      (fn []
        (line-seq (java.io.BufferedReader.
                    (java.io.FileReader. (str path)))))
      {})))
```

Same resource management concern as read-jsonl — see note in 5.4.

### 5.6 read-parquet

**BDD scenarios covered:** 1

```clojure
(defn impl-read-parquet [path]
  (let [f (java.io.File. (str path))]
    (when-not (.exists f)
      (dt-error {:code "DT-C001" :message (str "File not found: " path)}))
    (->LazySourceSeq
      path
      (fn []
        (require 'tech.v3.libs.parquet)
        ;; parquet->ds-seq returns a lazy seq of datasets (row-groups).
        ;; Convert each dataset to a seq of maps for DataTwist compatibility.
        (let [ds-seq ((resolve 'tech.v3.libs.parquet/parquet->ds-seq) path)]
          (mapcat (fn [ds]
                    ((resolve 'tech.v3.dataset/mapseq-reader) ds))
                  ds-seq)))
      {})))
```

Uses `require` + `resolve` at call time so that the main DataTwist jar can run
without TMD on the classpath (parquet is optional). This breaks GraalVM
compatibility for parquet but preserves it for all other features.

### 5.7 save!

**BDD scenarios covered:** 2 (passthrough + multi-format)

`save!` is a side-effect function (returns its first argument — passthrough
semantics). It materializes the pipeline by reducing it and writes the result.

```clojure
(defn impl-save! [data path]
  ;; Materialize (force the lazy seq into a vector)
  (let [rows (if (sequential? data) (vec data) [data])
        ext  (-> (str path)
                 (clojure.string/lower-case)
                 (clojure.string/split #"\.")
                 last)]
    (case ext
      "csv"     (write-csv-file path rows)
      "json"    (write-json-file path rows)
      "parquet" (write-parquet-file path rows)
      "txt"     (write-text-file path rows)
      (throw (dt-error {:code "DT-C005"
                        :message (str "Unsupported file extension: " ext)}))))
  data) ; passthrough
```

For streaming save (the "10gb-file.csv" BDD scenario), `save!` must not
materialize the full input. When `data` is a `LazySourceSeq`, use charred's
`write-csv` with a reducing function that writes rows one at a time:

```clojure
;; Streaming CSV save — no full materialization
(with-open [w (java.io.BufferedWriter. (java.io.FileWriter. path))]
  (reduce (fn [_ row] (charred/write-csv-row w row) nil)
          nil
          data))
```

### 5.8 Database Source Architecture

```
connect "postgres://host/db" opts
    │
    ▼
{:dt/type :db-connection
 :datasource <HikariCP DataSource>
 :uri uri
 :opts opts
 :closed? (atom false)}

table conn "users"
    │
    ▼
{:dt/type :db-table
 :connection conn
 :table "users"
 :plan-opts {}}
 ;; No SQL executed yet

query conn "SELECT ..." params
    │
    ▼
{:dt/type :db-query
 :connection conn
 :sql "SELECT ..."
 :params params}
 ;; No SQL executed yet

force! (terminal op)
    │
    ▼
next.jdbc/plan ds sql opts
    │  (IReduceInit — streams rows from DB)
    └─ into [] → vector of maps
```

### 5.9 connect

```clojure
(defn impl-connect [uri & [opts]]
  (try
    (let [pool-size (get opts :pool-size 10)
          db-spec   (merge {:jdbcUrl uri
                             :maximumPoolSize pool-size}
                           (select-keys opts [:user :password :username]))
          ds        (hikari-cp/make-datasource db-spec)]
      {:dt/type   :db-connection
       :datasource ds
       :uri        uri
       :opts       opts
       :closed?    (atom false)})
    (catch Exception e
      (dt-error {:code "DT-C002"
                 :message (str "Connection failed: " uri)
                 :hint (.getMessage e)}))))
```

Connection is validated immediately (hikari-cp attempts a test connection on
datasource creation) to satisfy the BDD scenario "connection failure raises a
descriptive error".

### 5.10 table

```clojure
(defn impl-table [conn table-name]
  (when (:closed? conn)
    (when @(:closed? conn)
      (dt-error {:code "DT-C004" :message "Connection is closed."})))
  {:dt/type    :db-table
   :connection conn
   :table      (str table-name)
   :plan-opts  {}})
```

Returns a lazy plan descriptor. No SQL is executed. The pipeline evaluator
treats `:db-table` and `:db-query` maps as reducible sources — on `force!`,
they are reduced via `jdbc/plan`.

### 5.11 query

```clojure
(defn impl-query [conn sql & [params]]
  (when @(:closed? conn)
    (dt-error {:code "DT-C004" :message "Connection is closed."}))
  {:dt/type    :db-query
   :connection conn
   :sql        (str sql)
   :params     (or params [])})
```

Parameterized queries pass `params` as the second element of the JDBC vector:
`[sql param1 param2 ...]`.

### 5.12 close!

```clojure
(defn impl-close! [conn]
  (when-not @(:closed? conn)
    (.close (:datasource conn))
    (reset! (:closed? conn) true))
  conn) ; passthrough (side-effect fn returns first arg)
```

### 5.13 into!

```clojure
(defn impl-into! [data conn table-name]
  (when @(:closed? conn)
    (dt-error {:code "DT-C004" :message "Connection is closed."}))
  (let [rows (if (sequential? data) data [data])
        ds   (:datasource conn)]
    (jdbc/with-transaction [tx ds]
      (doseq [row rows]
        (jdbc/execute! tx
          (sql/insert! table-name row)))))
  data) ; passthrough
```

---

## 6. Lazy Streaming Design

### 6.1 Resource Management Contract

The fundamental tension: Clojure lazy seqs are garbage-collected non-deterministically.
A `BufferedReader` held inside a lazy seq will not be closed until the seq is
fully consumed or GC'd (which may be never for leaked heads).

**Strategy: use a custom reducible, not a raw lazy-seq**

Implement `LazySourceSeq` (see 5.1) as a type that implements:

- `clojure.lang.IReduceInit` — primary interface. The `reduce` method opens the
  resource, processes rows, and closes the resource in a `try/finally` block.
  This guarantees closure whether reduction completes, short-circuits, or throws.
- `clojure.lang.Seqable` — secondary interface for compatibility with `first`,
  `take`, `count`, etc. Opens a resource, wraps in `with-open`, realizes the
  seq via `doall` (bounded by `dtw.MAX_COLLECT_ROWS` or similar), and closes.

```clojure
(deftype LazySourceSeq [path open-fn opts]
  clojure.lang.IReduceInit
  (reduce [_ f init]
    (let [supplier (open-fn)]
      (try
        (loop [acc init]
          (let [row (.get supplier)]
            (if (nil? row)
              acc
              (let [result (f acc row)]
                (if (reduced? result)
                  @result
                  (recur result))))))
        (finally
          (.close supplier)))))

  clojure.lang.Seqable
  (seq [_]
    ;; Opens a fresh resource and returns a bounded realized seq.
    ;; Used by force! and first/take/etc.
    (with-open [supplier (open-fn)]
      (doall (take (config/get-config :MAX_COLLECT_ROWS 100000)
                   (seq supplier))))))
```

### 6.2 Streaming Pipeline (the 10GB scenario)

BDD scenario:
```
read-csv "10gb-file.csv"
|> filter _.region = "EU"
|> map {id: _.id value: _.amount * _.rate}
|> save! "eu-values.csv"
```

This pipeline should never hold more than a bounded buffer of rows in memory.

Execution path:
1. `read-csv` returns a `LazySourceSeq` (no file opened yet)
2. `filter` + `map` wrap the source in a transducer composition
3. `save!` reduces the composed transducer over the source via `IReduceInit`
4. The `LazySourceSeq.reduce` method opens one `BufferedReader`, feeds rows
   through the transducer one at a time (charred's supplier yields one row at
   a time), and writes each output row to the output file
5. Memory peak: O(1) rows in flight (one row per reduce step)

**Implementation requirement:** the pipeline evaluator must detect when the
last step is `save!` or `into!` and use transducer fusion rather than
materializing intermediate results.

### 6.3 Database Streaming

For PostgreSQL, server-side cursors must be enabled:

```clojure
(defn db-source-reduce [plan-desc f init]
  (let [{:keys [connection sql params plan-opts]} plan-desc
        ds (:datasource connection)]
    (jdbc/with-transaction [tx ds]
      (reduce f init
        (jdbc/plan tx
          (into [sql] params)
          (merge {:fetch-size 1000
                  :concurrency :read-only
                  :cursors :close
                  :result-type :forward-only}
                 plan-opts))))))
```

Without `:fetch-size` + transaction, PostgreSQL loads the entire result set
into the JDBC driver's client-side buffer before the first row is returned.
With server-side cursors, rows are streamed in batches of `fetch-size`.

---

## 7. GraalVM Compatibility Notes

See `docs/graalvm-research.md` for the full GraalVM analysis. Summary for data
sources:

| Component            | GraalVM Compatible? | Notes                                                              |
|----------------------|---------------------|--------------------------------------------------------------------|
| charred (CSV/JSON)   | Likely yes          | Zero dependencies, pure char-array parsing. Needs tracing agent verification. |
| next.jdbc            | Yes (since PR #178) | Added to GraalVM CI matrix. GraalVM 22+.                          |
| hikari-cp            | Partial             | Requires reflection config for HikariCP internal classes.          |
| org.postgresql       | Partial             | Requires `-initialize-at-run-time` for several driver classes.     |
| tech.ml.dataset      | No                  | Apache Parquet + Hadoop shim are incompatible with native-image.   |

**Consequence:** the native-image target of DataTwist should:
1. Include charred (CSV/JSON), next.jdbc, hikari-cp, postgresql
2. Exclude tech.ml.dataset (parquet) — `read-parquet` and `save! "*.parquet"`
   throw a structured `DT-C006 "FEATURE UNAVAILABLE IN NATIVE MODE"` error
3. Generate reflection/resource configs via the tracing agent on a test run
   covering connect, read-csv, read-json, and save! operations

---

## 8. Implementation Phases

### Phase 3a: File Sources — CSV + JSON (unlocks 7 stubs)

**Unlocked stubs:**
1. `read-csv-file-as-lazy-sequence-of-maps`
2. `read-csv-with-explicit-options`
3. `read-csv-without-headers-produces-vectors-instead-of-maps`
4. `read-json-file-as-lazy-sequence-array-or-single-map-object`
5. `read-json-lines-newline-delimited-json-as-lazy-sequence-of-maps`
6. `read-text-file-as-lazy-sequence-of-lines`
7. `streaming-pipeline-processes-large-file-without-unbounded-memory-use`

**Work items:**
- Add charred to deps.edn
- Implement `LazySourceSeq` type in new `src/datatwist/sources.clj`
- Implement `read-csv`, `read-jsonl`, `read-lines` in `sources.clj`
- Implement `read-json` (eager parse, eduction wrapper) in `sources.clj`
- Register all functions in `stdlib.clj` (replace stubs)
- Add `DT-C003` (FORMAT ERROR) to `errors.clj`
- Add error codes `DT-C003`, `DT-C004`, `DT-C005` to `errors.clj`
- Write fixture CSV/JSON/JSONL files to `test/resources/` for test use
- Implement tests for all 7 stubs in `lazy_eval_test.clj`

### Phase 3b: File Output — save! (unlocks 3 stubs)

**Unlocked stubs:**
8. `save-bang-writes-pipeline-output-to-a-file-and-returns-data-passthrough`
9. `save-bang-supports-multiple-file-formats-by-extension`
10. `lazy-pipeline-reuse-file-sources-re-open-on-each-materialization`

**Work items:**
- Implement `write-csv-file`, `write-json-file`, `write-text-file` using charred
- Implement streaming `save!` (transducer path for `LazySourceSeq` inputs)
- Implement `save!` extension dispatch (`.csv`, `.json`, `.txt`)
- Register `save!` replacement in `stdlib.clj` (replace passthrough stub)
- Implement tests for 3 stubs

### Phase 3c: Database Sources — connect/table/query/close!/into! (unlocks 8 stubs)

**Unlocked stubs:**
11. `connect-to-a-postgresql-database`
12. `connect-with-explicit-credentials`
13. `reference-a-database-table-as-a-lazy-data-source`
14. `pipeline-over-a-database-table-is-lazy-until-a-terminal-operation`
15. `raw-sql-query-as-a-lazy-data-source`
16. `database-query-with-parameterized-sql`
17. `table-source-materializes-to-a-full-scan-on-force-bang`
18. `close-bang-explicitly-releases-a-database-connection`
19. `into-bang-inserts-pipeline-output-into-a-database-table-and-returns-data-passthrough`

**Work items:**
- Add next.jdbc, hikari-cp, org.postgresql to deps.edn
- Implement `connect`, `table`, `query`, `close!`, `into!` in `sources.clj`
- Implement `db-source-reduce` (streaming PostgreSQL plan)
- Make `LazySourceSeq` handle `:db-table` and `:db-query` map sources
- Add `DT-C004` (CONNECTION CLOSED) and `DT-C005` (WRITE ERROR) to `errors.clj`
- Integration tests require a running PostgreSQL — use Docker or testcontainers
- Register all functions in `stdlib.clj`
- Implement tests for 8 stubs (DB integration tests tagged `^:integration`)

### Phase 3d: Error Scenarios + Parquet (unlocks 5 stubs)

**Unlocked stubs:**
20. `connection-failure-raises-a-descriptive-error`
21. `file-not-found-raises-an-error-when-pipeline-is-first-evaluated`
22. `non-existent-field-access-in-a-pipeline-is-nil-tolerant`
23. `read-parquet-file-as-lazy-columnar-source`
    (+ `save-bang-supports-multiple-file-formats-by-extension` for `.parquet`)

**Work items:**
- Error scenario tests for DT-C001, DT-C002, DT-C004 (already partially
  implemented by existing stubs — need real connection attempt)
- Nil-tolerance test is in the evaluator (nil field access) — verify existing
  nil-tolerant field access works through lazy seq rows (likely already works)
- Add tech.ml.dataset (or alias) to deps.edn
- Implement `read-parquet` using `tech.v3.libs.parquet/parquet->ds-seq`
- Implement parquet write in `save!` dispatch
- Implement tests for 5 stubs

### Phase 4+: SQL Push-Down (7 additional stubs — separate design required)

The push-down stubs (`filter-pushes-down-to-sql-where-clause`, etc.) require
a query planner that inspects DataTwist AST nodes and translates them to SQL
fragments. This is a significant architectural feature covered in
`docs/pushdown-design.md`. It is out of scope for Phase 3.

---

## 9. New Source File: `src/datatwist/sources.clj`

All data source implementations should live in a new namespace separate from
`stdlib.clj`, which is already large (435 lines). The namespace structure:

```clojure
(ns datatwist.sources
  (:require [charred.api :as charred]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [hikari-cp.core :as hikari-cp]
            [datatwist.errors :refer [dt-error]]
            [datatwist.config :as config])
  (:import [java.io BufferedReader InputStreamReader FileInputStream]))
```

`stdlib.clj` registers the public functions:

```clojure
;; In stdlib.clj default-env additions:
"read-csv"   sources/impl-read-csv
"read-json"  sources/impl-read-json
"read-jsonl" sources/impl-read-jsonl
"read-lines" sources/impl-read-lines
"read-parquet" sources/impl-read-parquet
"save!"      sources/impl-save!
"connect"    sources/impl-connect
"table"      sources/impl-table
"query"      sources/impl-query
"close!"     sources/impl-close!
"into!"      sources/impl-into!
```

---

## 10. Test Infrastructure for Phase 3

### Fixture files needed (test/resources/)

```
test/resources/
  data/
    simple.csv          (5 rows, header: name,age,active)
    tsv.tsv             (tab-separated, 3 rows)
    no-header.csv       (3 rows, no header)
    array.json          (JSON array of 3 objects)
    object.json         (JSON object)
    events.jsonl        (3 JSON objects, one per line)
    logfile.txt         (5 lines of text)
    warehouse.parquet   (small parquet file, 3 rows)
    large.csv           (generated: 100k rows for streaming test)
```

### Integration test tags

DB tests that require a live PostgreSQL instance should be tagged:

```clojure
(deftest ^:integration connect-to-a-postgresql-database ...)
```

Run with: `clj -M -e "(require ...) (clojure.test/run-tests ...)" -D:integration`
(or a separate test alias). The standard `make test` should skip `^:integration`
tests to keep CI fast without a DB.

---

## 11. Summary Table: Stubs Unlocked per Phase

| Phase  | Stubs Unlocked | Requires                          |
|--------|----------------|-----------------------------------|
| 3a     | 7              | charred, LazySourceSeq            |
| 3b     | 3              | charred write, streaming save!    |
| 3c     | 9              | next.jdbc, hikari-cp, PostgreSQL  |
| 3d     | 4              | tech.ml.dataset (optional), error scenarios |
| 4+     | 7              | SQL push-down planner (separate design) |
| Total  | 23 + 7 pushdown | —                               |

---

Sources:
- [charred GitHub](https://github.com/cnuernber/charred)
- [charred Clojars (1.038)](https://clojars.org/com.cnuernber/charred)
- [charred API docs](https://cnuernber.github.io/charred/charred.api.html)
- [next.jdbc GitHub](https://github.com/seancorfield/next-jdbc)
- [next.jdbc getting started](https://cljdoc.org/d/com.github.seancorfield/next.jdbc/1.3.883/doc/getting-started)
- [next.jdbc Clojars (1.3.1093)](https://clojars.org/com.github.seancorfield/next.jdbc)
- [hikari-cp Clojars (4.0.0)](https://clojars.org/hikari-cp)
- [tech.ml.dataset GitHub](https://github.com/techascent/tech.ml.dataset)
- [tech.v3.libs.parquet docs](https://techascent.github.io/tech.ml.dataset/tech.v3.libs.parquet.html)
- [org.postgresql Maven Central](https://central.sonatype.com/artifact/org.postgresql/postgresql)
- [PostgreSQL JDBC streaming cursors](https://jdbc.postgresql.org/documentation/query/)
- [clj-easy/graalvm-clojure](https://github.com/clj-easy/graalvm-clojure)
- [JUXT: JSON in Clojure](https://www.juxt.pro/blog/json-in-clojure/)
- [jsonista GraalVM issue #51](https://github.com/metosin/jsonista/issues/51)
