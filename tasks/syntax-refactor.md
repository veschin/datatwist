# DataTwist Grammar Refactor Tasks

## Phase 2 Complete ✅ - Pipeline Structure Simplification

### ✅ COMPLETED: Pipeline Structure Simplification

**Before (Complex)**:
```
[:indented-pipeline 
  [:pipeline-source [:identifier users]]
  [:newline]
  [:indented-pipeline-body 
    [:indented-pipeline-ops 
      [:indented-pipeline-op 
        [:indent   ]
        [:specific-pipeline-op [:indented-filter-op ...]]]
      [:newline]
      [:indented-pipeline-op 
        [:indent   ]
        [:specific-pipeline-op [:indented-map-op ...]]]]]]
```

**After (Simplified)**:
```
[:pipeline 
  [:pipeline-source [:identifier users]]
  [:pipeline-operations 
    [:pipeline-operation [:filter-op [:operation-arguments ...]]]
    [:pipeline-operation [:map-op [:operation-arguments ...]]]]]
```

**Key Improvements**:
- ✅ Replaced `:indented-pipeline` with simple `:pipeline`
- ✅ Direct operation nodes: `:filter-op`, `:map-op`, `:take-op`, `:group-by-op`, `:sum-op`, `:count-op`, `:average-op`
- ✅ User-defined operations: `:user-defined-op`
- ✅ Removed unnecessary indentation wrappers
- ✅ All tests passing (293/293)

**Examples**:
- `users\n  filter _.age > 18` → `[:pipeline [:filter-op ...]]`
- `data\n  take 5\n  sum` → `[:pipeline [:take-op ...] [:sum-op]]`
- `data\n  custom-op arg` → `[:pipeline [:user-defined-op ...]]`

---

## Remaining Issues (Phase 3+)

### 1. Identifier Redundancy
**Problem**: `[:user-identifier "name"]` instead of `[:identifier "name"]`
- All `user-identifier` nodes should be simplified to `identifier`
- The distinction between user-identifier and identifier is unnecessary in the parse tree

### 2. Assignment vs Comparison
**Problem**: Assignment uses `[:comparison-op "="]` instead of dedicated assignment operator
- Assignment (`=`) should be parsed as `[:assignment-op]` or similar, not as comparison
- This semantically distinguishes assignment from equality comparison

### 3. List Structure Over-complication
**Problem**: `[:list [:single-line-list [:list-element ...]]]` instead of `[:list [...]]`
- `single-line-list` vs `multi-line-list` distinction is irrelevant for parse tree semantics
- `list-element` wrapper is unnecessary - list should contain elements directly
- Result should be: `[:list [:object ...] [:object ...] [:object ...]]`

### 4. Object Structure Simplification
**Problem**: `[:object [:single-line-object [:field ...]]]` instead of `[:object [:field ...]]`
- `single-line-object` vs `multi-line-object` distinction is not needed in parse tree
- Object should directly contain its fields

### 5. Field Structure
**Problem**: `[:field [:user-identifier "name"] [:field-value [:same-line-field-value [:expr ...]]]]`
- Field keys should be `[:identifier "name"]`, not `[:user-identifier "name"]`
- `field-value` wrapper with `same-line-field-value` is over-complicated
- Should be: `[:field [:identifier "name"] [:expr ...]]`

### 6. String Representation
**Problem**: Strings are split into individual characters: `[:string [:regular-char "A"] [:regular-char "l"] ...]`
- Strings should be represented as single nodes: `[:string "Alice"]`
- Character-by-character parsing is unnecessary for most use cases

### 7. Number Structure
**Problem**: Numbers have unnecessary nesting: `[:number [:integer "25"]]`
- Should be simplified to `[:number 25]` or `[:integer 25]`
- Float vs integer distinction can be handled in the value, not node structure

## Required Grammar Changes

