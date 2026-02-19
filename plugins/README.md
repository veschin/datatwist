# DataTwist Plugins

This directory contains the IDE and editor tooling plugins for DataTwist. The
tooling is split into two independent stacks that can be developed and deployed
separately.

## Architecture

### Stack A — Interactive Evaluation (Emacs-first)

Provides a CIDER-like REPL experience: evaluate DataTwist expressions, see
inline results, inspect data structures.

- `datatwist-nrepl/` — nREPL middleware (JVM, Clojure)
- `datatwist-emacs/` — Emacs major mode with REPL integration (Elisp)

### Stack B — Static Analysis (all editors)

Provides syntax highlighting, autocomplete, go-to-definition, and diagnostics
without needing the JVM evaluator.

- `tree-sitter-datatwist/` — Tree-sitter grammar for incremental parsing
- `lsp/` — LSP server (TypeScript, uses Tree-sitter WASM)
- `datatwist-vscode/` — VS Code extension (TextMate grammar + LSP client)

## Recommended Development Order

1. `datatwist-vscode/` TextMate grammar — instant highlighting in VS Code,
   Sublime, and GitHub (1-2 days)
2. `datatwist-nrepl/` + `datatwist-emacs/` basic mode — eval-at-point, overlays
   (1-2 weeks)
3. `tree-sitter-datatwist/` — incremental parsing, structural editing
   (1-2 weeks)
4. `lsp/` — diagnostics, completion, hover, go-to-definition (2-4 weeks)
5. Full CIDER-like experience — inspector, test runner, debugger (2-4 weeks)

## File Extensions

DataTwist source files use the `.dt` extension.

## Shared Resources

Stdlib metadata (function signatures, docstrings) is defined in
`resources/stdlib-metadata.edn` in the main DataTwist project. Both the nREPL
middleware (for completion/info ops) and the LSP server (for hover/signature
help) consume this file. The LSP server reads a JSON export of this data.
