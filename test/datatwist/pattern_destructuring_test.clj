(ns datatwist.pattern-destructuring-test
  (:require [clojure.test :refer [deftest testing is]]
            [datatwist.test-helpers :refer [eval-dt eval-dt-last parse-error? throws? throws-type? type-of]]))

;; ==========================================================================
;; Feature 13: String Pattern Destructuring (#p)
;; BDD Source: bdd/13-pattern-destructuring.feature
;;
;; Every deftest maps 1:1 to a BDD Scenario.
;;
;; Phase 1 implements: Sections 1, 4, 5, 6, 7, and selected Section 9 tests.
;; Sections 2, 3, 8, and remaining Section 9 tests are stubs for Phase 2+.
;;
;; Note on syntax: BDD uses `value | pattern -> result` shorthand.
;; In tests we use `value |> (| pattern -> result | _ -> nil)` which pipes
;; the value into a parenthesized guard block — the supported DT form.
;; The BDD also uses `{a b}` shorthand; tests use `{a: a b: b}` (valid DT).
;; ==========================================================================

;; === Section 1: Tier 1 — Simple captures ===

(deftest email-pattern-extracts-user-and-domain
  (testing "Email pattern extracts user and domain"
    ;; "alice@example.com" |> (| #p"{user}@{domain}" -> {user: user domain: domain})
    (is (= {:user "alice" :domain "example.com"}
           (eval-dt
            "\"alice@example.com\"
                |> (| #p\"{user}@{domain}\" -> {user: user domain: domain}
                      | _ -> nil)")))))

(deftest ipv4-pattern-extracts-four-octets
  (testing "IPv4 pattern extracts four octets"
    (is (= {:a "192" :b "168" :c "1" :d "42"}
           (eval-dt
            "\"192.168.1.42\"
                |> (| #p\"{a}.{b}.{c}.{d}\" -> {a: a b: b c: c d: d}
                      | _ -> nil)")))))

(deftest log-pattern-with-complex-literal-separators
  (testing "Log pattern with complex literal separators"
    (is (= {:method "GET" :url "/index.html" :ver "1.1"}
           (eval-dt-last
            "line is \"GET /index.html HTTP/1.1\""
            "line |> (| #p\"{method} {url} HTTP/{ver}\" -> {method: method url: url ver: ver}"
            "         | _ -> nil)")))))

(deftest named-pattern-bound-with-is
  (testing "Named pattern bound with is is reusable"
    (is (= {:user "bob" :domain "test.org"}
           (eval-dt-last
            "email-pat is #p\"{user}@{domain}\""
            "\"bob@test.org\" |> (| email-pat -> {user: user domain: domain}"
            "                      | _ -> nil)")))))

(deftest single-capture-consumes-entire-string-when-no-surrounding-literals
  (testing "Single capture consumes entire string when no surrounding literals"
    (is (= "hello world"
           (eval-dt
            "\"hello world\" |> (| #p\"{msg}\" -> msg | _ -> nil)")))))

(deftest empty-capture-when-literal-matches-but-no-chars-between
  (testing "Empty capture when literal matches but no chars remain after"
    ;; "user@" matches {user}@{domain} with domain = ""
    (is (= {:user "user" :domain ""}
           (eval-dt-last
            "\"user@\" |> (| #p\"{user}@{domain}\" -> {user: user domain: domain}"
            "               | _ -> nil)")))))

(deftest non-greedy-default-first-separator-wins-on-repeated-delimiter
  (testing "Non-greedy default: first separator wins on repeated delimiter"
    ;; "x-y-z" matched by {a}-{b}: a="x", b="y-z" (non-greedy first, greedy last)
    (is (= {:a "x" :b "y-z"}
           (eval-dt
            "\"x-y-z\" |> (| #p\"{a}-{b}\" -> {a: a b: b} | _ -> nil)")))))

(deftest pattern-does-not-match-returns-nil-in-guard-fall-through
  (testing "Pattern does not match — falls through to next arm"
    (is (= "other"
           (eval-dt
            "\"hello\" |> (| #p\"{a}@{b}\" -> \"email\" | _ -> \"other\")")))))

(deftest unmatched-leading-literal-causes-no-match
  (testing "Unmatched leading literal causes no-match"
    (is (= nil
           (eval-dt
            "\"Goodbye World\" |> (| #p\"Hello {who}\" -> who | _ -> nil)")))))

;; === Section 2: Tier 2 — Type hint shorthand ===
;; (Phase 2 — stubs only)

(deftest iso-date-with-4d-2d-2d-type-hints
  (testing "stub — not yet implemented (Phase 2)"))

(deftest type-hint-d-enforces-digits-only-octets
  (testing "stub — not yet implemented (Phase 2)"))

(deftest type-hint-d-rejects-non-digit-content
  (testing "stub — not yet implemented (Phase 2)"))

(deftest exact-length-type-hint-n-captures-fixed-char-count
  (testing "stub — not yet implemented (Phase 2)"))

(deftest exact-length-type-hint-nd-captures-fixed-digit-count
  (testing "stub — not yet implemented (Phase 2)"))

(deftest type-hint-w-captures-word-characters-only
  (testing "stub — not yet implemented (Phase 2)"))

(deftest type-hint-d-does-not-match-letters
  (testing "stub — not yet implemented (Phase 2)"))

;; === Section 3: Tier 3 — Full constraint syntax ===
;; (Phase 3 — stubs only)

(deftest many-constraint-matches-one-or-more-characters
  (testing "stub — not yet implemented (Phase 3)"))

(deftest maybe-constraint-matches-optional-suffix
  (testing "stub — not yet implemented (Phase 3)"))

(deftest maybe-constraint-allows-absent-optional-part
  (testing "stub — not yet implemented (Phase 3)"))

(deftest not-constraint-excludes-a-character-class
  (testing "stub — not yet implemented (Phase 3)"))

(deftest url-pattern-with-not-and-maybe-constraints
  (testing "stub — not yet implemented (Phase 3)"))

(deftest alternation-constraint-matches-one-of-several-literals
  (testing "stub — not yet implemented (Phase 3)"))

(deftest alternation-constraint-rejects-non-matching-literal
  (testing "stub — not yet implemented (Phase 3)"))

(deftest rest-constraint-captures-everything-remaining
  (testing "stub — not yet implemented (Phase 3)"))

(deftest exact-count-constraint-with-digit
  (testing "stub — not yet implemented (Phase 3)"))

(deftest many-digit-used-for-ip-style-parsing
  (testing "stub — not yet implemented (Phase 3)"))

(deftest grouped-alternation-with-parentheses
  (testing "stub — not yet implemented (Phase 3)"))

;; === Section 4: Brace escaping ===

(deftest double-brace-escape-produces-literal-opening-brace
  (testing "{{ in pattern produces literal { in match"
    ;; Pattern {{key}}: {value} matches literal "{key}: hello"
    (is (= "hello"
           (eval-dt
            "\"{key}: hello\" |> (| #p\"{{key}}: {value}\" -> value | _ -> nil)")))))

(deftest double-brace-escape-in-pattern-with-multiple-captures
  (testing "Named pattern with {{ escape reusable via is"
    (is (= "world"
           (eval-dt-last
            "kv-pat is #p\"{{key}}: {value}\""
            "\"{key}: world\" |> (| kv-pat -> value | _ -> nil)")))))

(deftest closing-double-brace-produces-literal-closing-brace
  (testing "}} in pattern produces literal } in match"
    ;; Pattern result {{{n}}} = result { + {n} + }
    (is (= "42"
           (eval-dt
            "\"result {42}\" |> (| #p\"result {{{n}}}\" -> n | _ -> nil)")))))

;; === Section 5: Guard integration ===

(deftest pattern-guard-matches-first-applicable-branch
  (testing "Pattern guard matches first applicable branch"
    (is (= "email"
           (eval-dt-last
            "classify is [input ->"
            "  input"
            "    | #p\"{name}@{domain}\" -> \"email\""
            "    | #p\"{proto}://{host}\" -> \"url\""
            "    | _                    -> \"unknown\""
            "]"
            "classify \"alice@example.com\"")))))

(deftest pattern-guard-falls-through-to-next-arm-on-no-match
  (testing "Pattern guard falls through to next arm on no-match"
    (is (= "unknown"
           (eval-dt-last
            "classify is [input ->"
            "  input"
            "    | #p\"{name}@{domain}\" -> \"email\""
            "    | #p\"{proto}://{host}\" -> \"url\""
            "    | _                    -> \"unknown\""
            "]"
            "classify \"just some text\"")))))

(deftest pattern-guard-selects-url-branch-for-url-shaped-input
  (testing "Pattern guard selects url branch for URL-shaped input"
    (is (= "url"
           (eval-dt-last
            "classify is [input ->"
            "  input"
            "    | #p\"{name}@{domain}\" -> \"email\""
            "    | #p\"{proto}://{host}\" -> \"url\""
            "    | _                    -> \"unknown\""
            "]"
            "classify \"https://example.com\"")))))

(deftest multiple-pattern-guards-in-sequence-each-tested-independently
  (testing "Multiple pattern guards in sequence — each tested independently"
    (is (= ["email" "other"]
           (eval-dt-last
            "r1 is \"alice@example.com\" |> (| #p\"{u}@{d}\" -> \"email\" | _ -> \"other\")"
            "r2 is \"https://example.com\" |> (| #p\"{u}@{d}\" -> \"email\" | _ -> \"other\")"
            "[r1 r2]")))))

(deftest pattern-guard-with-when-clause-for-additional-condition
  (testing "Pattern guard with when clause for additional condition"
    (is (= "internal"
           (eval-dt
            "\"alice@example.com\"
                |> (| #p\"{user}@{domain}\" when domain = \"example.com\" -> \"internal\"
                      | #p\"{user}@{domain}\"                             -> \"external\"
                      | _                                               -> \"unknown\")")))))

;; === Section 6: Named patterns as first-class values ===

(deftest pattern-bound-with-is-is-a-first-class-value
  (testing "Pattern bound with is is a first-class value (Tier 1 only)"
    ;; Note: BDD uses {y:4d}-{m:2d}-{d:2d} (Tier 2); this test uses simple {y}-{m}-{d}
    (is (= {:y "2024" :m "03" :d "22"}
           (eval-dt-last
            "date-fmt is #p\"{y}-{m}-{d}\""
            "\"2024-03-22\" |> (| date-fmt -> {y: y m: m d: d} | _ -> nil)")))))

(deftest named-pattern-reused-across-multiple-match-sites
  (testing "Named pattern reused across multiple match sites"
    (is (= {:r1 "10" :r2 "192"}
           (eval-dt-last
            "ip-pat is #p\"{a}.{b}.{c}.{d}\""
            "r1 is \"10.0.0.1\" |> (| ip-pat -> a | _ -> nil)"
            "r2 is \"192.168.1.1\" |> (| ip-pat -> a | _ -> nil)"
            "{r1: r1 r2: r2}")))))

(deftest pattern-passed-as-function-argument
  (testing "Pattern passed as function argument"
    ;; parse-field applies pat to text; returns original text if matched, else nil
    (is (= "alice@example.com"
           (eval-dt-last
            "parse-field is [text pat ->"
            "  text |> (| pat -> text | _ -> nil)"
            "]"
            "email-pat is #p\"{user}@{domain}\""
            "parse-field \"alice@example.com\" email-pat")))))

;; === Section 7: Pipeline integration ===

(deftest extract-function-applies-pattern-and-returns-object-or-nil
  (testing "extract function applies pattern and returns object or nil"
    (is (= {:user "alice" :domain "example.com"}
           (eval-dt-last
            "email-pat is #p\"{user}@{domain}\""
            "extract \"alice@example.com\" email-pat")))))

(deftest extract-returns-nil-when-pattern-does-not-match
  (testing "extract returns nil when pattern does not match"
    (is (= nil
           (eval-dt-last
            "email-pat is #p\"{user}@{domain}\""
            "extract \"hello\" email-pat")))))

(deftest pipeline-map-with-extract-over-list-of-strings
  (testing "Pipeline: map extract over list of strings"
    (is (= [{:user "alice" :domain "example.com"}
            {:user "bob" :domain "test.org"}]
           (eval-dt-last
            "email-pat is #p\"{user}@{domain}\""
            "emails is [\"alice@example.com\" \"bob@test.org\"]"
            "emails |> map [s -> extract s email-pat]")))))

(deftest pipeline-filter-with-match-keeps-only-matching-strings
  (testing "Pipeline: filter with match? keeps only matching strings"
    (is (= ["alice@example.com" "bob@test.org"]
           (eval-dt-last
            "email-pat is #p\"{user}@{domain}\""
            "items is [\"alice@example.com\" \"hello\" \"bob@test.org\"]"
            "items |> filter [s -> match? s email-pat]")))))

(deftest pipeline-combining-extract-and-filter-to-drop-non-matches
  (testing "Pipeline: extract + filter to drop non-matches (Tier 1 only)"
    ;; Note: BDD uses {y:4d}-{m:2d}-{d:2d} (Tier 2); this uses simple {y}-{m}-{d}
    (is (= [{:y "2024" :m "01" :d "15"} {:y "2024" :m "03" :d "22"}]
           (eval-dt-last
            "date-pat is #p\"{y}-{m}-{d}\""
            "lines is [\"2024-01-15\" \"bad-data\" \"2024-03-22\"]"
            "lines |> map [s -> extract s date-pat] |> filter [r -> r != nil]")))))

(deftest pipeline-filtering-extracted-month-field
  (testing "Pipeline: filter by extracted month field (Tier 1 only)"
    ;; Note: BDD uses {y:4d}-{m:2d}-{d:2d} (Tier 2); this uses simple {y}-{m}-{d}
    (is (= ["15" "20"]
           (eval-dt-last
            "date-pat is #p\"{y}-{m}-{d}\""
            "dates is [\"2024-01-15\" \"2024-02-28\" \"2024-01-20\"]"
            "dates |> map [s -> extract s date-pat] |> filter [r -> r != nil] |> filter [r -> r.m = \"01\"] |> map [r -> r.d]")))))

;; === Section 8: Compile-time errors ===

(deftest adjacent-unconstrained-captures-are-a-compile-time-error
  (testing "Adjacent unconstrained captures raise a compile-time error"
    (is (throws? "#p\"{a}{b}\""))))

(deftest constrained-adjacent-captures-are-valid
  (testing "stub — not yet implemented (Phase 2: requires type hints)"))

(deftest rest-in-non-final-position-is-a-compile-time-error
  (testing "stub — not yet implemented (Phase 3: requires rest constraint)"))

(deftest nested-quantifiers-are-a-compile-time-error
  (testing "stub — not yet implemented (Phase 3: requires constraint mini-language)"))

;; === Section 9: Edge cases and failure modes ===

(deftest empty-capture-is-valid-matched-text-happens-to-be-empty
  (testing "Empty capture is valid — matched text happens to be empty"
    ;; "-" matches {a}-{b}: a="" and b=""
    (is (= {:a "" :b ""}
           (eval-dt
            "\"-\" |> (| #p\"{a}-{b}\" -> {a: a b: b} | _ -> nil)")))))

(deftest pattern-applied-to-empty-string-can-match
  (testing "Pattern applied to empty string can match"
    ;; {a} with empty string: a=""
    (is (= ""
           (eval-dt
            "\"\" |> (| #p\"{a}\" -> a | _ -> nil)")))))

(deftest pattern-is-full-match-anchored-trailing-chars-cause-no-match
  (testing "stub — not yet implemented (Phase 2: requires :w / many letter constraint)"))

(deftest non-string-input-falls-through-in-guard-no-error
  (testing "Non-string input falls through in guard — no error"
    ;; 42 is not a string; pattern arm fails gracefully, falls to wildcard
    (is (= "not a string"
           (eval-dt
            "42 |> (| #p\"{x}\" -> \"matched\" | _ -> \"not a string\")")))))

(deftest extract-returns-nil-for-non-string-input
  (testing "extract returns nil for non-string input"
    (is (= nil
           (eval-dt-last
            "email-pat is #p\"{user}@{domain}\""
            "extract 42 email-pat")))))

(deftest unsatisfiable-constraint-too-few-chars-returns-no-match
  (testing "stub — not yet implemented (Phase 2: requires type hints)"))

(deftest all-captured-values-are-strings-explicit-conversion-needed-for-arithmetic
  (testing "All captured values are strings — explicit conversion needed"
    ;; Captures are always strings; to-int converts to numeric
    (is (= 2024
           (eval-dt-last
            "date-pat is #p\"{y}-{m}-{d}\""
            "to-int (extract \"2024-01-15\" date-pat).y")))))

(deftest wildcard-capture-matches-but-does-not-bind
  (testing "Wildcard {_} captures but does not bind to env"
    ;; {_} is consumed in the regex but not bound; other captures still work
    (is (= "world"
           (eval-dt
            "\"hello world\" |> (| #p\"{_} {tail}\" -> tail | _ -> nil)")))))

(deftest pattern-destructuring-via-is-binds-all-captures-in-scope
  (testing "stub — not yet implemented (Phase 1 extension: Pattern on LHS of is)"))

(deftest pattern-destructuring-via-is-throws-on-no-match
  (testing "stub — not yet implemented (Phase 1 extension: Pattern on LHS of is)"))

;; === Section 10: Nginx log example (integration smoke test) ===

(deftest nginx-style-log-pattern-extracts-all-fields
  (testing "stub — not yet implemented (Phase 2+: requires Tier 2 or Tier 3 constraints)"))