### 1. Simplify Identifier Rules
```clojure
;; Current
identifier = user-identifier
user-identifier = #'(?!(?:true|false|nil)$)[a-zA-Z][a-zA-Z0-9\-_]*[?]?'

;; Should be
identifier = #'(?!(?:true|false|nil)$)[a-zA-Z][a-zA-Z0-9\-_]*[?]?'
```

### 2. Add Assignment Operator
```clojure
;; Current
assignment = user-identifier <opt-ws> <'='> <opt-ws> expr

;; Should be
assignment = identifier <opt-ws> <assignment-op> <opt-ws> expr
assignment-op = '='
```

### 3. Simplify List Rules
```clojure
;; Current
list = single-line-list / multi-line-list
single-line-list = <'['> <opt-ws> list-elements <opt-ws> <']'>
<list-elements> = list-element (<opt-ws> (list-element / comment))* | comment* | #''
list-element = literal | object | list | function | wildcard-access | identifier

;; Should be
list = <'['> <opt-ws> (list-element (<opt-ws> list-element)*)? <opt-ws> <']'>
list-element = literal | object | list | function | wildcard-access | identifier
```

### 4. Simplify Object Rules
```clojure
;; Current
object = single-line-object / multi-line-object
single-line-object = <'{'> <opt-ws> object-fields <opt-ws> <'}'>
<object-fields> = field (<opt-ws> (field / comment))* | comment* | #''

;; Should be
object = <'{'> <opt-ws> (field (<opt-ws> field)*)? <opt-ws> <'}'>
```

### 5. Simplify Field Rules
```clojure
;; Current
field = user-identifier <opt-ws> <':'> <opt-ws> field-value
field-value = multi-line-object / same-line-field-value / multi-line-field-value / field-multi-line-object
same-line-field-value = expr / multi-line-object / same-line-field-pipeline

;; Should be
field = identifier <opt-ws> ':' <opt-ws> expr
```

### 6. Simplify String Rules
```clojure
;; Current
string = <'\"'> (escaped-char | regular-char)* <'\"'>
regular-char = #'[^\"]'

;; Should be
string = <'\"'> #'[^\"]*' <'\"'>
```

### 7. Simplify Number Rules
```clojure
;; Current
number = integer ('.' integer)?
integer = #'-?[0-9]+'

;; Should be
number = #'-?[0-9]+(\.[0-9]+)?'
```

## Design Principles for Parse Tree Structure

### 1. Maximum Simplicity
- Each node should contain the minimum necessary information
- Avoid unnecessary wrapper nodes that don't add semantic value
- Direct representation of language constructs

### 2. Easy Traversal
- Consistent node patterns across similar constructs
- Predictable structure for programmatic processing
- Minimal nesting depth where possible

### 3. Clear Distinction
- Each language construct should have unique, identifiable node type
- No ambiguity between different syntactic elements
- Clear separation of syntax vs semantics

### 4. Semantic Accuracy
- Parse tree should reflect the actual meaning of the code
- Assignment should be distinct from comparison
- Different data types should be clearly distinguishable

## Expected Parse Tree Examples

### Before (Current):
```clojure
[:assignment
 [:user-identifier "result"]
 [:expr
  [:function-call
   [:user-identifier "filtered-users"]
   [:bare-arguments
    [:expr
     [:object
      [:single-line-object
       [:field
        [:user-identifier "name"]
        [:field-value
         [:same-line-field-value
          [:expr
           [:literal
            [:string
             [:regular-char "A"]
             [:regular-char "l"]
             [:regular-char "i"]
             [:regular-char "c"]
             [:regular-char "e"]]]]]]]]]]]]]]
```

### After (Target):
```clojure
[:assignment
 [:identifier "result"]
 [:expr
  [:function-call
   [:identifier "filtered-users"]
   [:bare-arguments
    [:expr
     [:object
      [:field
       [:identifier "name"]
       [:expr [:literal [:string "Alice"]]]]]]]]]]
```

