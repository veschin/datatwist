# Clojure.string Library Documentation

## Overview
The `clojure.string` namespace provides comprehensive string manipulation functions that work consistently across Clojure and ClojureScript. It offers a rich set of utilities for string processing, pattern matching, and text transformation.

## Key Features
- Cross-platform string operations
- Regular expression support
- Unicode-aware text processing
- Efficient string joining and splitting
- Case conversion and whitespace handling
- Search and replace operations

---

## 1. Basic String Operations

### Case Conversion
```clojure
(require '[clojure.string :as str])

;; Case conversion
(str/upper-case "hello world")    ;=> "HELLO WORLD"
(str/lower-case "HELLO WORLD")    ;=> "hello world"
(str/capitalize "hello world")    ;=> "Hello world"
```

### Whitespace Handling
```clojure
;; Trimming operations
(str/trim "  hello world  ")      ;=> "hello world"
(str/triml "  hello world  ")     ;=> "hello world  "
(str/trimr "  hello world  ")     ;=> "  hello world"

;; Whitespace detection
(str/blank? "   ")                ;=> true
(str/blank? "hello")              ;=> false
```

---

## 2. String Splitting and Joining

### Split Operations
```clojure
;; Basic splitting
(str/split "a,b,c,d" #",")        ;=> ["a" "b" "c" "d"]
(str/split "a,b,c,d" #"," 3)      ;=> ["a" "b" "c,d"]

;; Split with limit
(str/split "a,b,c,d" #"," -1)     ;=> ["a" "b" "c" "d"]

;; Split lines
(str/split-lines "line1\nline2\nline3") ;=> ["line1" "line2" "line3"]
```

### Join Operations
```clojure
;; Basic joining
(str/join ", " ["a" "b" "c"])     ;=> "a, b, c"

;; Join with different separators
(str/join "-" [1 2 3 4])          ;=> "1-2-3-4"

;; Join sets (order not guaranteed)
(str/join " and " #{:fred :ethel :lucy}) ;=> ":lucy and :fred and :ethel"
```

---

## 3. Search and Replace Operations

### Search Functions
```clojure
;; Index finding
(str/index-of "hello world" "world")    ;=> 6
(str/index-of "hello world" "o")         ;=> 4
(str/index-of "hello world" "z")         ;=> -1

;; Last index
(str/last-index-of "hello world" "o")    ;=> 7
(str/last-index-of "hello world" "world") ;=> 6

;; Prefix/suffix checking
(str/starts-with? "hello world" "hello")  ;=> true
(str/ends-with? "hello world" "world")    ;=> true
(str/includes? "hello world" "lo wo")     ;=> true
```

### Replace Operations
```clojure
;; Simple replace
(str/replace "hello world" "world" "Clojure") ;=> "hello Clojure"

;; Replace with regex
(str/replace "a1b2c3" #"\d" "")               ;=> "abc"

;; Replace first occurrence
(str/replace-first "a1b2c3" #"\d" "X")       ;=> "aXb2c3"

;; Replace with function
(str/replace "a1b2c3" #"\d" str/upper-case)  ;=> "aBbCc"

;; Special characters in replacement
(str/replace "munge.this" "." "$")           ;=> "munge$this"

;; Using re-quote-replacement for literal replacement
(str/replace "x12, b4" #"([a-z]+)([0-9]+)"
             (str/re-quote-replacement "$1 <- $2"))
;=> "$1 <- $2, $1 <- $2"
```

---

## 4. Regular Expression Operations

### Pattern Matching
```clojure
;; Re-find returns first match
(re-find #"\d+" "abc123def456")        ;=> "123"

;; Re-seq returns all matches
(re-seq #"\d+" "abc123def456")        ;=> ("123" "456")

;; Re-matches checks entire string
(re-matches #"\d+" "123")             ;=> "123"
(re-matches #"\d+" "abc123")          ;=> nil
```

