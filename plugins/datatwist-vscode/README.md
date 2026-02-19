# datatwist-vscode

VS Code extension for DataTwist. Provides syntax highlighting via a TextMate
grammar (phase 1, works without any external process) and a full LSP client
that connects to the DataTwist LSP server (phase 2).

## Features

### Phase 1: TextMate Grammar (no server required)

- Syntax highlighting for `.dt` files
- Covers: keywords (`is`, `and`, `or`, `not`, `in`, `try`, `catch`, `require`),
  operators (`|>`, `>>`, `<<`, `??`, `->`, arithmetic, comparison), literals
  (strings, numbers, booleans, `nil`), comments (`//`), object keys (`name:`),
  side-effect functions (`log!`), wildcards (`_`)
- File extension association: `.dt`
- Language identifier: `datatwist`

### Phase 2: LSP Client

Connects to the DataTwist LSP server (`../lsp/`) to provide:
- Inline error diagnostics (red underlines for parse errors)
- Autocomplete: stdlib functions, bound variables, object field names
- Hover: function signatures and parameter documentation
- Go-to-definition: jump to `is` binding or function definition
- Signature help: parameter hints while typing function arguments

## File Structure

```
datatwist-vscode/
  package.json                 -- Extension manifest
  syntaxes/
    datatwist.tmLanguage.json  -- TextMate grammar
  language-configuration.json  -- Bracket matching, comment toggle, folding
  src/
    extension.ts               -- LSP client activation, server lifecycle
  test/
    suite/                     -- Jest tests for extension behavior
```

## TextMate Scope Names

| Element | Scope |
|---|---|
| `// comment` | `comment.line.double-slash.datatwist` |
| `"string"` | `string.quoted.double.datatwist` |
| `42`, `3.14` | `constant.numeric.datatwist` |
| `true`, `false` | `constant.language.boolean.datatwist` |
| `nil` | `constant.language.nil.datatwist` |
| `is`, `and`, `or` | `keyword.operator.datatwist` |
| `try`, `catch` | `keyword.control.datatwist` |
| `\|>` | `keyword.operator.pipe.datatwist` |
| `->` | `keyword.operator.arrow.datatwist` |
| `??` | `keyword.operator.nil-coalesce.datatwist` |
| `_` | `variable.language.wildcard.datatwist` |
| `name:` in objects | `entity.name.tag.datatwist` |
| Side-effect fns (`log!`) | `entity.name.function.side-effect.datatwist` |

## Development

```bash
npm install
npm run compile           # TypeScript compilation
npm run test              # Jest tests
code --extensionDevelopmentPath=. &   # Launch extension host
```

## BDD Specifications

Syntax highlighting scenarios: `../../bdd/11-lsp-editor-support.feature`
(Section 1: Syntax Highlighting).