### List Example:
```clojure
;; Before
[:list
 [:single-line-list
  [:list-element [:object [:field ...]]]
  [:list-element [:object [:field ...]]]]]

;; After  
[:list
 [:object [:field ...]]
 [:object [:field ...]]]
```

### Pipeline Example:
```clojure
;; Before
[:indented-pipeline
 [:pipeline-source [:identifier [:user-identifier "users"]]]
 [:indented-pipeline-body
  [:indented-pipeline-ops
   [:indented-pipeline-op
    [:indent "  "]
    [:specific-pipeline-op
     [:indented-filter-op
      [:operation-arguments
       [:expr
        [:wildcard-access "_" [:identifier [:user-identifier "age"]]]
        [:comparison-op ">"]
        [:literal [:number [:integer "18"]]]]]]]]]]]]]]

;; After
[:pipeline
 [:identifier "users"]
 [:operation
  [:identifier "filter"]
  [:wildcard-access "_" [:identifier "age"]]
  [:comparison-op ">"]
  [:literal [:number 18]]]
 [:operation
  [:identifier "map"]
  [:object
   [:field [:identifier "name"] [:wildcard-access "_" [:identifier "name"]]]]]]]
```

### Expression Examples:
```clojure
;; Simple assignment
[:assignment
 [:identifier "x"]
 [:literal [:number 42]]]

;; Function call
[:function-call
 [:identifier "process"]
 [:identifier "data"]
 [:literal [:string "input"]]]

;; Object literal
[:object
 [:field [:identifier "name"] [:literal [:string "Alice"]]]
 [:field [:identifier "age"] [:literal [:number 25]]]]

;; List literal
[:list
 [:literal [:number 1]]
 [:literal [:number 2]]
 [:literal [:number 3]]]

;; Wildcard access
[:wildcard-access "_" [:identifier "name"] [:identifier "first"]]
```

## Additional Simplification Opportunities

### 1. Pipeline Structure
**Current**: Complex nested structure with `indented-pipeline-op`, `specific-pipeline-op`, etc.
**Target**: Simple `[:pipeline [:source ...] [:operation ...] [:operation ...]]`

### 2. Expression Wrapper Elimination
**Current**: Many expressions wrapped in `[:expr ...]` unnecessarily
**Target**: Remove `[:expr ...]` wrappers completely - use direct nodes

### 3. Literal Simplification
**Current**: `[:literal [:string "value"]]` and `[:literal [:number 42]]`
**Target**: `[:string "value"]` and `[:number 42]` (direct type nodes)

### 4. Function Call Arguments
**Current**: Distinguish between `bare-arguments` and `arguments` (parenthesized)
**Target**: Direct arguments without wrapper: `[:function-call [:identifier "func"] [:arg1] [:arg2]]`

### 5. Pipeline Structure Simplification
**Current**: `[:pipeline [:source [:identifier "users"]]]`
**Target**: `[:pipeline [:identifier "users"] [:operation ...]]`

### 6. Comparison Operators
**Current**: `[:comparison-op ">"]` with string value
**Target**: Direct operator nodes like `[:gt]`, `[:lt]`, `[:eq]`, etc.

### 7. Field Structure
**Current**: `[:field [:identifier "name"] [:expr [:literal [:string "Alice"]]]]`
**Target**: `[:field [:identifier "name"] [:literal [:string "Alice"]]]`

## Node Type Standardization

### Core Types (no wrappers):
- `:identifier` - variable/function names: `[:identifier "users"]`
- `:string` - string literals: `[:string "Alice"]`
- `:number` - numeric literals: `[:number 42]` or `[:number 3.14]`
- `:boolean` - boolean literals: `[:boolean true]`
- `:nil` - null value: `[:nil]`

