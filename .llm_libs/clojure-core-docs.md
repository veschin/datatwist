# Clojure Core Library Documentation

## Overview
Clojure Core provides the fundamental functions, data structures, and abstractions that form the foundation of the Clojure programming language. It emphasizes immutability, functional programming, and powerful sequence abstractions.

## Key Features
- Immutable data structures (vectors, maps, lists, sets)
- Lazy sequences and powerful sequence operations
- Functional programming primitives
- Namespace system for code organization
- Protocol and multimethod systems for polymorphism
- Macros for metaprogramming
- Robust error handling and exception management

---

## 1. Core Data Structures

### Vectors
```clojure
;; Vector creation and operations
(def my-vector [1 2 3 4 5])
(conj my-vector 6)           ;=> [1 2 3 4 5 6]
(get my-vector 2)            ;=> 3
(assoc my-vector 1 99)        ;=> [1 99 3 4 5]
(subvec my-vector 1 3)       ;=> [2 3]
```

### Maps
```clojure
;; Map creation and operations
(def my-map {:a 1 :b 2 :c 3})
(assoc my-map :d 4)          ;=> {:a 1 :b 2 :c 3 :d 4}
(dissoc my-map :b)           ;=> {:a 1 :c 3}
(get my-map :a)              ;=> 1
(keys my-map)                ;=> (:a :b :c)
(vals my-map)                ;=> (1 2 3)
```

### Sets
```clojure
;; Set creation and operations
(def my-set #{1 2 3 4})
(conj my-set 5)              ;=> #{1 2 3 4 5}
(disj my-set 2)              ;=> #{1 3 4}
(contains? my-set 3)         ;=> true
```

### Lists
```clojure
;; List creation and operations
(def my-list '(1 2 3 4))
(conj my-list 0)             ;=> (0 1 2 3 4)
(first my-list)             ;=> 1
(rest my-list)              ;=> (2 3 4)
```

---

## 2. Sequence Operations

### Core Sequence Functions
```clojure
;; Mapping and filtering
(map inc [1 2 3 4])          ;=> (2 3 4 5)
(filter even? [1 2 3 4 5])   ;=> (2 4)
(remove odd? [1 2 3 4 5])    ;=> (2 4)

;; Reduction
(reduce + [1 2 3 4])         ;=> 10
(reduce conj [] [1 2 3])     ;=> [1 2 3]

;; Taking and dropping
(take 3 (range 10))          ;=> (0 1 2)
(drop 3 (range 10))          ;=> (3 4 5 6 7 8 9)
(take-while #(< % 5) (range)) ;=> (0 1 2 3 4)
(drop-while #(< % 5) (range)) ;=> (5 6 7 8 9)
```

### Lazy Sequences
```clojure
;; Creating lazy sequences
(range 10)                   ;=> (0 1 2 3 4 5 6 7 8 9)
(iterate inc 0)              ;=> (0 1 2 3 4 5 6 7 8 9...)
(repeat 5 :x)                ;=> (:x :x :x :x :x)
(cycle [1 2 3])              ;=> (1 2 3 1 2 3 1 2 3...)
```

---

## 3. Functions and Higher-Order Functions

### Function Definition
```clojure
;; Function definition with defn
(defn greet [name]
  (str "Hello, " name "!"))

;; Multi-arity functions
(defn calculate
  ([x] (* x x))
  ([x y] (+ x y))
  ([x y & more] (apply + x y more)))

;; Destructuring
(defn process-person [{:keys [name age]}]
  (str name " is " age " years old"))
```

### Higher-Order Functions
```clojure
;; Function composition
(def add-then-multiply (comp * (+ 10)))
(add-then-multiply 5 3)      ;=> 45

;; Partial application
(def add-10 (partial + 10))
(add-10 5)                   ;=> 15

;; Juxtaposition
(juxt + - * /) 6 2          ;=> [8 4 12 3]
```

---

## 4. Namespace Management

### Namespace Declaration
```clojure
(ns my-app.core
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [my-app.utils :as utils])
  (:use [clojure.pprint :only [pprint]])
  (:import [java.util Date])
  (:gen-class))
```

