# TASKS.md — Active Work Tracker

Pipeline: **Design → Research → BDD → Tests → Implementation**

Legend: `R` research, `I` implementation, `D` design decision needed, `B` backlog (blocked)

---

## Sprint 1: Quick Wins + Research

### I-01: WARNINGS_AS_ERRORS `I`
- **Status**: ready to implement
- **Files**: `src/datatwist/errors.clj`, `src/datatwist/evaluator.clj`, `test/datatwist/error_reporting_test.clj`
- **Stubs converted**: 1
- **Plan**: Add `*warnings-as-errors*` dynamic var in errors.clj. In `dt-warning`, check var — if true, throw via `dt-error` instead of returning map. In evaluator, bind var when `WARNINGS_AS_ERRORS` is set. Update stub test with real assertions.
- **Depends on**: nothing

### I-02: tap! output modes (bare/labeled/lambda) `I`
- **Status**: ready to implement
- **Files**: `src/datatwist/stdlib.clj`, `test/datatwist/lazy_eval_test.clj`
- **Stubs converted**: 5 (bare, labeled, lambda, micro-sample, multi-call)
- **Plan**: Refactor `tap!` in stdlib. Bare mode: print `--- tap! ---` header + sample (first N rows). Labeled mode: `tap! "label"` prints `--- label ---` + sample. Lambda mode: `tap! [d -> expr]` applies fn to sample for display, returns original data. Output captured via `*out*` binding in tests.
- **Depends on**: nothing (use hardcoded sample size 100 until config system exists)

### I-03: range infinite semantics `D`
- **Status**: needs design decision
- **Files**: `src/datatwist/stdlib.clj`, `test/datatwist/lazy_eval_test.clj`
- **Stubs converted**: 1
- **Conflict**: BDD says `range 5` = `[0 1 2 3 4]` (finite) AND `range 1` = infinite from 1. Same arity, contradictory semantics.
- **Options**: (a) `range N` always finite 0..N, add `range-from N` for infinite; (b) `range N` = infinite from N, `range 0 N` = finite; (c) heuristic based on N value (bad idea)
- **Action**: user decision required before implementation

### R-01: DTPipeline record architecture `R`
- **Status**: research
- **Deliverable**: implementation plan in `docs/dtpipeline-impl-plan.md`
- **Questions to answer**:
  - defrecord vs deftype? What protocols to implement (ISeq, IReduce, Counted)?
  - How does eval-pipeline change? Return DTPipeline instead of raw lazy seq?
  - How do terminal ops (count, first, reduce, force!) work on DTPipeline?
  - How does sample caching work per step?
  - What breaks in existing tests when pipeline returns DTPipeline not LazySeq?
- **Unlocks**: 9 stubs (3 laziness fundamentals + 6 introspection)

### R-02: Config system design `R`
- **Status**: research
- **Deliverable**: implementation plan in `docs/config-impl-plan.md`
- **Questions to answer**:
  - Grammar changes for `dtw.CONSTANT` dot-access and `set! dtw.KEY value`
  - Where to store config: dynamic var? atom? Both?
  - Which constants: SAMPLE_SIZE (100), DESCRIBE_SAMPLE_SIZE (1000), PRINT_WIDTH (120), MAX_COLLECT_ROWS (nil)
  - How does `set!` interact with evaluator (special form or stdlib function)?
  - How does MAX_COLLECT_ROWS enforcement work in force!?
- **Unlocks**: 8 stubs

### R-03: Pattern destructuring `#p` feasibility `R`
- **Status**: research
- **Deliverable**: feasibility report + implementation plan in `docs/pattern-destructuring-plan.md`
- **Scope**: 59 stub tests (entire pattern_destructuring_test.clj)
- **Questions to answer**:
  - Grammar additions for `#p"..."` reader macro syntax
  - Constraint mini-language inside `{var: ...}` placeholders
  - Compilation to java.util.regex.Pattern
  - Integration with guard/pattern matching
  - Estimated effort (this is a full new language feature)