### Structural Types:
- `:assignment` - variable assignment: `[:assignment [:identifier "x"] [:number 42]]`
- `:function-call` - function invocation: `[:function-call [:identifier "func"] [:arg1] [:arg2]]`
- `:object` - object/dictionary literals: `[:object [:field ...] [:field ...]]`
- `:list` - array/list literals: `[:list [:element1] [:element2]]`
- `:pipeline` - data transformation pipeline: `[:pipeline [:source] [:op1] [:op2]]`

### Operations (direct nodes):
- `:eq`, `:ne`, `:gt`, `:lt`, `:gte`, `:lte` - comparisons
- `:add`, `:sub`, `:mul`, `:div`, `:mod` - arithmetic
- `:and`, `:or` - logical operations
- `:assign` - assignment operator

### Special:
- `:field` - object field definition: `[:field [:identifier "name"] [:value]]`
- `:pattern` - pattern matching clause
- `:try-catch` - exception handling
- `:wildcard-access` - field access: `[:wildcard-access "_" [:identifier "name"]]`

## Radical Simplification Examples

### Assignment:
```clojure
;; Before: [:assignment [:user-identifier "x"] [:expr [:literal [:number [:integer "42"]]]]]
;; After:  [:assignment [:identifier "x"] [:number 42]]
```

### Function Call:
```clojure
;; Before: [:function-call [:user-identifier "map"] [:bare-arguments [:expr [:object ...]]]]
;; After:  [:function-call [:identifier "map"] [:object ...]]
```

### Pipeline:
```clojure
;; Before: [:indented-pipeline [:pipeline-source [:identifier [:user-identifier "users"]]] ...]
;; After:  [:pipeline [:identifier "users"] [:operation [:identifier "filter"] ...]]
```

### Object Field:
```clojure
;; Before: [:field [:user-identifier "name"] [:field-value [:same-line-field-value [:expr ...]]]]
;; After:  [:field [:identifier "name"] [:value ...]]
```

## Implementation Status

### ✅ COMPLETED (Phase 1)

#### 1. Identifier Simplification ✅
**Before**: `[:user-identifier "name"]`
**After**: `[:identifier "name"]`

#### 2. String Representation ✅
**Before**: `[:string [:regular-char "A"] [:regular-char "l"] [:regular-char "i"] [:regular-char "c"] [:regular-char "e"]]`
**After**: `[:string "Alice"]`

#### 3. List Structure Simplification ✅
**Before**: `[:list [:single-line-list [:list-element [:object ...]] [:list-element [:object ...]]]]`
**After**: `[:list [:object ...] [:object ...]]`

#### 4. Object Structure Simplification ✅
**Before**: `[:object [:single-line-object [:field ...]]]`
**After**: `[:object [:field ...]]`

#### 5. Field Structure Simplification ✅
**Before**: `[:field [:user-identifier "name"] [:field-value [:same-line-field-value [:expr ...]]]]`
**After**: `[:field [:identifier "name"] [:expr ...]]`

#### 6. Number Structure ✅
**Before**: `[:number [:integer "42"]]`
**After**: `[:number "42"]` (string representation, can be converted later)

#### 7. Expression Wrapper Elimination ✅
**Before**: `[:assignment [:identifier "x"] [:expr [:number "42"]]]`
**After**: `[:assignment [:identifier "x"] [:number "42"]]`

#### 8. Assignment Operator Distinction ✅
**Before**: Assignment used `[:comparison-op "="]`
**After**: Assignment uses `[:assignment-op "="]` separate from comparison

---

## 📊 CURRENT STATUS: Phase 2 In Progress

### ✅ Recently Completed (Oct 30, 2025)

#### 1. Direct Operator Nodes Implementation
- **Comparison Operators**: `[:gt ">"]` `[:lt "<"]` `[:gte ">="]` `[:lte "<="]` `[:eq "=="]` `[:ne "!="]` `[:assign "="]`
- **Arithmetic Operators**: `[:add "+"]` `[:sub "-"]` `[:mul "*"]` `[:div "/"]` `[:mod "%"]`
- **Logical Operators**: `[:and-op "and"]` `[:or-op "or"]`
- **Updated Tests**: All Phase 1 structure tests updated to expect new operator nodes
- **All 293 Tests Passing**: Grammar (189) + Comment (14) + Structure (87) + DTW Files (3)

