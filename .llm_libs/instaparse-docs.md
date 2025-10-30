# Instaparse Library Documentation

## Overview
Instaparse is a Clojure library for building parsers from context-free grammars using EBNF (Extended Backus-Naur Form) syntax. It provides powerful parsing capabilities with automatic error handling, tree transformation, and debugging features.

## Key Features
- EBNF/ABNF grammar support with multiple syntax styles
- Automatic whitespace handling and tokenization
- Parse tree visualization and transformation
- Comprehensive error handling and failure detection
- Performance optimization options
- Debugging with tracing and profiling

---

## 1. Basic Instaparse Syntax and EBNF Grammar Format

### Core Parser Creation
```clojure
(ns example.core
  (:require [instaparse.core :as insta]))

;; Basic EBNF grammar
(def simple-parser
  (insta/parser
    "S = AB*
     AB = A B
     A = 'a'+
     B = 'b'+"))

;; Alternative EBNF notations supported
(def alternative-parser
  (insta/parser
    "S:={AB}  ;
     AB ::= (A, B)
     A : \"a\" + ;
     B ='b' + ;"))
```

### EBNF Syntax Elements
- **String literals**: `'hello'` or `"hello"`
- **Regular expressions**: `#'pattern'` 
- **Alternation**: `|`
- **Concatenation**: space-separated
- **Quantifiers**: `*` (zero or more), `+` (one or more), `?` (optional)
- **Hidden rules**: `<rule>` (excludes from parse tree)
- **Ordered choice**: `/` (try alternatives in order)

### Grammar Rule Examples
```instaparse
;; Basic repetition
S = 'a'+

;; Complex token with regex
identifier = #'[a-zA-Z][a-zA-Z0-9]*'

;; Hidden intermediate nodes
<expression> = term (('+' | '-') term)*

;; Ordered choice for disambiguation
<token> = keyword / identifier
```

---

## 2. Whitespace and Tokenization

### Auto-Whitespace Feature
```clojure
;; Standard whitespace handling
(def parser-with-whitespace
  (insta/parser
    "sentence = token+
     <token> = word | number
     word = #'[a-zA-Z]+'
     number = #'[0-9]+'"
    :auto-whitespace :standard))

;; Custom whitespace (includes commas)
(def comma-whitespace-parser
  (insta/parser
    "expression = term (',' term)*"
    :auto-whitespace :comma))
```

### Custom Whitespace Parsers
```clojure
;; Simple whitespace parser
(def whitespace
  (insta/parser "whitespace = #'\\s+'"))

;; Whitespace with comments support
(def whitespace-or-comments
  (insta/parser
    "ws-or-comments = #'\\s+' | comments
     comments = comment+
     comment = '(*' inside-comment* '*)'
     inside-comment = !( '*)' | '(*' ) #'.' | comment"))

;; Use custom whitespace
(def parser-with-comments
  (insta/parser
    "sentence = token+
     <token> = word | number
     word = #'[a-zA-Z]+'
     number = #'[0-9]+'"
    :auto-whitespace whitespace-or-comments))
```

### Tokenization Best Practices
```instaparse
;; Prefer string literals for fixed tokens
keyword = 'defn' | 'let' | 'if'

;; Use regex for patterns
identifier = #'[a-zA-Z][a-zA-Z0-9]*'
number = #'[0-9]+'

;; Avoid character-by-character for performance
;; Good:
word = #'[a-zA-Z]+'
;; Avoid:
word = letter+
letter = #'[a-zA-Z]'
```

---

## 3. Error Handling and Failure Detection

### Parse Functions and Error Modes
```clojure
;; Standard parsing - returns failure object on error
(def result (parser "invalid input"))
(when (insta/failure? result)
  (println "Parse failed:" (insta/get-failure result)))

;; Total mode - embeds failure in parse tree
(def total-result (parser "partial input" :total true))
;; Returns tree with :instaparse/failure nodes

;; Partial mode - allows incomplete parses
(def partial-result (parser "partial" :partial true))
;; Returns successful parse of consumed portion
```

### Failure Detection Patterns
```clojure
;; Check for any type of failure
(defn parse-success? [result]
  (not (insta/failure? result)))

;; Extract detailed error information
(defn get-error-details [result]
  (when (insta/failure? result)
    (let [failure (insta/get-failure result)]
      {:reason (:reason failure)
       :line (:line failure)
       :column (:column failure)
       :index (:index failure)})))

;; Safe parsing with error handling
(defn safe-parse [parser input]
  (let [result (parser input)]
    (if (insta/failure? result)
      {:success false :error (insta/get-failure result)}
      {:success true :result result})))
```

### Handling Ambiguity
```clojure
;; Get all possible parses for ambiguous input
(def all-parses (insta/parses ambiguous-parser "ambiguous input"))

;; Transform each possible parse
(def transformed-results
  (map #(insta/transform transform-map %) all-parses))
```

---

## 4. Best Practices for Grammar Design

