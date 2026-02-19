# DataTwist LSP Server & Tree-sitter Grammar Design

## Table of Contents

1. [Tree-sitter Grammar](#1-tree-sitter-grammar)
2. [LSP Server Architecture](#2-lsp-server-architecture)
3. [Function Signature Hints](#3-function-signature-hints-key-feature)
4. [Scope Analysis](#4-scope-analysis)
5. [Integration Points](#5-integration-points)

---

## 1. Tree-sitter Grammar

### 1.1 Instaparse PEG vs Tree-sitter LR(1)/GLR

The existing Instaparse grammar (`resources/datatwist.grammar`, 180 lines) is
PEG (Parsing Expression Grammar) with ordered choice. Tree-sitter uses LR(1)
with GLR fallback. Key translation challenges:

| Instaparse (PEG)                       | Tree-sitter (LR)                                 | Migration impact                                     |
| -------------------------------------- | ------------------------------------------------ | ---------------------------------------------------- |
| Ordered choice (`/`) is priority-based | `choice()` is unordered; precedence via `prec()` | Must add explicit precedence levels                  |
| Unlimited lookahead via regex          | LR(1) = 1 token lookahead                        | Keyword word-boundary checks need `token()` wrappers |
| Hidden rules `<KW-IS>` drop from AST   | `_` prefix hides from CST but node still exists  | Use field names + anonymous literals instead         |
| `#'regex'` inline in rules             | `token()` / `alias()` for terminal patterns      | Regexes stay but wrapped in `token()`                |
| Manual whitespace `_` / `__` / `__I`   | `extras: [$.comment, /\s/]` handles most WS      | Some constructs need explicit `token.immediate()`    |
| No error recovery                      | Built-in error recovery via `ERROR` nodes        | Free benefit -- partial parses in the editor         |

### 1.2 Whitespace Strategy

Instaparse uses three manual whitespace rules:

- `_` = optional whitespace (including comments): `(\s|//[^\n]*)*`
- `__` = required whitespace: `(\s|//[^\n]*)+`
- `__I` = inline-only whitespace (no newlines): `([ \t]|//[^\n]*)+`

Tree-sitter handles this with `extras` for the common case, and
`token.immediate()` for the exceptions:

```javascript
module.exports = grammar({
  name: 'datatwist',

  extras: $ => [
    /\s/,
    $.comment,
  ],

  comment: $ => token(seq('//', /.*/)),
```

The critical exception is `__I` (inline whitespace) used in function calls.
`FnCall = CallTarget __I CallArg (__I CallArg)*` means arguments must be on the
same line as the call target. In Tree-sitter, this requires `token.immediate()`
or a custom scanner (external scanner in C). Since Tree-sitter's `extras`
inserts whitespace everywhere, we need to handle FnCall specially:

```javascript
// Option A: Treat newline as a statement terminator via external scanner.
// The external scanner emits a NEWLINE token when it sees \n not preceded by |>
// This is the cleanest approach -- see section 1.7.

// Option B: Use prec() to prefer non-call interpretations when newline intervenes.
// Less precise but avoids external scanner complexity.
```

Recommendation: **External scanner** (Option A). DataTwist's `__I` rule exists
specifically to prevent cross-line argument capture (e.g., `(expr)\nx` must NOT
be a function call). An external scanner can emit an implicit `_newline` token
that breaks function call parsing across lines, matching Instaparse's behavior
exactly.

### 1.3 Node Type Mapping

Instaparse rules map to Tree-sitter nodes as follows. Rules that exist only as
precedence wrappers in the PEG become `prec()` levels in Tree-sitter, not
separate named nodes.

**Named nodes (appear in the CST):**

| Instaparse rule             | Tree-sitter node       | Rationale                         |
| --------------------------- | ---------------------- | --------------------------------- |
| `Program`                   | `program` (root)       | Required root node                |
| `Binding`                   | `binding`              | Semantic: defines a name          |
| `Pipeline`                  | `pipeline`             | Semantic: core language construct |
| `SourcelessPipeline`        | `sourceless_pipeline`  | Distinct from Pipeline (no LHS)   |
| `Compose`                   | `compose`              | Semantic: function composition    |
| `GuardBlock`                | `guard_block`          | Semantic: pattern matching        |
| `GuardArm`                  | `guard_arm`            | Individual match arm              |
| `FnDef`                     | `function_definition`  | Semantic: defines a function      |
| `FnParam`                   | `parameter`            | Named for signature help          |
| `FnBody`                    | `function_body`        | Scope boundary                    |
| `FnCall`                    | `call_expression`      | Semantic: function invocation     |
| `Recur`                     | `recur_expression`     | Semantic: tail recursion          |
| `Object`                    | `object`               | Semantic: data structure          |
| `List`                      | `list`                 | Semantic: data structure          |
| `Require`                   | `require_statement`    | Semantic: module import           |
| `TryCatch`                  | `try_expression`       | Semantic: error handling          |
| `CatchClause`               | `catch_clause`         | Part of try                       |
| `FinallyClause`             | `finally_clause`       | Part of try                       |
| `FieldAccess`               | `field_access`         | Semantic: member access           |
| `DestructObjPattern`        | `object_pattern`       | Semantic: destructuring           |
| `DestructListPattern`       | `list_pattern`         | Semantic: destructuring           |
| `RestParam` / `RestBinding` | `rest_element`         | Semantic: variadic                |
| `MultiArityFn`              | `multi_arity_function` | Multiple function heads           |

**Anonymous nodes (precedence wrappers -- collapsed into `prec()`):**

| Instaparse rule | Tree-sitter treatment                         |
| --------------- | --------------------------------------------- |
| `Expr`          | Transparent -- `choice()` of its alternatives |
| `PipeExpr`      | Transparent                                   |
| `PipeAtom`      | Transparent                                   |
| `OrExpr`        | `prec.left(1, ...)` on binary `or`            |
| `AndExpr`       | `prec.left(2, ...)` on binary `and`           |
| `NilCoalesce`   | `prec.left(3, ...)` on binary `??`            |
| `NotExpr`       | `prec(4, ...)` on unary `not`                 |
| `CompExpr`      | `prec(5, ...)` on binary comparison           |
| `InExpr`        | `prec(6, ...)` on binary `in`                 |
| `AddExpr`       | `prec.left(7, ...)` on binary `+`/`-`         |
| `MulExpr`       | `prec.left(8, ...)` on binary `*`/`/`/`%`     |
| `UnaryExpr`     | `prec(9, ...)` on unary `-`                   |
| `FnCallExpr`    | `prec(10, ...)` on call                       |
| `FieldAccess`   | `prec.left(11, ...)` on `.`                   |
| `Atom`          | Transparent -- `choice()` of literals         |
| `CallTarget`    | Inline into `call_expression`                 |
| `CallArg`       | Alias for `field_access`                      |

**Literal nodes:**

| Instaparse rule  | Tree-sitter node  |
| ---------------- | ----------------- |
| `Integer`        | `integer`         |
| `Float`          | `float`           |
| `String`         | `string`          |
| `Boolean`        | `boolean`         |
| `Nil`            | `nil`             |
| `Keyword`        | `keyword`         |
| `Identifier`     | `identifier`      |
| `Wildcard`       | `wildcard`        |
| `InstanceMethod` | `instance_method` |
| `Constructor`    | `constructor`     |
| `QualifiedName`  | `qualified_name`  |
| `DotName`        | `dotted_name`     |
| `FieldName`      | `field_name`      |

### 1.4 Precedence and Associativity

Tree-sitter uses numeric precedence levels. Higher number = tighter binding.

```javascript
const PREC = {
  PIPE: 1, // |>
  COMPOSE: 2, // >> <<
  OR: 3, // or
  AND: 4, // and
  NIL_COAL: 5, // ??
  NOT: 6, // not (unary prefix)
  COMPARE: 7, // = != > < >= <=
  IN: 8, // in
  ADD: 9, // + -
  MUL: 10, // * / %
  UNARY_NEG: 11, // - (unary)
  CALL: 12, // function call (juxtaposition)
  FIELD: 13, // .field
};
```

Associativity:

- `+`, `-`, `*`, `/`, `%`, `or`, `and`, `??`, `|>`, `>>`, `<<`, `.field`:
  **left-associative** (`prec.left`)
- Comparison operators (`=`, `!=`, `>`, `<`, `>=`, `<=`): **non-associative**
  (`prec` without `.left` or `.right`)
- `not`, unary `-`: **right-associative prefix** (`prec`)
- `in`: **non-associative** (single operand on each side)

### 1.5 Keyword Word Boundaries

Instaparse uses regex lookahead for keyword boundaries:
`#'is(?![a-zA-Z0-9_?!\-])'`. Tree-sitter handles this differently -- keywords
are matched as whole tokens, and the `word` property provides implicit word
boundary:

```javascript
module.exports = grammar({
  name: "datatwist",
  word: ($) => $.identifier,
  // ...
});
```

The `word` property tells Tree-sitter that `identifier` is the "word" token.
When Tree-sitter sees `island`, it will try to match the full word as an
`identifier` before trying keywords like `is`. This eliminates the need for
manual lookahead on most keywords.

For keywords that can appear as part of identifiers with `-` (e.g., `is-valid`
should be an identifier, not `is` + `-valid`), the `identifier` regex must
include hyphens: `/[a-zA-Z][a-zA-Z0-9_\-]*[?!]?/`. Because this regex is longer
than `is`, Tree-sitter's longest-match rule ensures `is-valid` tokenizes as one
identifier.

Potential conflict: `true`, `false`, `nil` vs identifiers starting with those
strings. Tree-sitter resolves this via the `word` property -- if the full token
matches an identifier pattern, it wins over the keyword.

```javascript
rules: {
  identifier: $ => token(
    seq(
      /(?!(?:true|false|nil|is|and|or|not|in|when|as|try|catch|finally|recur|require)(?![a-zA-Z0-9_?!\-]))/,
      /[a-zA-Z][a-zA-Z0-9_\-]*[?!]?/
    )
  ),
  // Keywords as simple string literals -- word property handles disambiguation
  _kw_is: $ => 'is',
  _kw_and: $ => 'and',
  _kw_or: $ => 'or',
  // ...
},
```

However, the negative lookahead in `identifier` cannot be expressed in
Tree-sitter's token regex (Tree-sitter uses a limited regex engine without
lookahead). Instead, we rely on **conflicts** and **precedence**:

```javascript
  conflicts: $ => [
    // When we see "is" followed by a non-word char, prefer keyword over identifier
    [$.binding, $.call_expression],
  ],

  rules: {
    identifier: $ => /[a-zA-Z][a-zA-Z0-9_\-]*[?!]?/,

    // Keywords become string literals; Tree-sitter's word property resolves ambiguity
    binding: $ => seq(
      field('name', $.identifier),
      'is',
      field('value', $._expression),
    ),
  },
```

### 1.6 Grammar.js Skeleton for Complex Constructs

```javascript
// grammar.js — DataTwist Tree-sitter grammar (skeleton)

const PREC = {
  PIPE: 1,
  COMPOSE: 2,
  OR: 3,
  AND: 4,
  NIL_COAL: 5,
  NOT: 6,
  COMPARE: 7,
  IN: 8,
  ADD: 9,
  MUL: 10,
  UNARY_NEG: 11,
  CALL: 12,
  FIELD: 13,
};

module.exports = grammar({
  name: "datatwist",

  externals: ($) => [
    $._newline, // emitted by external scanner at \n boundaries
    $._indent, // future: if indentation sensitivity is added
  ],

  extras: ($) => [/[ \t]/, $.comment], // newlines handled by external scanner

  word: ($) => $.identifier,

  conflicts: ($) => [
    [$.call_expression, $._primary],
    [$.binding, $.call_expression],
    [$.list, $.function_definition], // both start with [
    [$.guard_block, $.pipeline], // | in guards vs |> in pipes
  ],

  rules: {
    program: ($) => repeat1($._statement),

    _statement: ($) => seq($._expression, optional($._newline)),

    _expression: ($) =>
      choice(
        $.require_statement,
        $.try_expression,
        $.binding,
        $._pipe_expression,
      ),

    // --- Require ---
    require_statement: ($) =>
      seq(
        "require",
        field("module", $.dotted_name),
        "as",
        field("alias", $.identifier),
      ),

    // --- Binding ---
    binding: ($) =>
      choice(
        seq(
          field("name", $.identifier),
          "is",
          field(
            "value",
            choice(
              $.multi_arity_function,
              $.try_expression,
              $._pipe_expression,
            ),
          ),
        ),
        seq(
          field("pattern", $._destruct_pattern),
          "is",
          field("value", $._pipe_expression),
        ),
      ),

    multi_arity_function: ($) =>
      prec(
        1,
        seq(
          $.function_definition,
          repeat1($.function_definition),
        ),
      ),

    // --- Pipeline ---
    _pipe_expression: ($) =>
      choice(
        $.pipeline,
        $.sourceless_pipeline,
        $.compose,
        $.guard_block,
        $._or_expression,
      ),

    pipeline: ($) =>
      prec.left(
        PREC.PIPE,
        seq(
          field("source", $._pipe_atom),
          repeat1(seq("|>", field("step", $._pipe_atom))),
        ),
      ),

    sourceless_pipeline: ($) =>
      seq(
        "|>",
        field("step", $._pipe_atom),
        repeat(seq("|>", field("step", $._pipe_atom))),
      ),

    _pipe_atom: ($) => choice($.guard_block, $._or_expression),

    // --- Composition ---
    compose: ($) =>
      prec.left(
        PREC.COMPOSE,
        seq(
          $._or_expression,
          repeat1(seq(field("op", choice(">>", "<<")), $._or_expression)),
        ),
      ),

    // --- Guards / Pattern Matching ---
    guard_block: ($) =>
      prec(
        1,
        seq(
          $.guard_arm,
          repeat1($.guard_arm),
        ),
      ),

    guard_arm: ($) =>
      seq(
        "|",
        field("pattern", $._guard_pattern),
        optional(seq("when", field("condition", $._or_expression))),
        "->",
        field("body", $._or_expression),
      ),

    _guard_pattern: ($) =>
      choice(
        $.list_pattern,
        $.object_pattern,
        $._or_expression,
      ),

    // --- Binary expressions (precedence chain) ---
    _or_expression: ($) => choice($.binary_expression, $._and_expression),
    _and_expression: ($) => choice($.binary_expression, $._nil_coalesce),
    _nil_coalesce: ($) => choice($.binary_expression, $._not_expression),
    _not_expression: ($) => choice($.not_expression, $._compare_expression),
    _compare_expression: ($) => choice($.binary_expression, $._in_expression),
    _in_expression: ($) => choice($.binary_expression, $._add_expression),
    _add_expression: ($) => choice($.binary_expression, $._mul_expression),
    _mul_expression: ($) => choice($.binary_expression, $._unary_expression),

    // Unified binary expression node with precedence levels
    binary_expression: ($) =>
      choice(
        prec.left(
          PREC.OR,
          seq(
            field("left", $._expression),
            field("op", "or"),
            field("right", $._expression),
          ),
        ),
        prec.left(
          PREC.AND,
          seq(
            field("left", $._expression),
            field("op", "and"),
            field("right", $._expression),
          ),
        ),
        prec.left(
          PREC.NIL_COAL,
          seq(
            field("left", $._expression),
            field("op", "??"),
            field("right", $._expression),
          ),
        ),
        prec(
          PREC.COMPARE,
          seq(
            field("left", $._expression),
            field("op", choice("=", "!=", ">", "<", ">=", "<=")),
            field("right", $._expression),
          ),
        ),
        prec(
          PREC.IN,
          seq(
            field("left", $._expression),
            field("op", "in"),
            field("right", $._expression),
          ),
        ),
        prec.left(
          PREC.ADD,
          seq(
            field("left", $._expression),
            field("op", choice("+", "-")),
            field("right", $._expression),
          ),
        ),
        prec.left(
          PREC.MUL,
          seq(
            field("left", $._expression),
            field("op", choice("*", "/", "%")),
            field("right", $._expression),
          ),
        ),
      ),

    not_expression: ($) => prec(PREC.NOT, seq("not", $._expression)),

    _unary_expression: ($) => choice($.unary_expression, $._call_expression),

    unary_expression: ($) =>
      prec(
        PREC.UNARY_NEG,
        seq(
          token.immediate("-"),
          $._call_expression,
        ),
      ),

    // --- Function call ---
    _call_expression: ($) =>
      choice(
        $.call_expression,
        $.recur_expression,
        $._field_access,
      ),

    // Juxtaposition call: `f arg1 arg2` or parenthesized: `f()`
    call_expression: ($) =>
      prec(
        PREC.CALL,
        choice(
          seq(
            field("function", $._call_target),
            "(",
            ")",
          ),
          seq(
            field("function", $._call_target),
            // Arguments must be on same line (external scanner handles this)
            repeat1(field("argument", $._field_access)),
          ),
        ),
      ),

    _call_target: ($) =>
      seq(
        choice(
          $.wildcard,
          $.identifier,
          $.paren_expression,
          $.instance_method,
          $.constructor,
          $.qualified_name,
        ),
        repeat(seq(".", $.field_name)),
      ),

    recur_expression: ($) =>
      prec(
        PREC.CALL,
        seq(
          "recur",
          repeat1(field("argument", $._field_access)),
        ),
      ),

    // --- Field access ---
    _field_access: ($) => choice($.field_access, $._primary),

    field_access: ($) =>
      prec.left(
        PREC.FIELD,
        seq(
          field("object", $._primary),
          repeat1(seq(token.immediate("."), field("field", $.field_name))),
        ),
      ),

    field_name: ($) => /[a-zA-Z][a-zA-Z0-9_\-]*[?!]?/,

    // --- Primary (atoms) ---
    _primary: ($) =>
      choice(
        $.float,
        $.integer,
        $.string,
        $.boolean,
        $.nil,
        $.keyword,
        $.object,
        $.function_definition,
        $.list,
        $.instance_method,
        $.constructor,
        $.qualified_name,
        $.wildcard,
        $.identifier,
        $.paren_expression,
      ),

    paren_expression: ($) => seq("(", $._expression, ")"),

    // --- Object ---
    object: ($) =>
      choice(
        seq("{", "}"),
        seq("{", $._object_content, "}"),
      ),

    _object_content: ($) =>
      choice(
        $.shorthand_content,
        $._standard_content,
      ),

    // {name, age, city: _.address.city}
    shorthand_content: ($) =>
      seq(
        $.identifier,
        repeat1(seq(",", $._shorthand_entry)),
      ),

    _shorthand_entry: ($) =>
      choice(
        seq(
          field("key", $.identifier),
          ":",
          field("value", $._pipe_expression),
        ),
        $.identifier,
      ),

    _standard_content: ($) => repeat1($._standard_entry),

    _standard_entry: ($) =>
      choice(
        $.add_field,
        $.remove_field,
        $.object_field,
      ),

    object_field: ($) =>
      seq(
        field("key", $.identifier),
        ":",
        field("value", $._pipe_expression),
      ),

    add_field: ($) =>
      seq(
        "+",
        field("key", $.identifier),
        ":",
        field("value", $._pipe_expression),
      ),
    remove_field: ($) => seq("-", field("key", $.identifier)),

    // --- Function definition ---
    function_definition: ($) =>
      choice(
        seq("[", optional($.parameter_list), "->", $.function_body, "]"),
      ),

    parameter_list: ($) => repeat1(field("param", $._parameter)),

    _parameter: ($) =>
      choice(
        $.object_pattern,
        $.list_pattern,
        $.rest_element,
        $.wildcard,
        $.identifier,
      ),

    rest_element: ($) => seq("&", $.identifier),

    function_body: ($) => repeat1($._expression),

    // --- List ---
    list: ($) =>
      choice(
        seq("[", "]"),
        seq("[", repeat1($._field_access), "]"),
      ),

    // --- Destructuring ---
    _destruct_pattern: ($) =>
      seq(
        choice($.object_pattern, $.list_pattern),
        optional(seq("as", field("alias", $.identifier))),
      ),

    object_pattern: ($) =>
      seq(
        "{",
        repeat1($._object_pattern_field),
        "}",
      ),

    _object_pattern_field: ($) =>
      choice(
        seq($.identifier, ":", $._destruct_sub_pattern), // rename
        seq($.identifier, "?", $._or_expression), // default
        $.identifier, // shorthand
      ),

    _destruct_sub_pattern: ($) =>
      choice(
        $.object_pattern,
        $.list_pattern,
        $.identifier,
      ),

    list_pattern: ($) =>
      seq(
        "[",
        $._list_pattern_elements,
        "]",
      ),

    _list_pattern_elements: ($) =>
      choice(
        seq(repeat1($._list_pattern_element), optional($.rest_element)),
        $.rest_element,
      ),

    _list_pattern_element: ($) =>
      choice(
        $.object_pattern,
        $.wildcard,
        $.identifier,
      ),

    // --- Try-Catch ---
    try_expression: ($) =>
      seq(
        "try",
        field("body", $._expression),
        repeat1($.catch_clause),
        optional($.finally_clause),
      ),

    catch_clause: ($) =>
      seq(
        "catch",
        field("target", $._catch_target),
        "->",
        field("handler", $._expression),
      ),

    _catch_target: ($) =>
      choice(
        seq($.dotted_name, $.identifier), // typed: ExceptionClass varName
        $.identifier, // untyped: varName
        $.wildcard, // discard: _
      ),

    finally_clause: ($) => seq("finally", field("body", $._expression)),

    // --- Literals ---
    float: ($) => /[0-9]+\.[0-9]+/,
    integer: ($) => /[0-9]+/,

    string: ($) =>
      choice(
        seq('"', /(?:[^"\\]|\\.)*/, '"'),
        seq("'", /(?:[^'\\]|\\.)*/, "'"),
      ),

    boolean: ($) => choice("true", "false"),
    nil: ($) => "nil",

    keyword: ($) => /:[a-zA-Z][a-zA-Z0-9_\-]*/,

    // --- Interop ---
    instance_method: ($) => /\.[a-zA-Z][a-zA-Z0-9_\-]*/,
    constructor: ($) => /[A-Z][a-zA-Z0-9]*\./,
    qualified_name: ($) =>
      /[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)*(\/[a-zA-Z][a-zA-Z0-9_\-]*)/,
    dotted_name: ($) => /[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)*/,

    // --- Wildcard and Identifier ---
    wildcard: ($) => "_",
    identifier: ($) => /[a-zA-Z][a-zA-Z0-9_\-]*[?!]?/,

    // --- Comment ---
    comment: ($) => token(seq("//", /.*/)),
  },
});
```

### 1.7 External Scanner for Newline Sensitivity

DataTwist's `__I` (inline-only whitespace) rule prevents function calls from
capturing arguments across line boundaries. Tree-sitter needs an external
scanner (C code) to handle this:

```c
// src/scanner.c
#include "tree_sitter/parser.h"

enum TokenType {
  NEWLINE,
};

// Called by Tree-sitter when it needs to decide about NEWLINE tokens.
// Scans forward; if we encounter a newline that is NOT followed by |>,
// emit NEWLINE to break function call argument lists.
bool tree_sitter_datatwist_external_scanner_scan(
  void *payload, TSLexer *lexer, const bool *valid_symbols
) {
  if (!valid_symbols[NEWLINE]) return false;

  // Skip spaces and tabs (not newlines)
  while (lexer->lookahead == ' ' || lexer->lookahead == '\t') {
    lexer->advance(lexer, true);  // skip
  }

  if (lexer->lookahead == '\n' || lexer->lookahead == '\r') {
    lexer->advance(lexer, true);
    // Skip any further whitespace to see what follows
    while (lexer->lookahead == ' ' || lexer->lookahead == '\t' ||
           lexer->lookahead == '\n' || lexer->lookahead == '\r') {
      lexer->advance(lexer, true);
    }
    // If next non-whitespace is |>, do NOT emit newline (pipe continuation)
    if (lexer->lookahead == '|') {
      // Peek one more character
      lexer->mark_end(lexer);
      lexer->advance(lexer, false);
      if (lexer->lookahead == '>') {
        return false;  // pipe continuation -- no break
      }
    }
    lexer->result_symbol = NEWLINE;
    return true;
  }

  return false;
}

// Boilerplate
void *tree_sitter_datatwist_external_scanner_create() { return NULL; }
void tree_sitter_datatwist_external_scanner_destroy(void *payload) {}
unsigned tree_sitter_datatwist_external_scanner_serialize(void *payload, char *buffer) { return 0; }
void tree_sitter_datatwist_external_scanner_deserialize(void *payload, const char *buffer, unsigned length) {}
```

This scanner emits `NEWLINE` tokens at line boundaries unless the next line
starts with `|>`. The grammar uses `$._newline` (from `externals`) as an
implicit statement separator and to break `call_expression` argument lists.

### 1.8 List vs FnDef Disambiguation

Both `List` and `FnDef` start with `[`. Instaparse resolves this via PEG
ordering (FnDef's `->` is tried first within `Atom`). Tree-sitter needs a
conflict declaration:

```javascript
conflicts: $ => [
  [$.list, $.function_definition],
],
```

Tree-sitter's GLR mode will fork the parse at `[`, and the branch that
encounters `->` commits to `function_definition`, while the branch without `->`
commits to `list`. This is exact parity with the Instaparse behavior.

### 1.9 Queries for Syntax Highlighting

Tree-sitter syntax highlighting uses S-expression queries in
`queries/highlights.scm`:

```scheme
; Keywords
["is" "and" "or" "not" "in" "when" "as" "try" "catch" "finally"
 "recur" "require"] @keyword

; Operators
["|>" ">>" "<<" "??" "+" "-" "*" "/" "%" "=" "!=" ">" "<" ">=" "<="] @operator
["->" "|"] @punctuation.special

; Literals
(integer) @number
(float) @number.float
(string) @string
(boolean) @constant.builtin
(nil) @constant.builtin
(keyword) @string.special

; Identifiers and functions
(call_expression function: (identifier) @function)
(call_expression function: (qualified_name) @function)
(binding name: (identifier) @variable)
(function_definition (parameter_list (identifier) @variable.parameter))
(rest_element (identifier) @variable.parameter)

; Wildcard
(wildcard) @variable.builtin

; Field access
(field_access field: (field_name) @property)
(object_field key: (identifier) @property)
(add_field key: (identifier) @property)
(remove_field key: (identifier) @property)

; Comments
(comment) @comment

; Punctuation
["{" "}" "[" "]" "(" ")"] @punctuation.bracket
[":" "," "." "+" "-"] @punctuation.delimiter

; Side-effect functions (ending with !)
((identifier) @function.builtin
 (#match? @function.builtin ".*!$"))

; Predicate functions (ending with ?)
((identifier) @function.builtin
 (#match? @function.builtin ".*\\?$"))

; Interop
(instance_method) @function.method
(constructor) @constructor
(qualified_name) @function

; Patterns
(guard_arm "|" @punctuation.special)
(guard_arm "->" @punctuation.special)
(function_definition "->" @punctuation.special)
```

---

## 2. LSP Server Architecture

### 2.1 Technology Choice

**Recommendation: TypeScript LSP server + Tree-sitter WASM.**

| Option                     | Pros                                                                              | Cons                                                                                                          |
| -------------------------- | --------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| **TypeScript (custom)**    | Fast startup, npm ecosystem, tree-sitter bindings mature, easy VSCode integration | Separate codebase from Clojure evaluator                                                                      |
| Clojure (custom)           | Same language as runtime, direct access to evaluator                              | JVM startup time (2-5s), poor LSP library ecosystem                                                           |
| Clojure (clojure-lsp fork) | Battle-tested LSP framework                                                       | clojure-lsp is Clojure-specific; adapting it for a different language is more work than building from scratch |

TypeScript wins because:

1. LSP libraries are mature (`vscode-languageserver` /
   `@vscode/languageserver-node`).
2. Tree-sitter has first-class WASM bindings for TypeScript (`web-tree-sitter`).
3. Startup time is instant (~100ms vs ~3s for JVM).
4. The LSP server does not need to evaluate DataTwist code -- it only needs to
   parse (via Tree-sitter) and analyze the AST. The evaluator remains in
   Clojure.
5. Stdlib metadata (function signatures, docs) is static and can be shipped as
   JSON.

### 2.2 Communication Protocol

**stdio** for all editors. Socket/TCP as optional fallback for JetBrains.

```
Editor <--stdio--> datatwist-lsp (TypeScript/Node.js)
                        |
                        +--- tree-sitter-datatwist (WASM, incremental parsing)
                        +--- stdlib-metadata.json (function signatures)
                        +--- scope-analyzer.ts (binding resolution)
```

### 2.3 Project Structure

```
datatwist-lsp/
  package.json
  tsconfig.json
  src/
    server.ts              # LSP server entry point (stdio)
    capabilities.ts        # Capability registration
    analyzer/
      scope.ts             # Scope analysis (is-binding, function params)
      pipeline.ts          # Pipeline context tracking (_ resolution)
      types.ts             # Type inference stubs
    providers/
      diagnostics.ts       # Parse errors, common mistakes
      completion.ts        # Context-aware completion
      signature-help.ts    # Function signature hints (KEY FEATURE)
      hover.ts             # Hover documentation
      definition.ts        # Go-to-definition
      references.ts        # Find references
      inlay-hints.ts       # Inline type/param hints
      semantic-tokens.ts   # Semantic highlighting (augments Tree-sitter)
    metadata/
      stdlib.json          # Function signature metadata
      loader.ts            # Metadata loader + validator
    tree-sitter/
      parser.ts            # Tree-sitter WASM wrapper
      queries.ts           # Pre-compiled queries for analysis
  tree-sitter-datatwist/   # Submodule: the Tree-sitter grammar
    grammar.js
    src/
      scanner.c
    queries/
      highlights.scm
      locals.scm           # Scope resolution queries
      tags.scm             # Symbol extraction queries
```

### 2.4 Capabilities in Priority Order

**Phase 1 (MVP):**

| Capability     | LSP Method                        | Implementation                          |
| -------------- | --------------------------------- | --------------------------------------- |
| Diagnostics    | `textDocument/publishDiagnostics` | Tree-sitter parse errors + ERROR nodes  |
| Completion     | `textDocument/completion`         | Stdlib functions, bound names, keywords |
| Signature Help | `textDocument/signatureHelp`      | Stdlib metadata with pipe-awareness     |

**Phase 2:**

| Capability       | LSP Method                    | Implementation                   |
| ---------------- | ----------------------------- | -------------------------------- |
| Hover            | `textDocument/hover`          | Function docs, type info         |
| Go-to-Definition | `textDocument/definition`     | Scope analysis for `is` bindings |
| Find References  | `textDocument/references`     | Scope analysis                   |
| Document Symbols | `textDocument/documentSymbol` | Top-level bindings and functions |

**Phase 3:**

| Capability      | LSP Method                    | Implementation                       |
| --------------- | ----------------------------- | ------------------------------------ |
| Semantic Tokens | `textDocument/semanticTokens` | Distinguish stdlib vs user functions |
| Inlay Hints     | `textDocument/inlayHint`      | Parameter names in function calls    |
| Rename          | `textDocument/rename`         | Scope-aware rename                   |
| Code Actions    | `textDocument/codeAction`     | Quick fixes for common mistakes      |
| Formatting      | `textDocument/formatting`     | Opinionated formatter                |

### 2.5 Diagnostics Implementation

Tree-sitter produces `ERROR` nodes for unparseable regions. The diagnostics
provider walks the CST and reports:

```typescript
// diagnostics.ts
import Parser from "web-tree-sitter";

interface DiagnosticContext {
  tree: Parser.Tree;
  source: string;
}

function collectErrors(ctx: DiagnosticContext): Diagnostic[] {
  const diagnostics: Diagnostic[] = [];
  const cursor = ctx.tree.walk();

  function visit() {
    const node = cursor.currentNode;

    if (node.type === "ERROR" || node.isMissing) {
      diagnostics.push({
        range: {
          start: {
            line: node.startPosition.row,
            character: node.startPosition.column,
          },
          end: {
            line: node.endPosition.row,
            character: node.endPosition.column,
          },
        },
        severity: DiagnosticSeverity.Error,
        source: "datatwist",
        message: inferErrorMessage(node, ctx.source),
        code: inferErrorCode(node),
      });
    }

    // Common mistake detection
    if (node.type === "binary_expression") {
      const op = node.childForFieldName("op");
      // User wrote `x = 42` but meant `x is 42`
      if (op?.text === "=" && isAssignmentContext(node)) {
        diagnostics.push({
          range: nodeRange(op),
          severity: DiagnosticSeverity.Warning,
          source: "datatwist",
          message: "Did you mean 'is'? Use 'is' for assignment: x is 42",
          code: "DT-P001",
        });
      }
    }

    if (cursor.gotoFirstChild()) {
      do {
        visit();
      } while (cursor.gotoNextSibling());
      cursor.gotoParent();
    }
  }

  visit();
  return diagnostics;
}
```

Error messages follow the PRD's Elm-style principle:

```json
{
  "range": {
    "start": { "line": 0, "character": 24 },
    "end": { "line": 0, "character": 25 }
  },
  "severity": 1,
  "source": "datatwist",
  "message": "Unexpected end of expression after '>'\n\nExpected: a value (number, string, identifier)\nExample: users |> filter _.age > 18",
  "code": "DT-P002"
}
```

### 2.6 Completion Implementation

Context-aware completion triggers:

```typescript
// completion.ts

function provideCompletion(
  params: CompletionParams,
  tree: Parser.Tree,
  scope: ScopeInfo,
  metadata: StdlibMetadata,
): CompletionItem[] {
  const node = nodeAtPosition(tree, params.position);
  const context = analyzeCompletionContext(node);

  switch (context.type) {
    case "after_pipe":
      // After |> — suggest functions that accept a collection/value as first arg
      return metadata.functions
        .filter((f) => f.pipeCompatible)
        .map((f) => ({
          label: f.name,
          kind: CompletionItemKind.Function,
          detail: f.signatureWithoutFirstParam, // first param is piped
          documentation: { kind: "markdown", value: f.doc },
          insertText: f.snippetWithoutFirstParam,
          insertTextFormat: InsertTextFormat.Snippet,
          sortText: `0${f.name}`, // prioritize stdlib
        }));

    case "after_dot":
      // After . — suggest field names from known object shapes
      return scope.knownFields(context.objectBinding).map((field) => ({
        label: field,
        kind: CompletionItemKind.Property,
      }));

    case "identifier":
      // General context — suggest everything
      return [
        ...scope.visibleBindings().map((b) => ({
          label: b.name,
          kind: CompletionItemKind.Variable,
          detail: b.type ?? "binding",
        })),
        ...metadata.functions.map((f) => ({
          label: f.name,
          kind: CompletionItemKind.Function,
          detail: f.signature,
          documentation: { kind: "markdown", value: f.doc },
          insertText: f.snippet,
          insertTextFormat: InsertTextFormat.Snippet,
        })),
        ...KEYWORDS.map((kw) => ({
          label: kw,
          kind: CompletionItemKind.Keyword,
        })),
      ];

    case "after_catch":
      // After catch — suggest exception class names
      return COMMON_EXCEPTIONS.map((e) => ({
        label: e,
        kind: CompletionItemKind.Class,
      }));

    default:
      return [];
  }
}
```

---

## 3. Function Signature Hints (KEY FEATURE)

This is the most important UX feature for DataTwist. When a user types a
function name, the editor shows parameter placeholders with tab-stop navigation.

### 3.1 Metadata Schema

Each stdlib function is described by a metadata entry. The canonical format is
EDN (for Clojure-side generation) with JSON export for the TypeScript LSP:

**EDN source (in `resources/stdlib-metadata.edn`):**

```clojure
;; resources/stdlib-metadata.edn
;; Authoritative metadata for stdlib functions.
;; Generated JSON is committed to datatwist-lsp/src/metadata/stdlib.json

[{:name "filter"
  :doc "Keep elements matching a predicate."
  :params [{:name "collection" :type "list" :pipe-first true}
           {:name "predicate"  :type "function"
            :placeholder "[item -> condition]"
            :examples ["_.active" "[x -> x > 0]" "[{age} -> age > 18]"]}]
  :returns "list"
  :examples ["users |> filter _.active"
             "numbers |> filter [n -> n > 5]"
             "[1 2 3 4] |> filter [n -> n % 2 = 0]"]
  :tags #{:collection :higher-order}}

 {:name "map"
  :doc "Transform each element with a function."
  :params [{:name "collection" :type "list" :pipe-first true}
           {:name "transform"  :type "function"
            :placeholder "[item -> expression]"
            :examples ["{name: _.name}" "[x -> x * 2]" "_.name"]}]
  :returns "list"
  :examples ["users |> map _.name"
             "users |> map {name: _.name age: _.age}"
             "numbers |> map [n -> n * 2]"]}

 {:name "sort-by"
  :doc "Sort a collection by a key function."
  :params [{:name "collection" :type "list" :pipe-first true}
           {:name "key"        :type "function"
            :placeholder "_.field"
            :examples ["_.age" "_.name" "_.score"]}]
  :returns "list"
  :examples ["users |> sort-by _.age"
             "users |> sort-by _.name"]}

 {:name "group-by"
  :doc "Group elements by a key function. Returns an object."
  :params [{:name "collection" :type "list" :pipe-first true}
           {:name "key"        :type "function"
            :placeholder "_.field"
            :examples ["_.status" "_.category" "_.type"]}]
  :returns "object"
  :examples ["users |> group-by _.status"
             "orders |> group-by _.category"]}

 {:name "take"
  :doc "Take the first n elements."
  :params [{:name "collection" :type "list" :pipe-first true}
           {:name "count"      :type "integer"
            :placeholder "n"
            :examples ["5" "10" "20"]}]
  :returns "list"
  :examples ["users |> take 10"]}

 {:name "drop"
  :doc "Drop the first n elements."
  :params [{:name "collection" :type "list" :pipe-first true}
           {:name "count"      :type "integer"
            :placeholder "n"
            :examples ["5" "10"]}]
  :returns "list"
  :examples ["users |> drop 5"]}

 {:name "reduce"
  :doc "Reduce a collection with a function."
  :params [{:name "collection" :type "list" :pipe-first true}
           {:name "reducer"    :type "function"
            :placeholder "[acc item -> expression]"
            :examples ["[acc x -> acc + x]"]}
           {:name "initial"    :type "any" :optional true
            :placeholder "init"
            :examples ["0" "\"\"" "[]" "{}"]}]
  :returns "any"
  :examples ["numbers |> reduce [acc x -> acc + x] 0"]}

 {:name "join"
  :doc "Join list elements into a string."
  :params [{:name "collection" :type "list" :pipe-first true}
           {:name "separator"  :type "string" :optional true
            :placeholder "\"sep\""
            :examples ["\", \"" "\" \"" "\"\\n\"" "\"-\""]}]
  :returns "string"
  :examples ["words |> join \", \""
             "[\"a\" \"b\" \"c\"] |> join \"-\""]}

 {:name "replace"
  :doc "Replace occurrences of a pattern in a string."
  :params [{:name "string"  :type "string" :pipe-first true}
           {:name "from"    :type "string" :placeholder "\"pattern\""}
           {:name "to"      :type "string" :placeholder "\"replacement\""}]
  :returns "string"
  :examples ["name |> replace \"_\" \" \""]}

 {:name "format"
  :doc "Format a string with arguments (printf-style)."
  :params [{:name "template" :type "string" :placeholder "\"%s is %d\""}
           {:name "args"     :type "any" :variadic true
            :placeholder "value"}]
  :returns "string"
  :examples ["format \"Hello, %s!\" name"
             "format \"%s scored %d\" name score"]}

 {:name "get"
  :doc "Get a value from an object by key. Returns nil if missing."
  :params [{:name "object"  :type "object" :pipe-first true}
           {:name "key"     :type "string" :placeholder "\"field\""}]
  :returns "any"
  :examples ["user |> get \"name\""
             "get user key-name"]}

 {:name "count"
  :doc "Return the number of elements in a collection."
  :params [{:name "collection" :type "list|object|string" :pipe-first true}]
  :returns "integer"
  :examples ["users |> count"
             "count items"]}

 {:name "sum"
  :doc "Sum all numeric elements in a collection."
  :params [{:name "collection" :type "list" :pipe-first true}]
  :returns "number"
  :examples ["prices |> sum"
             "[1 2 3 4 5] |> sum"]}

 {:name "average"
  :doc "Compute the average of numeric elements."
  :params [{:name "collection" :type "list" :pipe-first true}]
  :returns "number"
  :examples ["scores |> average"]}

 {:name "distinct"
  :doc "Remove duplicate elements."
  :params [{:name "collection" :type "list" :pipe-first true}]
  :returns "list"
  :examples ["items |> distinct"]}

 {:name "flatten"
  :doc "Flatten a nested list by one level."
  :params [{:name "collection" :type "list" :pipe-first true}]
  :returns "list"
  :examples ["[[1 2] [3 4]] |> flatten"]}

 {:name "reverse"
  :doc "Reverse the order of elements."
  :params [{:name "collection" :type "list" :pipe-first true}]
  :returns "list"
  :examples ["items |> reverse"]}

 {:name "log!"
  :doc "Print a message and return the data unchanged (side-effect)."
  :params [{:name "data"    :type "any" :pipe-first true}
           {:name "message" :type "string" :variadic true
            :placeholder "\"label\""}]
  :returns "any (passthrough)"
  :side-effect true
  :examples ["data |> log! \"processing\""]}

 {:name "tap!"
  :doc "Print the data (or apply a function) and return it unchanged."
  :params [{:name "data"     :type "any" :pipe-first true}
           {:name "function" :type "function" :optional true
            :placeholder "[x -> expression]"}]
  :returns "any (passthrough)"
  :side-effect true
  :examples ["data |> tap!"
             "data |> tap! [x -> println (count x)]"]}

 {:name "range"
  :doc "Generate a list of integers."
  :params [{:name "end"   :type "integer" :placeholder "n"}
           {:name "start" :type "integer" :optional true :placeholder "start"}
           {:name "step"  :type "integer" :optional true :placeholder "step"}]
  :returns "list"
  :examples ["range 10"
             "range 1 10"
             "range 0 100 5"]}

 {:name "split"
  :doc "Split a string by a separator pattern."
  :params [{:name "string"    :type "string" :pipe-first true}
           {:name "separator" :type "string" :placeholder "\"pattern\""}]
  :returns "list"
  :examples ["\"a,b,c\" |> split \",\""]}

 {:name "each"
  :doc "Apply a side-effect function to each element. Returns the collection unchanged."
  :params [{:name "collection" :type "list" :pipe-first true}
           {:name "function"   :type "function"
            :placeholder "[item -> side-effect]"}]
  :returns "list (passthrough)"
  :side-effect true
  :examples ["users |> each [u -> println u.name]"]}]
```

**JSON export (for LSP consumption, generated from EDN):**

```json
{
  "functions": [
    {
      "name": "sort-by",
      "doc": "Sort a collection by a key function.",
      "params": [
        { "name": "collection", "type": "list", "pipeFirst": true },
        {
          "name": "key",
          "type": "function",
          "placeholder": "_.field",
          "examples": ["_.age", "_.name", "_.score"]
        }
      ],
      "returns": "list",
      "examples": ["users |> sort-by _.age"],
      "tags": ["collection", "higher-order"]
    }
  ]
}
```

### 3.2 Signature Help Provider

The LSP `textDocument/signatureHelp` protocol returns `SignatureInformation`
with `ParameterInformation`. The key behavior:

1. **After `|>`**: The first `pipe-first: true` parameter is implicit (it
   receives the piped data). The signature display skips it.
2. **Direct call**: All parameters shown.
3. **Active parameter**: Tracks which parameter the cursor is on based on
   argument count.

```typescript
// signature-help.ts
import {
  ParameterInformation,
  SignatureHelp,
  SignatureInformation,
} from "vscode-languageserver";

function provideSignatureHelp(
  params: SignatureHelpParams,
  tree: Parser.Tree,
  metadata: StdlibMetadata,
): SignatureHelp | null {
  const node = nodeAtPosition(tree, params.position);
  const callCtx = findEnclosingCall(node);
  if (!callCtx) return null;

  const funcName = callCtx.functionName;
  const funcMeta = metadata.lookup(funcName);
  if (!funcMeta) return null;

  const isPiped = callCtx.isPipeContext;
  const visibleParams = isPiped
    ? funcMeta.params.filter((p) => !p.pipeFirst)
    : funcMeta.params;

  const activeParam = countArgumentsBefore(callCtx, params.position);

  // Build the display label: `sort-by ·key·`  or  `sort-by collection ·key·`
  const paramLabels = visibleParams.map((p) => p.placeholder ?? p.name);
  const label = isPiped
    ? `${funcName} ${paramLabels.join(" ")}`
    : `${funcName} ${
      funcMeta.params.map((p) => p.placeholder ?? p.name).join(" ")
    }`;

  const parameterInfos: ParameterInformation[] = visibleParams.map((p) => {
    const paramLabel = p.placeholder ?? p.name;
    const startIdx = label.indexOf(paramLabel);
    return {
      label: [startIdx, startIdx + paramLabel.length] as [number, number],
      documentation: {
        kind: "markdown",
        value: [
          `**${p.name}**: ${p.type}`,
          p.examples
            ? `\nExamples: ${p.examples.map((e) => `\`${e}\``).join(", ")}`
            : "",
        ].join(""),
      },
    };
  });

  return {
    signatures: [{
      label,
      documentation: { kind: "markdown", value: funcMeta.doc },
      parameters: parameterInfos,
      activeParameter: Math.min(activeParam, visibleParams.length - 1),
    }],
    activeSignature: 0,
    activeParameter: Math.min(activeParam, visibleParams.length - 1),
  };
}
```

**Example LSP response when cursor is at `users |> sort-by |`:**

```json
{
  "signatures": [{
    "label": "sort-by _.field",
    "documentation": {
      "kind": "markdown",
      "value": "Sort a collection by a key function."
    },
    "parameters": [{
      "label": [8, 15],
      "documentation": {
        "kind": "markdown",
        "value": "**key**: function\n\nExamples: `_.age`, `_.name`, `_.score`"
      }
    }],
    "activeParameter": 0
  }],
  "activeSignature": 0,
  "activeParameter": 0
}
```

Note: only `key` is shown because `collection` has `pipeFirst: true` and we are
in a pipe context.

### 3.3 Completion Snippets with Tab-Stops

When completing a function name, the LSP provides a snippet with tab-stop
placeholders:

```typescript
// For `sort-by` after |>:
{
  label: "sort-by",
  kind: CompletionItemKind.Function,
  detail: "sort-by key",
  insertText: "sort-by ${1:_.field}",
  insertTextFormat: InsertTextFormat.Snippet,
}

// For `filter` after |>:
{
  label: "filter",
  kind: CompletionItemKind.Function,
  detail: "filter predicate",
  insertText: "filter ${1:[item -> ${2:condition}]}",
  insertTextFormat: InsertTextFormat.Snippet,
}

// For `reduce` after |>:
{
  label: "reduce",
  kind: CompletionItemKind.Function,
  detail: "reduce reducer [init]",
  insertText: "reduce ${1:[acc item -> ${2:expression}]}${3: ${4:0}}",
  insertTextFormat: InsertTextFormat.Snippet,
}

// For `format` (not piped -- first param is template, not pipe-first):
{
  label: "format",
  kind: CompletionItemKind.Function,
  detail: "format template args...",
  insertText: "format ${1:\"%s\"} ${2:value}",
  insertTextFormat: InsertTextFormat.Snippet,
}

// For `take` after |>:
{
  label: "take",
  kind: CompletionItemKind.Function,
  detail: "take count",
  insertText: "take ${1:10}",
  insertTextFormat: InsertTextFormat.Snippet,
}
```

### 3.4 Inlay Hints for Parameter Names

For existing function calls, inlay hints display parameter names inline:

```
users |> filter  predicate: _.active |> take  count: 10
                 ^^^^^^^^^              ^^^^^^
                 (inlay hint)           (inlay hint)
```

```typescript
// inlay-hints.ts
function provideInlayHints(
  params: InlayHintParams,
  tree: Parser.Tree,
  metadata: StdlibMetadata,
): InlayHint[] {
  const hints: InlayHint[] = [];
  const calls = findAllCallExpressions(tree, params.range);

  for (const call of calls) {
    const funcMeta = metadata.lookup(call.functionName);
    if (!funcMeta) continue;

    const visibleParams = call.isPiped
      ? funcMeta.params.filter((p) => !p.pipeFirst)
      : funcMeta.params;

    for (
      let i = 0; i < Math.min(call.arguments.length, visibleParams.length); i++
    ) {
      const arg = call.arguments[i];
      const param = visibleParams[i];

      // Skip if the argument text already matches the param name
      if (arg.text === param.name) continue;

      hints.push({
        position: {
          line: arg.startPosition.row,
          character: arg.startPosition.column,
        },
        label: `${param.name}:`,
        kind: InlayHintKind.Parameter,
        paddingRight: true,
      });
    }
  }

  return hints;
}
```

### 3.5 Enum-Like Parameter Suggestions

Some parameters have a finite set of valid values. When the cursor is on such a
parameter, the LSP provides targeted completions. This is not a special protocol
-- it uses `textDocument/completion` triggered at the right position:

```clojure
;; In stdlib-metadata.edn, enum-like params use :enum
{:name "sort-by"
 :params [{:name "collection" :type "list" :pipe-first true}
          {:name "key" :type "function" :placeholder "_.field"}
          {:name "direction" :type "keyword" :optional true
           :enum [:asc :desc]
           :placeholder ":asc"}]}
```

The completion provider checks if the current argument position maps to an
`:enum` param and offers only those values:

```typescript
if (activeParam && activeParam.enum) {
  return activeParam.enum.map((v) => ({
    label: v,
    kind: CompletionItemKind.EnumMember,
    sortText: "0", // show first
    preselect: v === activeParam.enum[0], // preselect first option
  }));
}
```

### 3.6 Metadata Storage Strategy

The metadata lives in two forms:

1. **Source of truth**: `resources/stdlib-metadata.edn` in the DataTwist repo.
   Maintained alongside `stdlib.clj`. A Clojure script validates that every
   function in `default-env` has a corresponding metadata entry.

2. **LSP consumption**: `stdlib.json` generated from the EDN file and bundled
   with the LSP server npm package. Generation is a build step:

```bash
# In Makefile:
metadata:
	clj -M -e "(require 'datatwist.metadata-gen) (datatwist.metadata-gen/generate-json)"
```

The generator reads the EDN, validates against `default-env`, and writes JSON.
This runs at build time, not at LSP startup.

---

## 4. Scope Analysis

### 4.1 Binding Resolution for `is`

DataTwist uses sequential `is` bindings with lexical scope. The scope analyzer
builds a binding table by walking the Tree-sitter CST top-down:

```typescript
// scope.ts

interface Binding {
  name: string;
  node: Parser.SyntaxNode; // the identifier node
  valueNode: Parser.SyntaxNode; // the RHS of `is`
  scope: ScopeId;
  type?: InferredType; // from basic type inference
}

interface Scope {
  id: ScopeId;
  parent: ScopeId | null;
  bindings: Map<string, Binding>;
  kind: "program" | "function" | "pipeline" | "guard";
}

class ScopeAnalyzer {
  private scopes: Map<ScopeId, Scope> = new Map();
  private nextId = 0;

  analyze(tree: Parser.Tree): void {
    const root = this.createScope(null, "program");
    this.walkNode(tree.rootNode, root);
  }

  private walkNode(node: Parser.SyntaxNode, scopeId: ScopeId): void {
    switch (node.type) {
      case "binding": {
        const name = node.childForFieldName("name");
        const value = node.childForFieldName("value");
        if (name && value) {
          this.addBinding(scopeId, {
            name: name.text,
            node: name,
            valueNode: value,
            scope: scopeId,
          });
        }
        // Also handle destructuring patterns
        const pattern = node.childForFieldName("pattern");
        if (pattern) {
          this.extractPatternBindings(pattern, scopeId);
        }
        break;
      }

      case "function_definition": {
        // New scope for function body
        const fnScope = this.createScope(scopeId, "function");
        // Register parameters in the new scope
        const paramList = node.descendantsOfType("parameter_list")[0];
        if (paramList) {
          for (const param of paramList.namedChildren) {
            if (param.type === "identifier") {
              this.addBinding(fnScope, {
                name: param.text,
                node: param,
                valueNode: param,
                scope: fnScope,
              });
            } else if (param.type === "rest_element") {
              const restName = param.namedChildren[0];
              if (restName) {
                this.addBinding(fnScope, {
                  name: restName.text,
                  node: restName,
                  valueNode: restName,
                  scope: fnScope,
                });
              }
            } else if (
              param.type === "object_pattern" || param.type === "list_pattern"
            ) {
              this.extractPatternBindings(param, fnScope);
            }
          }
        }
        // Walk function body in new scope
        const body = node.descendantsOfType("function_body")[0];
        if (body) this.walkChildren(body, fnScope);
        return; // don't recurse into children again
      }

      case "pipeline":
      case "sourceless_pipeline": {
        // Each pipe step gets access to _ as implicit binding
        const pipeScope = this.createScope(scopeId, "pipeline");
        this.addBinding(pipeScope, {
          name: "_",
          node: node,
          valueNode: node.childForFieldName("source") ?? node,
          scope: pipeScope,
          type: { kind: "pipeline-context" },
        });
        this.walkChildren(node, pipeScope);
        return;
      }
    }

    this.walkChildren(node, scopeId);
  }

  /** Resolve a name at a given position to its binding. */
  resolve(name: string, position: Point, scopeId: ScopeId): Binding | null {
    const scope = this.scopes.get(scopeId);
    if (!scope) return null;

    // Check current scope (only bindings defined BEFORE this position)
    const binding = scope.bindings.get(name);
    if (binding && isBefore(binding.node.endPosition, position)) {
      return binding;
    }

    // Walk up to parent scope
    if (scope.parent !== null) {
      return this.resolve(name, position, scope.parent);
    }

    return null;
  }

  private extractPatternBindings(
    patternNode: Parser.SyntaxNode,
    scopeId: ScopeId,
  ): void {
    // Recursively extract bound names from destructuring patterns
    for (const child of patternNode.namedChildren) {
      if (child.type === "identifier") {
        this.addBinding(scopeId, {
          name: child.text,
          node: child,
          valueNode: child,
          scope: scopeId,
        });
      } else if (
        child.type === "object_pattern" || child.type === "list_pattern"
      ) {
        this.extractPatternBindings(child, scopeId);
      } else if (child.type === "rest_element") {
        const restName = child.namedChildren[0];
        if (restName?.type === "identifier") {
          this.addBinding(scopeId, {
            name: restName.text,
            node: restName,
            valueNode: restName,
            scope: scopeId,
          });
        }
      }
    }
  }
}
```

### 4.2 Go-to-Definition

Uses the scope analyzer to find the binding definition:

```typescript
// definition.ts
function provideDefinition(
  params: DefinitionParams,
  tree: Parser.Tree,
  scopeAnalyzer: ScopeAnalyzer,
): Location | null {
  const node = nodeAtPosition(tree, params.position);
  if (node.type !== "identifier" && node.type !== "wildcard") return null;

  const scopeId = scopeAnalyzer.scopeAt(params.position);
  const binding = scopeAnalyzer.resolve(node.text, node.startPosition, scopeId);

  if (!binding) return null;

  return {
    uri: params.textDocument.uri,
    range: nodeRange(binding.node),
  };
}
```

### 4.3 Pipeline `_` Context Tracking

The wildcard `_` is context-overloaded in DataTwist:

- In a pipeline: refers to the current element being processed
- In a guard block: refers to the matched value
- In destructuring: means "skip this position"

The scope analyzer handles this by creating nested scopes for each pipeline
step:

```typescript
// pipeline.ts

interface PipelineContext {
  sourceNode: Parser.SyntaxNode; // what produces the data
  currentStep: number; // which |> step we're in
  inferredElementType?: string; // type of each element (if known)
}

function analyzePipeline(
  pipelineNode: Parser.SyntaxNode,
  parentScope: ScopeId,
  analyzer: ScopeAnalyzer,
): void {
  const steps = pipelineNode.namedChildren;
  let prevStepScope = parentScope;

  for (let i = 0; i < steps.length; i++) {
    const step = steps[i];
    // Each step creates a scope where _ refers to the element from previous step
    const stepScope = analyzer.createScope(prevStepScope, "pipeline");
    analyzer.addBinding(stepScope, {
      name: "_",
      node: step,
      valueNode: i === 0 ? step : steps[i - 1],
      scope: stepScope,
      type: inferStepOutputType(steps, i),
    });
    analyzer.walkNode(step, stepScope);
    prevStepScope = stepScope;
  }
}
```

For nested pipelines (e.g., `_.scores |> filter [s -> s > 80]` inside a `map`),
the inner pipeline creates a new scope that shadows the outer `_`. The scope
analyzer naturally handles this because the inner pipeline scope's parent is the
outer pipeline scope, and name resolution checks the innermost scope first.

### 4.4 Require Alias Resolution

`require clojure.string as str` creates a binding from `str` to the module
`clojure.string`. When the user writes `str/upper-case`, the LSP resolves `str`
to `clojure.string` and then looks up `upper-case` in that namespace.

```typescript
interface RequireBinding {
  alias: string;
  module: string;
  node: Parser.SyntaxNode;
}

function resolveQualifiedName(
  qualifiedName: string, // e.g., "str/upper-case"
  requires: RequireBinding[],
): { module: string; function: string } | null {
  const slashIdx = qualifiedName.indexOf("/");
  if (slashIdx < 0) return null;

  const prefix = qualifiedName.substring(0, slashIdx);
  const funcName = qualifiedName.substring(slashIdx + 1);

  const req = requires.find((r) => r.alias === prefix);
  if (!req) return null;

  return { module: req.module, function: funcName };
}
```

The LSP does not evaluate Clojure namespaces at design time. For Clojure interop
completion (`str/`), the LSP can ship a pre-built index of common namespaces
(`clojure.core`, `clojure.string`, `clojure.set`, etc.) and lazily load JVM
namespace metadata via a sidecar process if needed.

---

## 5. Integration Points

### 5.1 VSCode Extension

```
datatwist-vscode/
  package.json
  src/
    extension.ts           # Activate/deactivate, start LSP client
  syntaxes/
    datatwist.tmLanguage.json   # TextMate grammar (fallback for no Tree-sitter)
  language-configuration.json   # Bracket matching, comment toggling, auto-close
```

**package.json (relevant fields):**

```json
{
  "name": "datatwist-vscode",
  "displayName": "DataTwist",
  "description": "DataTwist language support: syntax highlighting, LSP, Tree-sitter",
  "categories": ["Programming Languages"],
  "activationEvents": ["onLanguage:datatwist"],
  "contributes": {
    "languages": [{
      "id": "datatwist",
      "aliases": ["DataTwist", "dt"],
      "extensions": [".dt", ".datatwist"],
      "configuration": "./language-configuration.json"
    }],
    "grammars": [{
      "language": "datatwist",
      "scopeName": "source.datatwist",
      "path": "./syntaxes/datatwist.tmLanguage.json"
    }]
  },
  "dependencies": {
    "vscode-languageclient": "^9.0.0"
  }
}
```

**language-configuration.json:**

```json
{
  "comments": {
    "lineComment": "//"
  },
  "brackets": [
    ["{", "}"],
    ["[", "]"],
    ["(", ")"]
  ],
  "autoClosingPairs": [
    { "open": "{", "close": "}" },
    { "open": "[", "close": "]" },
    { "open": "(", "close": ")" },
    { "open": "\"", "close": "\"" },
    { "open": "'", "close": "'" }
  ],
  "surroundingPairs": [
    { "open": "{", "close": "}" },
    { "open": "[", "close": "]" },
    { "open": "(", "close": ")" },
    { "open": "\"", "close": "\"" }
  ],
  "indentationRules": {
    "increaseIndentPattern": "^.*[{\\[]\\s*$|^.*\\|>\\s*$",
    "decreaseIndentPattern": "^\\s*[}\\]]"
  },
  "wordPattern": "[a-zA-Z][a-zA-Z0-9_\\-]*[?!]?"
}
```

**extension.ts:**

```typescript
import * as path from "path";
import { ExtensionContext, workspace } from "vscode";
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
  TransportKind,
} from "vscode-languageclient/node";

let client: LanguageClient;

export function activate(context: ExtensionContext) {
  const serverModule = context.asAbsolutePath(
    path.join("server", "out", "server.js"),
  );

  const serverOptions: ServerOptions = {
    run: { module: serverModule, transport: TransportKind.stdio },
    debug: { module: serverModule, transport: TransportKind.stdio },
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: "file", language: "datatwist" }],
    synchronize: {
      fileEvents: workspace.createFileSystemWatcher("**/*.dt"),
    },
  };

  client = new LanguageClient(
    "datatwist",
    "DataTwist Language Server",
    serverOptions,
    clientOptions,
  );
  client.start();
}

export function deactivate(): Thenable<void> | undefined {
  return client?.stop();
}
```

**Tree-sitter integration in VSCode**: VSCode has experimental Tree-sitter
support via the `vscode.treeSitter` API (behind a feature flag). Until it
stabilizes, the extension uses the TextMate grammar for syntax highlighting and
relies on Tree-sitter only inside the LSP server for semantic analysis. When
VSCode's Tree-sitter support becomes stable, the extension will ship
`tree-sitter-datatwist.wasm` and reference it in `package.json`.

### 5.2 Neovim

Neovim has native Tree-sitter support via `nvim-treesitter`. Setup:

**1. Install the Tree-sitter grammar:**

```lua
-- In nvim-treesitter config (lazy.nvim example):
require('nvim-treesitter.configs').setup {
  ensure_installed = { 'datatwist' },
  highlight = { enable = true },
}

-- Register the parser (before nvim-treesitter is configured):
local parser_config = require('nvim-treesitter.parsers').get_parser_configs()
parser_config.datatwist = {
  install_info = {
    url = 'https://github.com/datatwist/tree-sitter-datatwist',
    files = { 'src/parser.c', 'src/scanner.c' },
    branch = 'main',
  },
  filetype = 'datatwist',
}
```

**2. Highlight queries**: Ship `queries/datatwist/highlights.scm` (from section
1.9) in the Tree-sitter grammar repo. `nvim-treesitter` picks these up
automatically.

**3. LSP via nvim-lspconfig:**

```lua
-- In lspconfig setup:
local lspconfig = require('lspconfig')
local configs = require('lspconfig.configs')

if not configs.datatwist then
  configs.datatwist = {
    default_config = {
      cmd = { 'datatwist-lsp', '--stdio' },
      filetypes = { 'datatwist' },
      root_dir = lspconfig.util.root_pattern('.git', 'deps.edn'),
      settings = {},
    },
  }
end

lspconfig.datatwist.setup {}
```

**4. File type detection:**

```lua
vim.filetype.add {
  extension = {
    dt = 'datatwist',
    datatwist = 'datatwist',
  },
}
```

Tree-sitter handles syntax highlighting (fast, incremental), while the LSP
handles diagnostics, completion, signature help, go-to-definition, etc. They
work independently -- Tree-sitter runs in Neovim's native C layer, while LSP
runs via the stdio language server.

### 5.3 JetBrains IDEs

JetBrains IDEs (IntelliJ, WebStorm, etc.) support LSP via the **LSP API** plugin
(built-in since 2023.2+):

**Option A: LSP Plugin (recommended)**

Create a JetBrains plugin that registers the DataTwist LSP:

```xml
<!-- plugin.xml -->
<idea-plugin>
  <id>com.datatwist.intellij</id>
  <name>DataTwist</name>
  <depends>com.intellij.modules.platform</depends>

  <extensions defaultExtensionNs="com.intellij">
    <fileType name="DataTwist" language="DataTwist"
              implementationClass="com.datatwist.intellij.DataTwistFileType"
              fieldName="INSTANCE" extensions="dt;datatwist"/>

    <lsp.serverSupportProvider
              implementation="com.datatwist.intellij.DataTwistLspServerSupportProvider"/>
  </extensions>
</idea-plugin>
```

```kotlin
// DataTwistLspServerSupportProvider.kt
class DataTwistLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter
    ) {
        if (file.extension == "dt" || file.extension == "datatwist") {
            serverStarter.ensureServerStarted(DataTwistLspServerDescriptor(project))
        }
    }
}

class DataTwistLspServerDescriptor(project: Project) :
    ProjectWideLspServerDescriptor(project, "DataTwist") {
    override fun createCommandLine(): GeneralCommandLine {
        return GeneralCommandLine("datatwist-lsp", "--stdio")
    }

    override fun isSupportedFile(file: VirtualFile): Boolean {
        return file.extension == "dt" || file.extension == "datatwist"
    }
}
```

**Option B: TextMate bundle for syntax highlighting** (simpler, no plugin
needed)

JetBrains supports TextMate bundles via the TextMate Bundles plugin. Ship the
same `.tmLanguage.json` used by VSCode. This gives syntax highlighting but no
semantic features.

**Recommendation**: Ship both. The TextMate bundle provides immediate syntax
highlighting with zero setup. The LSP plugin adds full language intelligence.
Tree-sitter is not natively supported by JetBrains, so the LSP server handles
all semantic analysis internally using its embedded Tree-sitter WASM parser.

### 5.4 How Tree-sitter and LSP Work Together

```
                Editor
               /      \
      Tree-sitter      LSP Client
      (native)         (stdio)
         |                |
Syntax highlighting    datatwist-lsp (Node.js)
Bracket matching           |
Code folding         Tree-sitter WASM
Indentation          (incremental parsing)
                           |
                     Scope Analysis
                     Stdlib Metadata
                           |
                +---------+---------+
                |         |         |
          Diagnostics  Completion  Signature Help
          Hover        Definition  References
          Inlay Hints  Rename      Semantic Tokens
```

**Division of labor:**

| Feature             | Provider                        | Rationale                                         |
| ------------------- | ------------------------------- | ------------------------------------------------- |
| Syntax highlighting | Tree-sitter (editor-native)     | Fastest: runs in editor's event loop, incremental |
| Code folding        | Tree-sitter (editor-native)     | Based on named node boundaries                    |
| Bracket matching    | Editor (language-configuration) | Simple declarative config                         |
| Auto-indent         | Editor (language-configuration) | Regex-based indent rules                          |
| Diagnostics         | LSP server (Tree-sitter WASM)   | Needs ERROR node analysis + custom rules          |
| Completion          | LSP server                      | Needs scope analysis + stdlib metadata            |
| Signature help      | LSP server                      | Needs stdlib metadata + pipe context              |
| Hover               | LSP server                      | Needs stdlib metadata + scope                     |
| Go-to-definition    | LSP server                      | Needs scope analysis                              |
| Find references     | LSP server                      | Needs scope analysis                              |
| Rename              | LSP server                      | Needs scope analysis (all references)             |
| Semantic tokens     | LSP server                      | Distinguishes stdlib vs user functions            |
| Inlay hints         | LSP server                      | Needs stdlib metadata + call analysis             |

The LSP server embeds its own Tree-sitter WASM instance for parsing, independent
of the editor's native Tree-sitter. This means:

- The editor's Tree-sitter handles visual features (highlighting, folding).
- The LSP's Tree-sitter handles semantic features (scope, diagnostics).
- Both parse the same grammar, so they always agree on structure.
- The LSP's tree is updated on every `textDocument/didChange` notification using
  Tree-sitter's incremental parsing (sub-millisecond for typical edits).

### 5.5 Distribution

The LSP server is distributed as an npm package:

```bash
npm install -g datatwist-lsp
```

This installs the `datatwist-lsp` binary (Node.js script) that editors invoke.
The Tree-sitter WASM binary is bundled inside the npm package.

The Tree-sitter grammar is a separate repository (`tree-sitter-datatwist`) that
editors install via their native package managers:

- Neovim: `:TSInstall datatwist`
- VSCode: bundled in the extension
- JetBrains: not used (LSP-only)

---

## Appendix A: Full Stdlib Metadata Coverage

Functions from `default-env` in `stdlib.clj` that need metadata entries:

**Collection (pipe-first, first param is collection):** `count`, `first`,
`last`, `nth`, `rest`, `keys`, `vals`/`values`, `get`, `contains?`, `empty?`,
`merge`, `assoc`, `dissoc`, `conj`, `concat`, `into`, `select-keys`, `update`,
`append`, `prepend`, `length`/`size`, `map`, `filter`, `reduce`, `each`,
`group-by`, `sort`, `sort-by`, `take`, `drop`, `sum`, `average`/`avg`,
`flatten`, `distinct`, `reverse`, `zip`, `partition`, `frequencies`, `join`

**String (pipe-first, first param is string):** `replace`, `split`,
`upper-case`, `lower-case`, `trim`, `starts-with?`, `ends-with?`, `includes?`,
`substring`

**Non-pipe-first (first param is NOT the piped data):** `format`, `str`,
`range`, `apply`, `partial`, `comp`, `identity`, `constantly`

**Type checking (pipe-first):** `type`/`type-of`, `str?`, `int?`, `float?`,
`bool?`, `nil?`, `fn?`, `map?`, `vec?`

**Type conversion (pipe-first):** `int`, `double`, `to-string`, `to-int`,
`to-float`

**Math (pipe-first):** `inc`, `dec`, `max`, `min`, `abs`, `sqrt`, `round`,
`ceil`, `floor`, `pow`, `even?`, `odd?`

**Atoms (not pipe-first):** `atom`, `deref`, `reset!`, `swap!`

**Side effects (pipe-first, passthrough):** `log!`, `tap!`, `save!`, `print`,
`println`

Total: ~80 functions requiring metadata entries.

## Appendix B: Tree-sitter `locals.scm` for Scope Resolution

Tree-sitter queries can define scope and reference relationships that editors
use for local variable highlighting:

```scheme
; queries/locals.scm

; Scopes
(program) @local.scope
(function_definition) @local.scope
(function_body) @local.scope

; Definitions
(binding name: (identifier) @local.definition)
(parameter_list (identifier) @local.definition)
(rest_element (identifier) @local.definition)
(require_statement alias: (identifier) @local.definition)
(catch_clause target: (identifier) @local.definition)

; References
(identifier) @local.reference
(wildcard) @local.reference
```

This enables local variable highlighting in Neovim and other editors that
support Tree-sitter `locals` queries -- occurrences of the same binding are
highlighted together.