#### 2. Test Structure Improvements
- **Renamed**: `simplified_grammar_tests.clj` → `structure_tests.clj`
- **Purpose**: Dedicated file for exact parse tree structure validation
- **Integration**: Added to main test-runner as separate test suite
- **Namespace**: Updated to `datatwist.structure-tests`

### 🎯 Current Parse Tree Improvements

**Before (string operators):**
```clojure
[:program [:identifier "x"] "+" [:identifier "y"] ">" [:number "10"] "and" [:identifier "z"] "<" [:number "20"]]
```

**After (typed operators):**
```clojure
[:program [:identifier "x"] [:add "+"] [:identifier "y"] [:comparison-op [:gt ">"]] [:number "10"] 
         [:logical-op [:and-op "and"]] [:identifier "z"] [:comparison-op [:lt "<"]] [:number "20"]]
```

---

---

## 📊 CURRENT STATUS: Phase 3 Complete ✅

### ✅ COMPLETED: Phase 3 Final Simplification (Oct 31, 2025)

#### 1. Function Call Arguments Simplification ✅
**Before (Complex)**:
```clojure
[:function-call 
 [:identifier "process"] 
 [:bare-arguments 
  [:identifier "data"] 
  [:identifier "filter"]]]]
```

**After (Simplified)**:
```clojure
[:function-call 
 [:identifier "process"] 
 [:identifier "data"] 
 [:identifier "filter"]]]
```

**Key Improvements**:
- ✅ Eliminated `[:bare-arguments [...]]` wrapper completely
- ✅ Direct arguments in function calls for Clojure-style syntax
- ✅ Preserved parenthesized arguments with `[:arguments [...]]` wrapper for distinction
- ✅ Added 2 new tests for parenthesized arguments coverage
- ✅ All 295 tests passing (up from 293)

#### 2. Pattern Matching Simplification ✅
**Before (Complex)**:
```clojure
[:field [:identifier "age_group"]
 [:multi-line-field-value
  [:newline "\n"]
  [:pattern-clauses
   [:pattern-clause
    "|"
    [:pattern-condition [:pattern-test [:wildcard-access "_" [:identifier "age"]] [:comparison-op [:lt "<"]] [:number "25"]]]
    [:pattern-result [:string "young"]]]
   [:pattern-clause
    "|"
    [:pattern-condition [:pattern-default]]
    [:pattern-result [:string "senior"]]]]]]
```

**After (Simplified)**:
```clojure
[:field [:identifier "age_group"]
 [:multi-line-field-value
  [:newline "\n"]
  [:pattern
   "|" [:pattern-condition [:wildcard-access "_" [:identifier "age"]] [:comparison-op [:lt "<"]] [:number "25"]] [:string "young"]
   [:newline "\n"] "|" [:pattern-condition] [:string "senior"]]]]
```

**Key Improvements**:
- ✅ Eliminated `[:pattern-clauses [...]]` wrapper
- ✅ Eliminated `[:pattern-clause [...]]` wrapper  
- ✅ Eliminated `[:pattern-result [...]]` wrapper
- ✅ Simplified pattern condition structure
- ✅ Reduced parse tree node count significantly (large-sample.dtw: 1612 → 1546 nodes)

#### 3. Expression Wrapper Elimination ✅
**Status**: Already completed via hidden `<expr>` nodes in grammar
- ✅ No `[:expr ...]` wrappers found in any parse trees
- ✅ Clean direct node structure throughout
- ✅ Proper precedence handling maintained

---

## 🎯 PHASE 3 ACHIEVEMENTS

