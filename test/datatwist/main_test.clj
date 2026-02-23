(ns datatwist.main-test
  (:require [clojure.test :refer [deftest testing is]]
            [datatwist.main :as main]
            [datatwist.parser :as parser]))

(deftest parse-args-test
  (testing "eval subcommand with -e flag"
    (let [result (main/parse-args ["eval" "-e" "1 + 2"])]
      (is (= :eval (:command result)))
      (is (= "1 + 2" (:expression result)))))

  (testing "run subcommand with file argument"
    (let [result (main/parse-args ["run" "script.dt"])]
      (is (= :run (:command result)))
      (is (= "script.dt" (:file result)))))

  (testing "repl subcommand"
    (let [result (main/parse-args ["repl"])]
      (is (= :repl (:command result)))))

  (testing "fmt subcommand"
    (let [result (main/parse-args ["fmt"])]
      (is (= :fmt (:command result)))))

  (testing "bare invocation defaults to repl"
    (let [result (main/parse-args [])]
      (is (= :repl (:command result)))))

  (testing "--help flag"
    (let [result (main/parse-args ["--help"])]
      (is (= :help (:command result)))))

  (testing "--version flag"
    (let [result (main/parse-args ["--version"])]
      (is (= :version (:command result)))))

  (testing "unknown subcommand returns error"
    (let [result (main/parse-args ["unknown"])]
      (is (= :error (:command result))))))

(deftest eval-expression-test
  (testing "evaluates simple arithmetic"
    (is (= 3 (main/eval-expression "1 + 2"))))

  (testing "evaluates string pipeline operation"
    (is (= "hello" (main/eval-expression "\"  hello  \" |> trim")))))

(deftest run-file-test
  (testing "runs a .dt file and returns result"
    (let [tmp (java.io.File/createTempFile "test" ".dt")]
      (.deleteOnExit tmp)
      (spit tmp "1 + 2")
      (is (= 3 (main/run-file (.getAbsolutePath tmp)))))))

(deftest parse-error-detection-test
  (testing "detects parse errors in expressions"
    (is (true? (parser/parse-error? "@@@ not valid syntax")))))
