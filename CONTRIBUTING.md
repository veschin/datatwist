# Contributing to DataTwist

## Getting Started

Requirements: Java 11+, [Clojure CLI](https://clojure.org/guides/install).

```bash
git clone <repo-url>
cd datatwist
make test
```

## Project Structure

```
src/datatwist/parser.clj     -- Parser and evaluator (single source file)
resources/datatwist.grammar   -- Instaparse EBNF grammar
test/datatwist/*_test.clj     -- Tests (one file per language feature)
bdd/*.feature                 -- Gherkin specs (authoritative language spec)
PRD.md                        -- Language specification and design decisions
docs/                         -- Design documents
```

## Running Tests

```bash
make test      # all tests
make lint      # clj-kondo linter

# single namespace
clj -M -e "(require 'clojure.test 'datatwist.literals-test) (clojure.test/run-tests 'datatwist.literals-test)"
```

## Specifications

[PRD.md](PRD.md) is the single source of truth for language semantics. The BDD feature files in `bdd/` define expected behavior. Each `deftest` maps 1:1 to a BDD scenario.

Do not add language features that are not in the PRD.

## Commits

Use descriptive commit messages. Reference the relevant feature area or issue.

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). All participants are expected to uphold it.
