# Clojure.test Library Documentation

## Overview
`clojure.test` is Clojure's built-in testing framework that provides a simple yet powerful foundation for unit testing. It supports assertions, fixtures, test suites, and comprehensive reporting capabilities.

## Key Features
- Simple assertion-based testing
- Test fixtures for setup/teardown
- Test suite organization
- Flexible reporting mechanisms
- Integration with build tools
- Support for property-based testing extensions

---

## 1. Basic Test Structure

### Test Definition
```clojure
(ns my-app.core-test
  (:require [clojure.test :refer :all]
            [my-app.core :as core]))

;; Basic test
(deftest test-addition
  (is (= 4 (+ 2 2))))

;; Test with description
(deftest test-string-concatenation
  (testing "String concatenation works"
    (is (= "hello world" (str "hello " "world")))))
```

### Assertions
```clojure
;; Basic assertions
(is (= 5 (+ 2 3)))              ;=> true
(is (nil? nil))                  ;=> true
(is (not nil))                   ;=> true

;; Assertions with messages
(is (= 5 (+ 2 3)) "Addition should work")

;; Exception testing
(is (thrown? ArithmeticException (/ 1 0)))

;; Specific exception with message
(is (thrown-with-msg? ArithmeticException #"Divide by zero" (/ 1 0)))
```

---

## 2. Testing Functions and Values

### Function Testing
```clojure
(deftest test-math-functions
  (testing "Basic arithmetic"
    (is (= 10 (core/add 3 7)))
    (is (= 6 (core/multiply 2 3))))
  
  (testing "Edge cases"
    (is (= 0 (core/add 0 0)))
    (is (= 1 (core/multiply 1 1)))))

;; Testing with different inputs
(deftest test-string-processing
  (are [input expected] (= expected (core/process-string input))
    "hello" "HELLO"
    "world" "WORLD"
    "" ""))
```

### Collection Testing
```clojure
(deftest test-collection-operations
  (testing "Vector operations"
    (is (= [1 2 3] (conj [1] 2 3)))
    (is (= 2 (count [1 2]))))
  
  (testing "Map operations"
    (is (= {:a 1 :b 2} (assoc {:a 1} :b 2)))
    (is (= 1 (get {:a 1} :a)))))
```

---

## 3. Test Fixtures

### Setup and Teardown
```clojure
;; Define fixtures
(defn setup-database [f]
  (println "Setting up database...")
  (let [db (core/create-test-db)]
    (binding [core/*db* db]
      (f))
    (core/cleanup-db db)))

(defn setup-logging [f]
  (binding [core/*log-level* :debug]
    (f)))

;; Apply fixtures to tests
(use-fixtures :once setup-database)    ; Run once per namespace
(use-fixtures :each setup-logging)     ; Run before each test

(deftest test-database-operations
  (is (core/save-record {:name "test"}))
  (is (= 1 (core/count-records))))
```

### Multiple Fixtures
```clojure
;; Multiple fixtures in order
(use-fixtures :once setup-database setup-logging)

;; Fixture with cleanup
(defn with-temp-file [f]
  (let [temp-file (java.io.File/createTempFile "test" ".tmp")]
    (try
      (binding [core/*temp-file* temp-file]
        (f))
      (finally
        (.delete temp-file)))))
```

---

## 4. Test Organization

### Test Namespaces
```clojure
;; Separate test namespace
(ns my-app.utils-test
  (:require [clojure.test :refer :all]
            [my-app.utils :as utils]))

(deftest test-string-utils
  (testing "String utilities"
    (is (utils/valid-email? "test@example.com"))
    (is (not (utils/valid-email? "invalid")))))
```

### Test Suites
```clojure
;; Running specific tests
(run-tests 'my-app.core-test)
(run-tests 'my-app.core-test 'my-app.utils-test)

;; Running all tests in namespace
(run-tests)

;; Running specific test vars
(test-vars [#'my-app.core-test/test-addition])
```

---

## 5. Advanced Testing Patterns

### Property-Based Testing
```clojure
;; Simple property testing
(deftest test-commutative-property
  (testing "Addition is commutative"
    (are [a b] (= (+ a b) (+ b a))
      1 2
      -5 10
      0 100)))

;; Using test.check for property-based testing
(require '[clojure.test.check :as tc]
         '[clojure.test.check.properties :as prop]
         '[clojure.test.check.clojure-test :refer [defspec]])

(defspec addition-commutative
  100
  (prop/for-all [a (gen/int)
                 b (gen/int)]
    (= (+ a b) (+ b a))))
```

### Mocking and Stubbing
```clojure
;; Using with-redefs for mocking
(deftest test-external-service
  (with-redefs [http/get (constantly {:status 200 :body "success"})]
    (is (= "success" (core/fetch-data "http://example.com")))))

;; Time-based testing
(deftest test-time-dependent-function
  (with-redefs [core/current-time (constantly #inst "2023-01-01")]
    (is (= "2023" (core/get-year)))))
```

