# tree-sitter-datatwist

Tree-sitter grammar for the DataTwist language. Provides incremental, error-
tolerant parsing for editor integrations (Emacs 29+, Neovim, Helix, Zed, VS
Code via the LSP server).

## What Tree-sitter Provides

- **Incremental parsing**: only re-parses changed regions on each keystroke
- **Error recovery**: partial parses with `ERROR` nodes when code is incomplete
- **CST (Concrete Syntax Tree)**: named nodes for every construct in the
  DataTwist language
- **Syntax highlighting**: via `queries/highlights.scm`
- **Indentation**: via `queries/indents.scm`
- **Text objects**: via `queries/textobjects.scm` (structural editing)
- **Folding**: via `queries/folds.scm`

## Grammar Coverage

The grammar covers all DataTwist constructs from the Instaparse EBNF grammar
(`resources/datatwist.grammar`):

| Construct | Tree-sitter node |
|---|---|
| `name is value` | `binding` |
| `data \|> f args` | `pipeline` |
| `\|> f args` | `sourceless_pipeline` |
| `f >> g` | `compose` |
| `\| pattern -> result` | `guard_block` / `guard_arm` |
| `[params -> body]` | `function_definition` |
| `[a -> b, c -> d]` | `multi_arity_function` |
| `f arg1 arg2` | `call_expression` |
| `recur args` | `recur_expression` |
| `{key: value}` | `object` |
| `[1 2 3]` | `list` |
| `obj.field` | `field_access` |
| `try ... catch` | `try_expression` |
| `require mod as alias` | `require_statement` |
| Destructuring | `object_pattern` / `list_pattern` |

## Key Grammar Challenges

### Whitespace: `__I` (inline whitespace)

Instaparse's `__I` rule prevents function call arguments from spanning line
boundaries. The Tree-sitter grammar handles this via an **external scanner** in
C (`src/scanner.c`) that emits a `_newline` token at line boundaries unless the
next line starts with `|>` (pipeline continuation).

### Ambiguity: `[` starts both lists and functions

`[1 2 3]` is a list; `[x -> x + 1]` is a function. Tree-sitter's GLR mode
forks the parse when it sees `[` and commits when `->` is (or is not) found.
This requires a `conflicts` declaration.

### Keywords vs identifiers

`is-valid` is an identifier, not the keyword `is` followed by `-valid`. The
`word: ($) => $.identifier` property in the grammar ensures that the longest
matching token (the full identifier) wins over any keyword prefix.

## File Structure

```
tree-sitter-datatwist/
  grammar.js          -- Grammar definition (Node.js module)
  src/
    parser.c          -- Generated parser (do not edit)
    scanner.c         -- Hand-written external scanner for __I
  queries/
    highlights.scm    -- Syntax highlighting queries
    indents.scm       -- Indentation queries
    textobjects.scm   -- Structural editing text objects
    folds.scm         -- Code folding queries
  test/
    corpus/           -- Tree-sitter test corpus files
      literals.txt
      bindings.txt
      pipelines.txt
      functions.txt
      guards.txt
      destructuring.txt
      interop.txt
```

## Development

```bash
npm install
npx tree-sitter generate    # Regenerate parser.c from grammar.js
npx tree-sitter test        # Run corpus tests
npx tree-sitter parse file.dt      # Parse a file, print CST
npx tree-sitter highlight file.dt  # Show highlighting
```

## Converting from Instaparse

The Instaparse EBNF grammar in `resources/datatwist.grammar` is the canonical
language definition. The Tree-sitter grammar in `grammar.js` is a manual
translation. See `docs/lsp-tree-sitter-design.md` for the full mapping between
Instaparse rules and Tree-sitter nodes, and the complete `grammar.js` skeleton.
