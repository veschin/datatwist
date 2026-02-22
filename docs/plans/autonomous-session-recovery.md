# Autonomous Session Recovery Plan

## Original User Prompt (paraphrased)
Sort out the backlog and tasks. Actively use agents — do nothing yourself. Let agents research and implement. Push to branch `claude/autonomous-task-delegation-OoJAD`. User won't be around, won't answer questions, won't make decisions. Write down questions instead. If context runs out and session gets squashed, re-read this prompt and return to tasks/backlog.

## Branch
`claude/autonomous-task-delegation-OoJAD`

## Session State (last updated: 2026-02-22)

### Environment
- Java 21 available, Maven available
- `clj` not pre-installed — needs bootstrap via Maven (pom.xml approach)
- Proxy blocks most downloads except Maven Central, GitHub, PyPI, npm

### What's Done
- Read full BACKLOG.md (527 lines, P0-P3 priorities)
- Read PRD.md (language spec)
- Identified actionable items vs blocked items

### Actionable Items (no user decision needed)
1. **stdlib gaps: fill-nil, skip-nil** — straightforward, PRD specifies them
2. **Error reporting improvements** — expected token hints, suppress stack traces, JSON error output, error code registry
3. **Pattern #p Tier 2 type hints** — design done in docs/pattern-phase2-plan.md, ready for BDD+tests+impl
4. **Short-circuit evaluation** — guards and boolean ops in evaluator
5. **autotap! BDD scenarios** — design in docs/autotap-impl-plan.md, write BDD+tests
6. **Backlog reorganization** — clean up, prioritize, add clarity

### Blocked Items (need user decisions — SKIP these)
- Lazy Range syntax (range-from N vs range 1 ..)
- Standard Library: coerce semantics, join key syntax, define vs is [fn->...]
- Config system: file format, layering, project discovery
- Pushdown optimization: blocked on DTPipeline
- REPL: blocked on nREPL research
- GraalVM: research needed but no decisions to make autonomously

### Questions for User (written down, not asked)
1. Should `fill-nil` work on both collections and single values? e.g. `fill-nil 0 [1 nil 3]` vs `fill-nil 0 nil`
2. For `skip-nil` — should it work on objects too (removing nil-valued keys)?
3. Pattern Tier 2 type hints — should `:d` capture as Long/Double automatically, or always String?
4. Short-circuit in boolean ops — should `and`/`or` be short-circuit by default (Clojure style)?
5. For expected token hints in parse errors — should we show the first N expected tokens or all?
6. JSON error output — should this be opt-in via a config flag or always available?

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