### Performance Optimization
```instaparse
;; Use quantifiers instead of recursion
;; Good:
<A> = 'a'+
;; Avoid:
<A> = 'a' A | 'a'

;; Prefer full regex tokens
;; Good:
Identifier = #'[a-zA-Z][a-zA-Z0-9]*'
;; Avoid:
Identifier = Letter Digit*
Letter = #'[a-zA-Z]'
Digit = #'[0-9]'

;; Use string literals for fixed strings
;; Good:
'apple'
;; Avoid:
#'apple'
```

### Disambiguation Strategies
```instaparse
;; Ordered choice for keyword/identifier conflict
<token> = keyword / identifier
keyword = 'defn' | 'let' | 'if'
identifier = #'[a-zA-Z][a-zA-Z0-9]*'

;; Negative lookahead for exclusions
<identifier> = !keyword word
word = #'[a-zA-Z]+'

;; Hidden rules to flatten tree structure
<expression> = term (('+' | '-') term)*
```

### Grammar Organization
```instaparse
;; Clear separation of concerns
program = statement*
statement = assignment | expression | control-flow
assignment = identifier '=' expression
control-flow = if-statement | while-loop

;; Reusable components
<identifier> = #'[a-zA-Z][a-zA-Z0-9]*'
<number> = #'[0-9]+'
<string> = '\"' #'[^\"]*' '\"'
```

---

## 5. Debugging Parse Trees and Issues

### Tracing and Profiling
```clojure
;; Enable tracing for detailed debugging
(parser "input" :trace true)

;; Example trace output:
;; Initiating parse: S at index 0 (input)
;; Result for S at index 0 => [:S ...]
;; Successful parse.

;; Memory optimization for large inputs
(parser "large input" :optimize :memory)
```

### Parse Tree Inspection
```clojure
;; Get span information for nodes
(defn spans [tree]
  (if (sequential? tree)
    (cons (insta/span tree) (map spans (next tree)))
    tree))

;; Add line/column metadata
(def tree-with-metadata
  (insta/add-line-and-column-info-to-metadata 
    input-text 1 1 parse-tree))

;; Inspect metadata
(meta parse-tree)  ; => {:line 1, :column 1, ...}
```

### Visualization
```clojure
;; Visualize parse tree (requires rhizome and graphviz)
(insta/visualize parse-tree)

;; Save visualization to file
(insta/visualize parse-tree 
  :output-file "parse-tree.png"
  :options {:dpi 96})
```

### Common Debugging Techniques
```clojure
;; Test individual rules
(defn test-rule [parser rule-name input]
  (parser input :start rule-name))

;; Compare expected vs actual structure
(defn compare-trees [expected actual]
  (= expected actual))

;; Step-by-step parsing with partial results
(defn step-through [parser input]
  (for [i (range 1 (inc (count input)))]
    (parser (subs input 0 i) :partial true)))
```

---

## 6. Common Pitfalls and Solutions

### Left Recursion
```instaparse
;; Problematic left recursion
;; S = S 'a' | 'a'  ; This will cause infinite recursion

;; Solution: right recursion or iteration
;; S = 'a' S*  ; Right recursive
;; S = 'a'+    ; Using quantifier
```

### Ambiguity Issues
```instaparse
;; Ambiguous grammar
;; S = 'ab'+ | 'a' 'ba'+

;; Solutions:
;; 1. Refactor to eliminate ambiguity
;; S = 'a' 'b' 'ab'*
;; 2. Use ordered choice
;; S = 'ab'+ / 'a' 'ba'+
;; 3. Use negative lookahead
;; S = !('a' 'ba'+) 'ab'+ | 'a' 'ba'+
```

### Whitespace Handling
```clojure
;; Common mistake: mixing auto-whitespace with manual whitespace
;; Problem:
(def parser
  (insta/parser
    "expr = term whitespace term"
    :auto-whitespace :standard))

;; Solution: let auto-whitespace handle it
(def parser
  (insta/parser
    "expr = term term"
    :auto-whitespace :standard))
```

### Performance Issues
```instaparse
;; Avoid: character-by-character parsing
;; word = letter+
;; letter = #'[a-zA-Z]'

;; Prefer: single regex
;; word = #'[a-zA-Z]+'

;; Avoid: deep recursion for simple repetition
;; list = item list | item

;; Prefer: quantifiers
;; list = item+
```

### Memory Management
```clojure
;; For large inputs, use memory optimization
(def result (large-parser input :optimize :memory))

;; Process large documents in chunks
(defn process-large-document [lines parser]
  (map parser lines))
```

---

## Usage Notes for DataTwist Project

1. **Start Simple**: Begin with basic grammar rules and gradually add complexity
2. **Use Auto-Whitespace**: Leverage `:auto-whitespace :standard` for most cases
3. **Test Incrementally**: Use `:partial true` to debug partial matches
4. **Handle Errors Gracefully**: Always check `(insta/failure? result)`
5. **Profile Performance**: Use `:trace true` to identify bottlenecks
6. **Visualize Trees**: Use `insta/visualize` to understand parse structure
7. **Transform Results**: Use `insta/transform` to convert parse trees to useful data structures

This comprehensive documentation should provide you with all the necessary information to effectively use Instaparse in your DataTwist grammar parsing project.