---

## 6. Test Data Management

### Test Data Builders
```clojure
;; Test data builders
(defn build-user [& {:keys [name email age]
                     :or {name "Test User"
                          email "test@example.com"
                          age 25}}]
  {:name name :email email :age age})

(deftest test-user-validation
  (testing "Valid user"
    (let [user (build-user :name "John" :email "john@example.com")]
      (is (core/valid-user? user))))
  
  (testing "Invalid user"
    (let [user (build-user :email "invalid")]
      (is (not (core/valid-user? user))))))
```

### Test Fixtures with Data
```clojure
(defn with-test-data [f]
  (let [users [(build-user :name "Alice")
               (build-user :name "Bob")]]
    (binding [core/*test-users* users]
      (f))))

(use-fixtures :each with-test-data)
```

---

## 7. Error Testing

### Exception Testing
```clojure
(deftest test-error-conditions
  (testing "Division by zero"
    (is (thrown? ArithmeticException 
                 (core/divide 10 0))))
  
  (testing "Invalid input"
    (is (thrown-with-msg? IllegalArgumentException 
                         #"Invalid input"
                         (core/process nil)))))

;; Testing error messages
(deftest test-error-messages
  (try
    (core/invalid-operation)
    (catch Exception e
      (is (= "Operation failed" (.getMessage e))))))
```

---

## 8. Performance Testing

### Benchmark Testing
```clojure
(deftest test-performance
  (testing "Large collection processing"
    (let [start (System/nanoTime)
          result (core/process-large-collection (range 10000))
          end (System/nanoTime)
          duration (/ (- end start) 1000000.0)] ; Convert to milliseconds
      (is (< duration 100)) ; Should complete in under 100ms
      (is (= 10000 (count result))))))
```

---

## 9. Integration Testing

### Database Integration
```clojure
(defn with-test-db [f]
  (let [db-spec {:subprotocol "h2"
                 :subname "mem:test"
                 :user "sa"
                 :password ""}]
    (core/migrate-db db-spec)
    (binding [core/*db-spec* db-spec]
      (f))
    (core/drop-db db-spec)))

(use-fixtures :once with-test-db)

(deftest test-database-operations
  (testing "User creation"
    (let [user-id (core/create-user {:name "Test" :email "test@test.com"})]
      (is (integer? user-id))
      (is (core/user-exists? user-id)))))
```

---

## 10. Test Reporting and Output

### Custom Reporting
```clojure
;; Custom test reporter
(defn custom-reporter [event]
  (case (:type event)
    :begin-test-ns (println "Starting namespace:" (:ns event))
    :end-test-ns (println "Finished namespace:" (:ns event))
    :pass (println "✓" (:test event))
    :fail (println "✗" (:test event) "-" (:message event))
    :error (println "⚠" (:test event) "-" (:message event))
    nil))

;; Run tests with custom reporter
(run-tests :reporter custom-reporter)
```

### Test Output Control
```clojure
;; Suppress output during tests
(binding [*out* (java.io.StringWriter.)]
  (run-tests))

;; Capture test results
(def test-results (atom []))

(defn capture-results [event]
  (when (#{:pass :fail :error} (:type event))
    (swap! test-results conj event)))

(run-tests :reporter capture-results)
```

---

## 11. Best Practices

### Test Organization
```clojure
;; Group related tests
(deftest test-string-operations
  (testing "Trimming operations"
    (is (= "hello" (str/trim "  hello  ")))
    (is (= "hello" (str/triml "  hello  ")))
    (is (= "hello" (str/trimr "  hello  "))))
  
  (testing "Case conversion"
    (is (= "HELLO" (str/upper-case "hello")))
    (is (= "hello" (str/lower-case "HELLO")))))
```

### Test Naming
```clojure
;; Descriptive test names
(deftest test-addition-with-positive-numbers
  (is (= 5 (+ 2 3))))

(deftest test-addition-with-negative-numbers
  (is (= -1 (+ -2 1))))

(deftest test-addition-with-zero
  (is (= 2 (+ 2 0))))
```

---

## Usage Notes for DataTwist Project

1. **Grammar Testing**: Test each grammar rule with valid and invalid inputs
2. **Parse Tree Validation**: Use `is` with expected parse tree structures
3. **Error Cases**: Test failure conditions with `thrown?` assertions
4. **Fixtures**: Use fixtures to set up test grammar files and parsers
5. **Property Testing**: Consider property-based testing for grammar properties
6. **Integration Tests**: Test end-to-end parsing with real grammar files
7. **Performance**: Benchmark parsing performance with large inputs

This comprehensive testing framework documentation provides all the tools needed for robust testing of your DataTwist grammar parsing system.