---

## Sprint 2: Core Infrastructure (after Sprint 1 research completes)

### I-04: Config system implementation `I`
- **Status**: blocked by R-02
- **Files**: new `src/datatwist/config.clj`, grammar, evaluator, stdlib, tests
- **Stubs converted**: 8
- **Depends on**: R-02

### I-05: DTPipeline record implementation `I`
- **Status**: blocked by R-01
- **Files**: new `src/datatwist/pipeline.clj`, evaluator.clj, stdlib.clj, tests
- **Stubs converted**: 9 (3 laziness + 6 introspection)
- **Depends on**: R-01

### I-06: autotap! AST transformation `I`
- **Status**: blocked by I-05
- **Files**: evaluator.clj, stdlib.clj, tests
- **Stubs converted**: 3
- **Plan**: autotap! wraps every subsequent pipeline step with tap!. Needs evaluator-level transformation of pipeline AST nodes.
- **Depends on**: I-05 (DTPipeline)

### I-07: force! + save! integration `I`
- **Status**: blocked by data sources
- **Stubs converted**: 1
- **Depends on**: data source infrastructure (Sprint 3+)

---

## Sprint 3: Pattern Destructuring (after R-03)

### I-08: `#p` grammar + parser `I`
- **Status**: blocked by R-03
- **Files**: `resources/datatwist.grammar`, `src/datatwist/parser.clj`
- **Depends on**: R-03

### I-09: `#p` evaluator + stdlib `I`
- **Status**: blocked by I-08
- **Files**: `src/datatwist/evaluator.clj`, `src/datatwist/stdlib.clj`
- **Stubs converted**: 59
- **Depends on**: I-08

---

## Backlog (separate project phase, moved from stubs)

These stubs require external dependencies or infrastructure that doesn't exist yet. Tests remain as stubs until the prerequisite infrastructure is built.

### B-01: Database sources (8 stubs)
- connect, credentials, table reference, lazy DB pipeline, raw SQL, parameterized SQL, full scan, close!
- **Blocked by**: JDBC driver, HikariCP, connector interface design
- **Ref**: BACKLOG.md → Module System & Connectors

### B-02: File sources (15 stubs)
- read-csv (3 variants), read-json, read-jsonl, read-text, read-parquet, save! (2 variants), into!, streaming, file reuse, connection failure, file-not-found, nil-tolerant field access
- **Blocked by**: CSV/JSON/Parquet libraries, real file I/O
- **Ref**: BACKLOG.md → Module System & Connectors

### B-03: SQL pushdown (9 stubs)
- filter→WHERE, sort→ORDER BY, take→LIMIT, map→SELECT, count→COUNT, pushdown boundary, explain SQL, schema metadata, nREPL inspect
- **Blocked by**: B-01 (database sources), pushdown optimizer
- **Ref**: BACKLOG.md → Pushdown Optimization

### B-04: REPL display (6 stubs)
- Auto-sample preview, tabular format, full result for small collections, scalar display, first-N strategy, no LazySeq references
- **Blocked by**: REPL infrastructure (doesn't exist)
- **Ref**: BACKLOG.md → REPL & Developer Experience

---

## Stub Inventory

| Area | Total stubs | Sprint | Blocker |
|------|------------|--------|---------|
| WARNINGS_AS_ERRORS | 1 | S1 | none |
| tap! modes | 5 | S1 | none |
| range infinite | 1 | S1 | design decision |
| DTPipeline | 9 | S2 | R-01 research |
| Config system | 8 | S2 | R-02 research |
| autotap! | 3 | S2 | I-05 DTPipeline |
| force!+save! | 1 | S2+ | data sources |
| Pattern `#p` | 59 | S3 | R-03 research |
| DB sources | 8 | Backlog | JDBC/HikariCP |
| File sources | 15 | Backlog | CSV/JSON/Parquet libs |
| SQL pushdown | 9 | Backlog | DB sources |
| REPL display | 6 | Backlog | REPL infra |
| **Total** | **125** | | |