### Require and Use
```clojure
;; Different require forms
(require 'clojure.string)
(require '[clojure.string :as str])
(require '[clojure.string :refer [split join]])
(require '[clojure.string :refer :all])

;; Use (generally discouraged in production)
(use 'clojure.pprint)
```

---

## 5. Protocols and Multimethods

### Protocols
```clojure
(defprotocol Drawable
  (draw [this] "Draw the object"))

(defrecord Circle [radius]
  Drawable
  (draw [this] (str "Drawing circle with radius " radius)))

(defrecord Rectangle [width height]
  Drawable
  (draw [this] (str "Drawing rectangle " width "x" height)))
```

### Multimethods
```clojure
(defmulti area (fn [shape] (:type shape)))

(defmethod area :circle [{:keys [radius]}]
  (* Math/PI radius radius))

(defmethod area :rectangle [{:keys [width height]}]
  (* width height))

(area {:type :circle :radius 5})      ;=> 78.53981633974483
(area {:type :rectangle :width 3 :height 4}) ;=> 12
```

---

## 6. Error Handling

### Exception Handling
```clojure
;; Try-catch
(try
  (/ 10 0)
  (catch ArithmeticException e
    "Division by zero")
  (finally
    (println "Cleanup")))

;; Exception creation
(throw (ex-info "Custom error" {:error-code 123}))

;; Exception information
(try
  (throw (ex-info "Error" {:data "value"}))
  (catch Exception e
    (ex-data e)))              ;=> {:data "value"}
```

---

## 7. Metaprogramming with Macros

### Basic Macros
```clojure
;; Simple macro
(defmacro unless [condition & body]
  `(if (not ~condition) (do ~@body)))

;; Macro with syntax quote
(defmacro debug [expr]
  `(let [result# ~expr]
     (println '~expr "=>" result#)
     result#))
```

---

## 8. Performance Optimizations

### Transients
```clojure
;; Using transients for performance
(defn fast-conj [coll items]
  (persistent! (reduce conj! (transient coll) items)))

;; Vector operations
(defn fast-vector-conj [items]
  (persistent! (reduce conj! (transient []) items)))
```

### Type Hints
```clojure
;; Type hints for performance
(defn fast-add ^long [^long a ^long b]
  (+ a b))

(defn process-string ^String [^String s]
  (.toUpperCase s))
```

---

## 9. New Core Functions (Clojure 1.12+)

### Parser Functions
```clojure
(parse-double "1.23e4")       ;=> 12300.0
(parse-long "12345")          ;=> 12345
(parse-boolean "true")        ;=> true
(parse-uuid "550e8400-e29b-41d4-a716-446655440000")
```

### Enhanced Map Operations
```clojure
(update-keys my-map keyword)  ; Transform all keys
(update-vals my-map inc)      ; Transform all values

;; Bounded counting
(bounded-count 1000 huge-seq) ; Count up to 1000 without realizing all
```

---

## 10. Best Practices

### Idiomatic Clojure
```clojure
;; Prefer immutable data structures
(def state (atom {}))
(swap! state assoc :key :value)

;; Use functions over methods
(defn process-item [item] ...)
(map process-item items)

;; Leverage the REPL for development
(def my-data [1 2 3])
(map inc my-data)  ; Try it immediately
```

### Performance Considerations
```clojure
;; Use transients for large collections
(defn build-large-set [items]
  (persistent! (reduce conj! (transient #{}) items)))

;; Prefer reduce over loop/recur for collections
(reduce + 0 numbers)  ; Better than (apply + numbers) for large collections

;; Use bounded-count for potentially infinite sequences
(bounded-count 100 (range))  ; Safe even for infinite sequences
```

---

## Usage Notes for DataTwist Project

1. **Immutable Data**: Always use immutable data structures for grammar rules and parse results
2. **Sequence Processing**: Leverage lazy sequences for processing large grammar files
3. **Error Handling**: Use `ex-info` for structured error reporting in parsing failures
4. **Performance**: Consider transients for building large data structures during parsing
5. **Namespaces**: Organize grammar components into logical namespaces
6. **Testing**: Use the REPL extensively for interactive grammar development
7. **Protocols**: Consider protocols for different grammar node types

This comprehensive documentation provides the foundation for effective Clojure development in your DataTwist project.