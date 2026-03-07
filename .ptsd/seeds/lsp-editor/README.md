# DataTwist LSP Server

A Language Server Protocol server for DataTwist, implemented in TypeScript.
Provides diagnostics, completion, hover, go-to-definition, and signature help
for any LSP-compatible editor (VS Code, Neovim, Helix, Zed, etc.).

## Implementation Language

TypeScript with the `@vscode/languageserver-node` library. The server uses
Tree-sitter (via `web-tree-sitter` WASM bindings) for all parsing — it does
not invoke the JVM evaluator. This keeps startup time below 200ms.

If GraalVM native-image support is added to the main DataTwist binary, the
server may be reimplemented in Clojure via `lsp4clj` to share code with the
evaluator.

## Supported LSP Capabilities

| Capability | LSP Method | Status |
|---|---|---|
| Diagnostics (parse errors) | `textDocument/publishDiagnostics` | Phase 1 |
| Completion | `textDocument/completion` | Phase 1 |
| Signature Help | `textDocument/signatureHelp` | Phase 1 |
| Hover | `textDocument/hover` | Phase 2 |
| Go-to-Definition | `textDocument/definition` | Phase 2 |
| Find References | `textDocument/references` | Phase 3 |
| Document Symbols | `textDocument/documentSymbol` | Phase 3 |
| Rename | `textDocument/rename` | Phase 3 |

## Architecture

```
Editor (LSP client)
    |
    | JSON-RPC over stdio
    v
LSP Server (TypeScript / Node.js)
    |
    | web-tree-sitter WASM
    v
Tree-sitter CST
    |
    v
Scope analysis -> completions, definitions, diagnostics
```

## Parsing Strategy

On every document change, Tree-sitter re-parses only the changed region (O(edits)
not O(file size)). The resulting Concrete Syntax Tree (CST) is used for:

- **Diagnostics**: walk the CST for `ERROR` nodes; also detect `x = 42` when
  `x is 42` was intended
- **Completion**: examine the CST node at the cursor to determine context
  (after `|>`, after `.`, after `is`, general position)
- **Scope analysis**: walk the CST to build a binding table for
  go-to-definition and references
- **Signature help**: find the enclosing `call_expression` and count arguments

## Stdlib Metadata

Function signatures and docstrings are sourced from
`../../resources/stdlib-metadata.edn`, exported to `src/stdlib.json` at build
time. This provides hover documentation and completion details for all stdlib
functions without evaluating DataTwist code.

## Development

```bash
npm install
npm run build      # TypeScript compilation
npm run test       # Jest test suite
npm run watch      # Incremental build
```

## BDD Specifications

See `../../bdd/11-lsp-editor-support.feature` for the acceptance tests that
define expected LSP behavior.
