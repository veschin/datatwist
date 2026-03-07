(ns datatwist.lsp-editor-test
  (:require [clojure.test :refer [deftest testing is]]
            [datatwist.test-helpers :refer :all]))

;; Test stubs for BDD feature: 11-lsp-editor-support.feature
;; LSP server (Tree-sitter + TypeScript) is not yet implemented.
;; Each deftest maps to a BDD section; each testing block maps 1:1 to a scenario.

;; ===========================================================================
;; SECTION 1: Syntax Highlighting
;; ===========================================================================

(deftest syntax-highlighting
  (testing "Keywords are highlighted as keyword tokens"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Control keywords are highlighted distinctly"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "String literals are highlighted as strings"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Numeric literals are highlighted as constants"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Boolean and nil literals are highlighted as language constants"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Line comments are highlighted as comments"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Pipe operator is highlighted as a pipe operator"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Object keys use a tag scope"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Side-effect functions are highlighted distinctly"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Wildcard is highlighted as a language variable"
    ;; stub -- LSP server not yet implemented
    (is true)))

;; ===========================================================================
;; SECTION 2: Autocomplete
;; ===========================================================================

(deftest autocomplete
  (testing "Stdlib functions appear in completion at top level"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Completion includes locally bound names from the current file"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Field completion after dot operator"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Completion after pipe operator offers callable functions"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Completion respects scope -- inner binding shadows outer"
    ;; stub -- LSP server not yet implemented
    (is true)))

;; ===========================================================================
;; SECTION 3: Hover
;; ===========================================================================

(deftest hover
  (testing "Hovering a stdlib function shows its signature"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Hovering a bound name shows its inferred type"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Hovering a function definition shows its parameter names"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Hovering nil shows nil type information"
    ;; stub -- LSP server not yet implemented
    (is true)))

;; ===========================================================================
;; SECTION 4: Go-to-Definition
;; ===========================================================================

(deftest go-to-definition
  (testing "Go-to-definition on a bound name jumps to its is-binding"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Go-to-definition on a function name jumps to its definition"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Go-to-definition on a parameter jumps to its declaration in the function head"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Go-to-definition on an undefined name produces a diagnostic instead of navigating"
    ;; stub -- LSP server not yet implemented
    (is true)))

;; ===========================================================================
;; SECTION 5: Error Diagnostics
;; ===========================================================================

(deftest error-diagnostics
  (testing "Parse error is shown as an inline diagnostic"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Unclosed bracket produces a diagnostic at the bracket location"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Multiple parse errors are all reported"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Valid code produces no diagnostics"
    ;; stub -- LSP server not yet implemented
    (is true))

  (testing "Diagnostics clear when the parse error is fixed"
    ;; stub -- LSP server not yet implemented
    (is true)))

;; ===========================================================================
;; SECTION 6: Signature Help
;; ===========================================================================

(deftest signature-help
  (testing "Signature help shows parameter hints inside a function call"
    ;; stub -- LSP server not yet implemented
    (is true)))

;; ===========================================================================
;; SECTION 7: Pipeline Step Inspection via Hover
;; ===========================================================================

(deftest pipeline-step-inspection
  (testing "Hovering a pipeline step shows sample data for that step"
    ;; stub -- LSP server not yet implemented
    (is true)))
