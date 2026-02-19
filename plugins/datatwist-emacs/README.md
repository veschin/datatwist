# datatwist-mode

Emacs major mode for DataTwist. Provides a CIDER-like interactive development
experience: syntax highlighting, indentation, REPL connection, eval-at-point,
inline result overlays, and data inspector.

## Phases

### Phase 1: Regex-based mode (no Tree-sitter dependency)

Available immediately, works in Emacs 27+:

- `datatwist-mode-syntax-table` — character classes (`-`, `?`, `!` are word
  chars; `//` is a line comment)
- `datatwist-font-lock-keywords` — regex-based syntax highlighting
- Indentation rules: increase after `|>`, `[`, `{`, `|`; decrease on `]`, `}`
- Bracket matching and auto-pairing for `[]`, `{}`, `""`
- `datatwist-jack-in` — start nREPL server and connect
- `datatwist-eval-last-sexp` — evaluate expression before point (heuristic
  boundary detection using newlines and balanced brackets)
- `datatwist-eval-buffer` — evaluate entire buffer
- `datatwist-eval-region` — evaluate selected region
- `datatwist-switch-to-repl-buffer` — switch to the REPL buffer
- Inline result overlays via `after-string` (CIDER overlay pattern)

### Phase 3: Tree-sitter-based mode (Emacs 29+)

- `datatwist-ts-mode` — Tree-sitter major mode using `treesit.el`
- Highlighting via `queries/highlights.scm`
- Structural navigation: next/previous expression, up/down in syntax tree
- `datatwist-eval-last-sexp` uses `treesit-node-at` for precise boundaries
- `treesit-defun-type-regexp` set to `"binding\\|function_definition\\|pipeline"`

## Key Functions

| Function | Description |
|---|---|
| `datatwist-jack-in` | Start nREPL server and connect |
| `datatwist-eval-last-sexp` | Evaluate expression before point, show overlay |
| `datatwist-eval-defun-at-point` | Evaluate the top-level binding at point |
| `datatwist-eval-buffer` | Evaluate the entire buffer |
| `datatwist-eval-region` | Evaluate the selected region |
| `datatwist-switch-to-repl-buffer` | Switch to or show the REPL buffer |
| `datatwist-inspect-last-result` | Inspect the result of the last evaluation |
| `datatwist-load-file` | Load and evaluate the current `.dt` file via nREPL |

## Inline Result Display

After each evaluation, the result is displayed as an inline overlay at the end
of the evaluated expression:

```
name is "Alice"  => "Alice"
```

The overlay auto-dismisses after `datatwist-eval-result-duration` seconds
(default: 3). The face is `datatwist-result-overlay-face`.

## Installation

```elisp
;; Via use-package (MELPA, once published)
(use-package datatwist-mode
  :mode "\\.dt\\'"
  :hook (datatwist-mode . datatwist-ts-mode-maybe))
```

## Dependencies

- `nrepl.el` or CIDER (for nREPL connection management)
- `tree-sitter-datatwist` grammar (for Phase 3 features)

## BDD Specifications

See `../../bdd/11-lsp-editor-support.feature` for eval-at-point and inspector
scenarios, and `../../bdd/12-nrepl-integration.feature` for nREPL scenarios.