### Parse Tree Optimization Results:
- **Test Count**: 293 → 295 tests (+2 new function call tests)
- **Node Reduction**: ~4% reduction in complex file parse trees
- **Structure Simplicity**: Eliminated 3 major wrapper types
- **Performance**: Maintained (~420-530ms for large input parsing)

### Wrapper Elimination Summary:
1. ✅ **Function Calls**: `[:bare-arguments [...]]` → direct arguments
2. ✅ **Pattern Matching**: `[:pattern-clauses [...]]` + `[:pattern-clause [...]]` + `[:pattern-result [...]]` → simplified structure
3. ✅ **Expressions**: `[:expr [...]]` → eliminated via hidden nodes
4. ✅ **Maintained**: Parenthesized arguments distinction with `[:arguments [...]]`

### Final Parse Tree Examples:

#### Function Call Evolution:
```clojure
// Phase 2: [:function-call [:identifier "func"] [:bare-arguments [:identifier "arg1"] [:identifier "arg2"]]]
// Phase 3: [:function-call [:identifier "func"] [:identifier "arg1"] [:identifier "arg2"]]]
```

#### Pattern Matching Evolution:
```clojure
// Phase 2: Complex nested structure with 4+ wrapper levels
// Phase 3: Streamlined structure with minimal nesting
```

#### Complex Pipeline (Final):
```clojure
[:program 
 [:pipeline 
  [:identifier "data"]
  [:pipeline-operation [:filter-op [:operation-arguments [:identifier "even?"]]]]
  [:pipeline-operation [:map-op [:operation-arguments [:identifier "double"]]]]
  [:pipeline-operation [:take-op [:operation-arguments [:number "5"]]]]
  [:pipeline-operation [:sum-op]]]]
```

---

## 🔄 NEXT PHASE: Potential Future Optimizations

### Low Priority Opportunities:
1. **Multi-line-field-value wrapper** - Could be eliminated for field patterns
2. **Pipeline-operation wrapper** - Could be streamlined further  
3. **Operation-arguments wrapper** - Could be simplified for single arguments
4. **Field-value wrapper** - Could be eliminated in some contexts

### Current Status: **EXCELLENT** ✅
- All major structural complexity eliminated
- Parse trees are semantically clear and minimal
- Performance is stable and optimized
- Test coverage is comprehensive (295 tests passing)
- Code quality is high with minimal linting warnings

**Phase 3 represents the completion of the core grammar simplification goals.**

**Target Simple Structure:**
```clojure
[:pipeline
 [:identifier "users"]
 [:operation
  [:identifier "filter"]
  [:wildcard-access "_" [:identifier "age"]]
  [:comparison-op ">"]
  [:number "18"]]]
 [:operation
  [:identifier "map"]
  [:object
   [:field [:identifier "name"] [:wildcard-access "_" [:identifier "name"]]]]]]
```

### 2. Function Call Arguments Simplification

**Current:**
```clojure
[:function-call
 [:identifier "process"]
 [:bare-arguments
  [:identifier "data"]
  [:string "input"]]]
```

**Target:**
```clojure
[:function-call
 [:identifier "process"]
 [:identifier "data"]
 [:string "input"]]
```

### 3. Pattern Matching Simplification

**Current:**
```clojure
[:field [:identifier "age_group"]
 [:multi-line-field-value
  [:newline "\n"]
  [:pattern-clauses
   [:pattern-clause
    "|"
    [:pattern-condition [:pattern-test [:wildcard-access "_" [:identifier "age"]] [:comparison-op "<"] [:number "25"]]]
    [:pattern-result [:string "young"]]]
   [:pattern-clause
    "|"
    [:pattern-condition [:pattern-default]]
    [:pattern-result [:string "senior"]]]]]]
```

