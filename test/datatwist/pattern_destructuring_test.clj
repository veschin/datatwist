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
;; Section 2 (Tier 2 type hints) is also implemented with real assertions.
;; Sections 3, 8, and remaining Section 9 tests are stubs for Phase 2+.
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
  (testing "4d-2d-2d type hints extract year, month, day from ISO date string"
    (is (= {:y "2024" :m "01" :d "15"}
           (eval-dt "\"2024-01-15\" |> (| #p\"{y:4d}-{m:2d}-{d:2d}\" -> {y: y  m: m  d: d} | _ -> nil)")))))

(deftest type-hint-d-enforces-digits-only-octets
  (testing ":d hint matches digit-only octets in an IP address"
    (is (= {:a "10" :b "0" :c "0" :d "1"}
           (eval-dt "\"10.0.0.1\" |> (| #p\"{a:d}.{b:d}.{c:d}.{d:d}\" -> {a: a  b: b  c: c  d: d} | _ -> nil)")))))

(deftest type-hint-d-rejects-non-digit-content
  (testing ":d hint does not match alphabetic octets — falls through to default guard"
    (is (= "no match"
           (eval-dt "\"abc.def.ghi.jkl\" |> (| #p\"{a:d}.{b:d}.{c:d}.{d:d}\" -> {a: a  b: b  c: c  d: d} | _ -> \"no match\")")))))

(deftest exact-length-type-hint-n-captures-fixed-char-count
  (testing ":N hint captures exactly N characters"
    (is (= {:code "ABC" :rest "remainder"}
           (eval-dt "\"ABC-remainder\" |> (| #p\"{code:3}-{rest}\" -> {code: code  rest: rest} | _ -> nil)")))))

(deftest exact-length-type-hint-nd-captures-fixed-digit-count
  (testing ":Nd hint captures exactly N digits and values can be converted to int"
    (is (= {:y 2024 :m 1 :d 15}
           (eval-dt-last
            "date is \"2024-01-15\""
            "date |> (| #p\"{y:4d}-{m:2d}-{d:2d}\" -> {y: to-int y  m: to-int m  d: to-int d}"
            "          | _ -> nil)")))))

(deftest type-hint-w-captures-word-characters-only
  (testing ":w hint captures word characters, stopping before a space"
    (is (= {:word "hello"}
           (eval-dt
            "\"hello world\" |> (| #p\"{word:w} {rest}\" -> {word: word} | _ -> nil)")))))

(deftest type-hint-d-does-not-match-letters
  (testing ":2d hint rejects segments containing letters — no match"
    (is (= "no match"
           (eval-dt
            "\"ab-12\" |> (| #p\"{a:2d}-{b:2d}\" -> {a: a b: b} | _ -> \"no match\")")))))

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
  (testing "Pipeline: map extract over list of strings, extract years from dates"
    (is (= ["2024" "2024" "2024"]
           (eval-dt-last
            "date-fmt is #p\"{y:4d}-{m:2d}-{d:2d}\""
            "dates is [\"2024-01-15\" \"2024-02-28\" \"2024-12-01\"]"
            "dates |> map [s -> extract s date-fmt] |> map [r -> r.y]")))))

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
  (testing "All captured values are strings — explicit conversion needed for arithmetic"
    ;; Captures are always strings; to-int converts each before adding
    (is (= 2040
           (eval-dt
            "\"2024-01-15\" |> (| #p\"{y:4d}-{m:2d}-{d:2d}\" -> to-int y + to-int m + to-int d | _ -> nil)")))))

(deftest wildcard-capture-matches-but-does-not-bind
  (testing "Wildcard {_} matches but does not bind — used in filter over list"
    ;; {_}@{_} matches email-shaped strings; {_} captures are discarded
    (is (= ["alice@example.com" "bob@test.org"]
           (eval-dt-last
            "inputs is [\"alice@example.com\" \"bob@test.org\" \"not-an-email\"]"
            "inputs |> filter [s -> match? s #p\"{_}@{_}\"]")))))

(deftest pattern-destructuring-via-is-binds-all-captures-in-scope
  (testing "stub — not yet implemented (Phase 1 extension: Pattern on LHS of is)"))

(deftest pattern-destructuring-via-is-throws-on-no-match
  (testing "stub — not yet implemented (Phase 1 extension: Pattern on LHS of is)"))

;; === Section 10: Nginx log example (integration smoke test) ===

(deftest nginx-style-log-pattern-extracts-all-fields
  (testing "stub — not yet implemented (Phase 2+: requires Tier 2 or Tier 3 constraints)"))

;; === Section 11: Reader macro syntax — whitespace and positioning ===
;; Design decision: #p"..." is a single lexeme. The sigil and delimiter
;; must be adjacent, matching Clojure's #"regex" convention.

(deftest space-between-hash-p-and-quote-is-a-parse-error
  (testing "Space between #p and opening quote is a parse error"
    ;; #p"..." is a single lexeme — whitespace breaks it
    (is (parse-error? "#p \"hello {name}\""))))