### Advanced Pattern Operations
```clojure
;; Groups in regex
(def pattern #"(\w+)-(\w+)")
(re-find pattern "hello-world")       ;=> ["hello-world" "hello" "world"]

;; Named groups (Clojure 1.11+)
(re-find #"(?<first>\w+)-(?<second>\w+)" "hello-world")
;=> ["hello-world" "hello" "world"]
```

---

## 5. String Escape and Quote Operations

### Escape Sequences
```clojure
;; Escape special characters for regex
(str/escape "a+b=c" {"+" "\\+" "=" "\\="}) ;=> "a\\+b\\=c"

;; Quote replacement string
(str/re-quote-replacement "$1.00")    ;=> "\\$1.00"
```

---

## 6. String Comparison and Analysis

### String Analysis
```clojure
;; Reverse string
(str/reverse "hello")                ;=> "olleh"

;; Get characters
(str/get "hello" 1)                  ;=> "e"

;; Check if string is numeric
(defn numeric? [s]
  (every? #(Character/isDigit %) s))

(numeric? "123")                     ;=> true
(numeric? "12a3")                    ;=> false
```

---

## 7. Performance Considerations

### Efficient String Building
```clojure
;; Use StringBuilder for large concatenations
(defn build-string [parts]
  (.toString (reduce #(.append %1 (str %2)) 
                    (StringBuilder.) 
                    parts)))

;; Use str/join for joining collections
(str/join ", " large-collection)     ; More efficient than repeated concat
```

### Regex Performance
```clojure
;; Pre-compile regex patterns for repeated use
(def email-pattern #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$")

(defn valid-email? [email]
  (re-matches email-pattern email))
```

---

## 8. Unicode and Internationalization

### Unicode Support
```clojure
;; Unicode string operations work naturally
(str/upper-case "café")              ;=> "CAFÉ"
(str/lower-case "CAFÉ")              ;=> "café"

;; Character counting
(count "hello")                      ;=> 5
(count "café")                       ;=> 4 (é is one character)

;; Code points
(map int "café")                     ;=> (99 97 102 233)
```

---

## 9. Common Patterns and Idioms

### Text Processing Patterns
```clojure
;; Clean and normalize text
(defn clean-text [text]
  (-> text
      str/trim
      (str/replace #"\s+" " ")
      str/lower-case))

(clean-text "  Hello   World  ")    ;=> "hello world"

;; Parse CSV-like data
(defn parse-csv-line [line]
  (str/split line #"\s*,\s*"))

(parse-csv-line "a,b,c,d")          ;=> ["a" "b" "c" "d"]

;; Extract words from text
(defn extract-words [text]
  (re-seq #"\b\w+\b" text))

(extract-words "Hello, world!")     ;=> ("Hello" "world")
```

---

## 10. Integration with Other Libraries

### File Processing
```clojure
(require '[clojure.java.io :as io])

;; Process file line by line
(with-open [reader (io/reader "file.txt")]
  (doseq [line (line-seq reader)]
    (println (str/trim line))))
```

### Web Content Processing
```clojure
;; Extract URLs from text
(defn extract-urls [text]
  (re-seq #"https?://[^\s]+" text))

;; Clean HTML tags (simple version)
(defn strip-html [html]
  (str/replace html #"<[^>]+>" ""))
```

---

## Usage Notes for DataTwist Project

1. **Grammar Processing**: Use `str/split-lines` for processing grammar files line by line
2. **Token Cleaning**: Use `str/trim` for cleaning whitespace from tokens
3. **Pattern Matching**: Use regex functions for identifying grammar patterns
4. **String Building**: Use `str/join` for efficiently building output strings
5. **Case Sensitivity**: Be consistent with case handling in grammar keywords
6. **Unicode Support**: Ensure grammar files handle Unicode characters correctly
7. **Performance**: Pre-compile regex patterns used repeatedly in parsing

This comprehensive documentation covers all essential string operations needed for effective text processing in your DataTwist grammar parsing project.