**Target:**
```clojure
[:field [:identifier "age_group"]
 [:pattern
  [:when [:wildcard-access "_" [:identifier "age"]] [:comparison-op "<"] [:number "25"]] [:string "young"]
  [:when [:wildcard-access "_" [:identifier "age"]] [:comparison-op "<"] [:number "40"]] [:string "middle"]
  [:default [:string "senior"]]]]
```

### ✅ 4. Direct Operator Nodes (COMPLETED)

**Before:**
```clojure
[:comparison-op ">"]
[:comparison-op "=="]
[:comparison-op "<="]
```

**After:**
```clojure
[:comparison-op [:gt ">"]]
[:comparison-op [:eq "=="]]
[:comparison-op [:lte "<="]]
[:comparison-op [:lt "<"]]
[:comparison-op [:gte ">="]]
[:comparison-op [:ne "!="]]
[:comparison-op [:assign "="]]
```

### ✅ 5. Logical Operators as Nodes (COMPLETED)

**Before:**
```clojure
[:wildcard-access "_" [:identifier "age"]]
[:comparison-op ">"]
[:number "18"]
"and"
[:wildcard-access "_" [:identifier "status"]]
[:comparison-op "=="]
[:string "active"]
```

**After:**
```clojure
[:wildcard-access "_" [:identifier "age"]]
[:comparison-op [:gt ">"]]
[:number "18"]
[:logical-op [:and-op "and"]]
[:wildcard-access "_" [:identifier "status"]]
[:comparison-op [:eq "=="]]
[:string "active"]
```

---

## 🎯 Expected Final Parse Tree Examples

### Complex Pipeline with Logic:
**Input:**
```datatwist
users
  filter _.age > 18 and _.status == "active"
  map {
    name: _.name
    age_group: 
      | _.age < 25 -> "young"
      | _ -> "senior"
  }
```

**Target Parse Tree:**
```clojure
[:program
 [:pipeline
   [:identifier "users"]
   [:operation
    [:identifier "filter"]
    [:logical-op [:and-op "and"]
     [:wildcard-access "_" [:identifier "age"]]
     [:comparison-op [:gt ">"]]
     [:number "18"]
     [:wildcard-access "_" [:identifier "status"]]
     [:comparison-op [:eq "=="]]
     [:string "active"]]]
   [:operation
    [:identifier "map"]
    [:object
     [:field [:identifier "name"] [:wildcard-access "_" [:identifier "name"]]]
     [:field [:identifier "age_group"]
      [:pattern
       [:when [:wildcard-access "_" [:identifier "age"]] [:comparison-op [:lt "<"]] [:number "25"]] [:string "young"]
       [:default [:string "senior"]]]]]]]
```

### Function Call:
**Input:** `process data "input"`

**Target Parse Tree:**
```clojure
[:program
 [:function-call
  [:identifier "process"]
  [:identifier "data"]
  [:string "input"]]]
```

### Assignment with Arithmetic:
**Input:** `result = x + y * 2`

**Target Parse Tree:**
```clojure
[:program
 [:assignment
   [:identifier "result"]
   [:add "+"]
    [:identifier "x"]
    [:mul "*"]
     [:identifier "y"]
     [:number "2"]]]]
```

---

## Implementation Priority (Phase 2)

### ✅ COMPLETED
1. **Direct Operator Nodes** - All comparison, arithmetic, and logical operators now use typed nodes
2. **Logical Operators as Nodes** - `[:and-op]` and `[:or-op]` instead of strings

### 🔄 IN PROGRESS / NEXT
1. **High Priority**: Pipeline structure simplification (biggest impact)
2. **Medium Priority**: Function call arguments, pattern matching

### 📋 REMAINING (Low Priority)
- Further operator simplification (remove wrapper nodes completely)
- Expression wrapper elimination
- Literal simplification (direct type nodes)

## Testing Requirements

- All existing tests must pass after refactor
- Parse tree structure should be semantically equivalent  
- Performance should not degrade
- New tests should verify simplified structure
- Complex pipeline examples should parse correctly