(deftest newline-between-hash-p-and-quote-is-a-parse-error
  (testing "Newline between #p and opening quote is a parse error"
    (is (parse-error? "#p\n\"hello {name}\""))))

;; === Section 12: Literal-only patterns and empty pattern ===

(deftest empty-pattern-evaluates-to-a-valid-pattern-object
  (testing "Empty pattern #p\"\" evaluates to a pattern map with :dt/type :pattern"
    (let [result (eval-dt "#p\"\"")]
      (is (map? result))
      (is (= :pattern (:dt/type result))))))

(deftest empty-pattern-matches-only-empty-string
  (testing "Empty pattern matches only the empty string"
    (is (= "matched"
           (eval-dt "\"\" |> (| #p\"\" -> \"matched\" | _ -> \"no match\")")))))

(deftest empty-pattern-does-not-match-non-empty-string
  (testing "Empty pattern does not match a non-empty string"
    (is (= "no match"
           (eval-dt "\"hello\" |> (| #p\"\" -> \"matched\" | _ -> \"no match\")")))))

(deftest pattern-with-only-literal-text-matches-exact-string
  (testing "Literal-only pattern (no captures) matches exact string"
    (is (= "matched"
           (eval-dt "\"hello\" |> (| #p\"hello\" -> \"matched\" | _ -> \"no match\")")))))

(deftest pattern-with-only-literal-text-does-not-match-different-string
  (testing "Literal-only pattern does not match a different string"
    (is (= "no match"
           (eval-dt "\"world\" |> (| #p\"hello\" -> \"matched\" | _ -> \"no match\")")))))

(deftest extract-returns-empty-map-for-literal-only-pattern-on-match
  (testing "extract returns empty map {} when literal-only pattern matches"
    ;; No captures means no fields — successful match returns {}
    (is (= {}
           (eval-dt "extract \"hello\" #p\"hello\"")))))

;; === Section 13: Regex special characters in literal segments ===
;; Literal text in #p"..." is always Pattern/quote'd — regex metacharacters
;; match themselves, never as regex syntax.

(deftest dot-in-literal-matches-only-literal-dot-not-any-character
  (testing "Dot in literal segment matches only a literal dot"
    ;; "aXb" should NOT match {a}.{b} because X != .
    (is (= "no match"
           (eval-dt "\"aXb\" |> (| #p\"{a}.{b}\" -> \"matched\" | _ -> \"no match\")")))
    ;; "a.b" SHOULD match {a}.{b}
    (is (= {:a "a" :b "b"}
           (eval-dt "\"a.b\" |> (| #p\"{a}.{b}\" -> {a: a b: b} | _ -> nil)")))))

(deftest star-in-literal-matches-literal-asterisk
  (testing "Star (*) in literal segment matches literal asterisk character"
    (is (= {:x "a" :y "b"}
           (eval-dt "\"a*b\" |> (| #p\"{x}*{y}\" -> {x: x y: y} | _ -> nil)")))))

(deftest plus-in-literal-matches-literal-plus-sign
  (testing "Plus (+) in literal segment matches literal plus character"
    (is (= {:x "a" :y "b"}
           (eval-dt "\"a+b\" |> (| #p\"{x}+{y}\" -> {x: x y: y} | _ -> nil)")))))

(deftest question-mark-in-literal-matches-literal-question-mark
  (testing "Question mark (?) in literal segment matches literal ? character"
    (is (= "is it ok"
           (eval-dt "\"is it ok?\" |> (| #p\"{msg}?\" -> msg | _ -> nil)")))))

(deftest parentheses-in-literal-match-literal-parentheses
  (testing "Parentheses in literal segment match literal ( and ) characters"
    (is (= "hello"
           (eval-dt "\"(hello)\" |> (| #p\"({msg})\" -> msg | _ -> nil)")))))

;; === Section 14: Pattern as inline expression (first-class usage) ===

(deftest pattern-as-direct-function-argument-inline-not-bound
  (testing "Pattern used directly as function argument without being bound to a name"
    (is (= {:user "alice" :domain "example.com"}
           (eval-dt "extract \"alice@example.com\" #p\"{user}@{domain}\"")))))

(deftest pattern-in-pipeline-as-inline-argument-to-extract
  (testing "Pattern used inline as argument to extract in a pipeline"
    (is (= {:user "alice" :domain "example.com"}
           (eval-dt "\"alice@example.com\" |> extract #p\"{user}@{domain}\"")))))

(deftest pattern-on-own-line-in-multi-line-code-is-valid-expression
  (testing "Pattern on its own line is a valid standalone expression"
    ;; The pattern evaluates to a pattern object; the last expression's value is returned
    (is (= 2
           (eval-dt-last
            "x is 1"
            "#p\"{a}@{b}\""
            "x + 1")))))

(deftest match-with-inline-pattern-not-bound-to-variable
  (testing "match? accepts an inline (unbound) pattern literal"
    (is (= true
           (eval-dt "match? \"alice@example.com\" #p\"{u}@{d}\"")))))
