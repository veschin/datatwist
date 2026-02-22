# Autonomous Session Recovery Plan

## Original User Prompt (paraphrased)
Sort out the backlog and tasks. Actively use agents — do nothing yourself. Let agents research and implement. Push to branch `claude/autonomous-task-delegation-OoJAD`. User won't be around, won't answer questions, won't make decisions. Write down questions instead. If context runs out and session gets squashed, re-read this prompt and return to tasks/backlog.

## Branch
`claude/autonomous-task-delegation-OoJAD`

## Session State (last updated: 2026-02-22, session 2)

### Environment
- Java 21 available, Clojure CLI 1.12.0.1530 available, Maven available
- `clj` IS installed (was not in earlier session — now it is)
- Proxy configured via `JAVA_TOOL_OPTIONS` env var automatically
- See `BOOTSTRAP.md` for full cloud environment setup instructions

### What's Done (across both sessions)

**Session 1 (completed):**
- stdlib: `fill-nil`, `skip-nil` implemented
- Error reporting: expected token hints, stack trace suppression, JSON output, error code registry
- Pattern `#p` Tier 2 type hints: BDD + tests + implementation
- `autotap!` BDD + tests + implementation
- Short-circuit evaluation in guards/boolean ops
- Backlog reorganization and cleanup
- Demo runner improvements
- `median` stdlib function (BDD + test + impl) — already existed

**Session 2 (current):**
- Created `BOOTSTRAP.md` — cloud environment setup, dependency troubleshooting
- Fixed BDD nil comparison specs (4 scenarios): changed from `false` to `nil` to match PRD three-valued logic
- Strengthened error reporting tests: added error code and hint content assertions
- Updated BACKLOG.md with median, BDD coverage analysis results
- BDD/test coverage gap analysis (comprehensive)
- Stdlib gap analysis vs PRD

### Completed Actionable Items
1. ~~**stdlib gaps: fill-nil, skip-nil, median**~~ — all done
2. ~~**Error reporting improvements**~~ — substantially complete (data-aware warnings blocked on DTPipeline)
3. ~~**Pattern #p Tier 2 type hints**~~ — done
4. ~~**Short-circuit evaluation**~~ — done
5. ~~**autotap! BDD scenarios**~~ — done
6. ~~**Backlog reorganization**~~ — done

### Remaining Actionable Items (no user decision needed)
1. **Test quality improvements** — many parse-error tests only check boolean, not error codes/messages
2. **`summarize` stdlib function** — needs design (what fields does the result object contain?)

### Blocked Items (need user decisions — SKIP these)
- Lazy Range syntax (range-from N vs range 1 ..)
- Standard Library: coerce semantics, join key syntax, define vs is [fn->...]
- Config system: file format, layering, project discovery
- Pushdown optimization: blocked on DTPipeline
- Data-aware warnings: blocked on DTPipeline sampling
- REPL: blocked on nREPL research
- GraalVM: research needed but no decisions to make autonomously
- Data source functions (table, query, read-json, etc.): blocked on DTPipeline

### Questions for User (written down, not asked)
1. Should `fill-nil` work on both collections and single values? e.g. `fill-nil 0 [1 nil 3]` vs `fill-nil 0 nil`
2. For `skip-nil` — should it work on objects too (removing nil-valued keys)?
3. Pattern Tier 2 type hints — should `:d` capture as Long/Double automatically, or always String?
4. For expected token hints in parse errors — should we show the first N expected tokens or all?
5. JSON error output — should this be opt-in via a config flag or always available?
6. `summarize` — what fields should it produce? (count, sum, average, min, max, median per numeric column?)

### Test Suite Status
- 761 tests, 1563+ assertions, 0 failures, 0 errors
- BDD coverage: all feature files have corresponding tests
- Feature 8 has ~49 stubs (DTPipeline, data sources, SQL push-down, nREPL)

### Pipeline for Each Task
Design -> Research -> BDD -> Tests (from BDD) -> Implementation

### Recovery Steps
1. Check git status on branch `claude/autonomous-task-delegation-OoJAD`
2. Read this file
3. Read BACKLOG.md for current state
4. Check which tasks were completed (look at git log)
5. Continue with next actionable item
6. Always use agents (Sonnet for implementation, Opus for review)
7. Run tests before committing
8. Push to branch when done
