Feature: Pattern Matching & Guards
  DataTwist provides pattern matching and guard expressions for conditional
  logic. Guards test boolean conditions. Structural patterns match the shape
  of data (objects, lists, literals). The two forms are disambiguated by
  context: `| {..}` or `| [..]` is structural, `| expr` is a guard.
  First match wins (top to bottom). A `when` clause adds an extra boolean
  condition after a structural pattern. `_` is the catch-all / default case.

  Background:
    Given the DataTwist compiler is available

  # ---------------------------------------------------------------------------
  # SECTION 1: Guard expressions (boolean conditions)
  # ---------------------------------------------------------------------------

  Scenario: Simple guard expression with binding
    Given the DataTwist source:
      """
      tier is
        | amount > 1000 -> "gold"
        | amount > 100  -> "silver"
        | _             -> "bronze"
      """
    When I compile and evaluate with amount bound to 500
    Then the result of tier is "silver"

  Scenario: Guard expression evaluates first matching branch
    Given the DataTwist source:
      """
      label is
        | x > 100 -> "huge"
        | x > 10  -> "big"
        | x > 0   -> "small"
        | _       -> "zero-or-negative"
      """
    When I compile and evaluate with x bound to 50
    Then the result of label is "big"

  Scenario: Guard with logical operators
    Given the DataTwist source:
      """
      access is
        | role = "admin" or role = "superadmin" -> "full"
        | role = "editor" and active            -> "write"
        | _                                     -> "read"
      """
    When I compile and evaluate with role bound to "editor" and active bound to true
    Then the result of access is "write"

  Scenario: Guard with comparison chains
    Given the DataTwist source:
      """
      bucket is
        | age >= 0 and age < 13  -> "child"
        | age >= 13 and age < 18 -> "teen"
        | age >= 18              -> "adult"
        | _                      -> "invalid"
      """
    When I compile and evaluate with age bound to 15
    Then the result of bucket is "teen"

  Scenario: Guard falls through to default
    Given the DataTwist source:
      """
      result is
        | x > 100 -> "big"
        | _       -> "small"
      """
    When I compile and evaluate with x bound to 3
    Then the result of result is "small"

  Scenario: Guard with function call in condition
    Given the DataTwist source:
      """
      even? is [n -> n % 2 = 0]
      label is
        | even? x -> "even"
        | _       -> "odd"
      """
    When I compile and evaluate with x bound to 7
    Then the result of label is "odd"

  Scenario: Single-line guard expression after is
    Given the DataTwist source:
      """
      tier is | x > 5 -> "high" | _ -> "low"
      """
    When I compile and evaluate with x bound to 10
    Then the result of tier is "high"

  # ---------------------------------------------------------------------------
  # SECTION 2: Guards in object fields
  # ---------------------------------------------------------------------------

  Scenario: Guard as object field value (multi-line)
    Given the DataTwist source:
      """
      result is {
        tier:
          | amount > 1000 -> "gold"
          | _             -> "bronze"
      }
      """
    When I compile and evaluate with amount bound to 2000
    Then result.tier is "gold"

  Scenario: Guard as object field value (inline)
    Given the DataTwist source:
      """
      result is {tier: | amount > 1000 -> "gold" | _ -> "bronze"}
      """
    When I compile and evaluate with amount bound to 50
    Then result.tier is "bronze"

  Scenario: Multiple guard fields in one object
    Given the DataTwist source:
      """
      result is {
        tier:
          | spending > 1000 -> "gold"
          | _               -> "bronze"
        risk:
          | age < 18 -> "minor"
          | _        -> "standard"
      }
      """
    When I compile and evaluate with spending bound to 2000 and age bound to 15
    Then result.tier is "gold"
    And result.risk is "minor"

  # ---------------------------------------------------------------------------
  # SECTION 3: Guards inside pipelines
  # ---------------------------------------------------------------------------

  Scenario: Guard in pipeline map
    Given the DataTwist source:
      """
      users |> map {
        name: _.name
        tier:
          | _.spending > 1000 -> "gold"
          | _                 -> "bronze"
      }
      """
    When I compile and evaluate with users bound to [{name: "Alice" spending: 1500} {name: "Bob" spending: 200}]
    Then the result is [{name: "Alice" tier: "gold"} {name: "Bob" tier: "bronze"}]

  Scenario: Guard in pipeline map using wildcard access
    Given the DataTwist source:
      """
      orders |> map {
        status:
          | _.total > 500 and _.paid -> "confirmed"
          | _.total > 500            -> "pending-payment"
          | _                        -> "small-order"
      }
      """
    When I compile and evaluate with orders bound to [{total: 600 paid: true} {total: 600 paid: false} {total: 50 paid: true}]
    Then the result is [{status: "confirmed"} {status: "pending-payment"} {status: "small-order"}]

  # ---------------------------------------------------------------------------
  # SECTION 4: Structural pattern matching
  # ---------------------------------------------------------------------------

  Scenario: Structural match on object type field
    Given the DataTwist source:
      """
      classify is [data ->
        | {type: "book"}  -> "book"
        | {type: "movie"} -> "movie"
        | _               -> "unknown"
      ]
      """
    When I call classify with {type: "book" title: "Dune"}
    Then the result is "book"

  Scenario: Structural match on object with multiple fields
    Given the DataTwist source:
      """
      classify is [data ->
        | {type: "book" format: "hardcover"} -> "hardcover-book"
        | {type: "book"}                     -> "book"
        | _                                  -> "other"
      ]
      """
    When I call classify with {type: "book" format: "hardcover" pages: 300}
    Then the result is "hardcover-book"

  Scenario: Structural match on list patterns
    Given the DataTwist source:
      """
      describe is [data ->
        | []        -> "empty"
        | [x]       -> "single"
        | [x y]     -> "pair"
        | [x & rest] -> "collection"
        | _         -> "not a list"
      ]
      """
    When I call describe with [1 2 3 4]
    Then the result is "collection"

  Scenario: Structural match on empty list
    Given the DataTwist source:
      """
      describe is [data ->
        | [] -> "empty"
        | _  -> "non-empty"
      ]
      """
    When I call describe with []
    Then the result is "empty"

  Scenario: Structural match on single-element list
    Given the DataTwist source:
      """
      describe is [data ->
        | [x] -> "single"
        | _   -> "other"
      ]
      """
    When I call describe with [42]
    Then the result is "single"

  Scenario: Structural match distinguishes object from list
    Given the DataTwist source:
      """
      what is [data ->
        | {type: _} -> "has-type-field"
        | [_ & _]   -> "list"
        | _         -> "something-else"
      ]
      """
    When I call what with {type: "x"}
    Then the result is "has-type-field"
    When I call what with [1 2 3]
    Then the result is "list"

  # ---------------------------------------------------------------------------
  # SECTION 5: Literal patterns
  # ---------------------------------------------------------------------------

  Scenario: Match on integer literal
    Given the DataTwist source:
      """
      describe is [n ->
        | 0  -> "zero"
        | 1  -> "one"
        | 42 -> "answer"
        | _  -> "other"
      ]
      """
    When I call describe with 42
    Then the result is "answer"

  Scenario: Match on string literal
    Given the DataTwist source:
      """
      respond is [status ->
        | "ok"    -> "success"
        | "error" -> "failure"
        | _       -> "unknown"
      ]
      """
    When I call respond with "error"
    Then the result is "failure"

  Scenario: Match on boolean literal
    Given the DataTwist source:
      """
      describe is [flag ->
        | true  -> "yes"
        | false -> "no"
        | _     -> "not a boolean"
      ]
      """
    When I call describe with true
    Then the result is "yes"

  Scenario: Match on nil
    Given the DataTwist source:
      """
      safe is [val ->
        | nil -> "nothing"
        | _   -> "something"
      ]
      """
    When I call safe with nil
    Then the result is "nothing"

  # ---------------------------------------------------------------------------
  # SECTION 6: Variable binding in structural patterns
  # ---------------------------------------------------------------------------

  Scenario: Bind variable from object pattern
    Given the DataTwist source:
      """
      greet is [person ->
        | {name: n} -> format "Hello, %s!" n
        | _         -> "Hello, stranger!"
      ]
      """
    When I call greet with {name: "Alice" age: 30}
    Then the result is "Hello, Alice!"

  Scenario: Bind multiple variables from object pattern
    Given the DataTwist source:
      """
      summary is [item ->
        | {name: n price: p} -> format "%s costs %s" n p
        | _                  -> "unknown item"
      ]
      """
    When I call summary with {name: "Widget" price: 9.99}
    Then the result is "Widget costs 9.99"

  Scenario: Bind variable from list pattern
    Given the DataTwist source:
      """
      head is [xs ->
        | [first & _] -> first
        | _           -> nil
      ]
      """
    When I call head with [10 20 30]
    Then the result is 10

  Scenario: Bind head and tail from list
    Given the DataTwist source:
      """
      parts is [xs ->
        | [h & t] -> {head: h tail: t}
        | _       -> {head: nil tail: []}
      ]
      """
    When I call parts with [1 2 3]
    Then the result is {head: 1 tail: [2 3]}

  # ---------------------------------------------------------------------------
  # SECTION 7: when clause (guard after structural pattern)
  # ---------------------------------------------------------------------------

  Scenario: Structural pattern with when guard
    Given the DataTwist source:
      """
      classify is [data ->
        | {type: "book" pages: p} when p > 500 -> "epic"
        | {type: "book"}                        -> "book"
        | _                                     -> "other"
      ]
      """
    When I call classify with {type: "book" pages: 800}
    Then the result is "epic"

  Scenario: when guard fails, falls through to next branch
    Given the DataTwist source:
      """
      classify is [data ->
        | {type: "book" pages: p} when p > 500 -> "epic"
        | {type: "book"}                        -> "book"
        | _                                     -> "other"
      ]
      """
    When I call classify with {type: "book" pages: 200}
    Then the result is "book"

  Scenario: when guard with multiple conditions
    Given the DataTwist source:
      """
      classify is [data ->
        | {type: "movie" rating: r year: y} when r > 8 and y > 2000 -> "modern-classic"
        | {type: "movie" rating: r} when r > 8                      -> "classic"
        | {type: "movie"}                                            -> "movie"
        | _                                                          -> "other"
      ]
      """
    When I call classify with {type: "movie" rating: 9 year: 2010}
    Then the result is "modern-classic"

  Scenario: when guard using bound variables from pattern
    Given the DataTwist source:
      """
      analyze is [record ->
        | {name: n age: a} when a > 18 -> format "%s is adult" n
        | {name: n age: a}             -> format "%s is minor" n
        | _                            -> "no name"
      ]
      """
    When I call analyze with {name: "Bob" age: 25}
    Then the result is "Bob is adult"
    When I call analyze with {name: "Eve" age: 12}
    Then the result is "Eve is minor"

  # ---------------------------------------------------------------------------
  # SECTION 8: Nested structural patterns
  # ---------------------------------------------------------------------------

  Scenario: Nested object pattern
    Given the DataTwist source:
      """
      city-of is [person ->
        | {address: {city: c}} -> c
        | _                    -> "unknown"
      ]
      """
    When I call city-of with {name: "Alice" address: {city: "Moscow" zip: "101000"}}
    Then the result is "Moscow"

  Scenario: Object pattern with nested list
    Given the DataTwist source:
      """
      first-tag is [item ->
        | {tags: [t & _]} -> t
        | _               -> "untagged"
      ]
      """
    When I call first-tag with {name: "post" tags: ["clojure" "jvm"]}
    Then the result is "clojure"

  Scenario: List of objects structural match
    Given the DataTwist source:
      """
      first-name is [data ->
        | [{name: n} & _] -> n
        | _               -> "nobody"
      ]
      """
    When I call first-name with [{name: "Alice"} {name: "Bob"}]
    Then the result is "Alice"

  # ---------------------------------------------------------------------------
  # SECTION 9: Pattern matching inside function bodies
  # ---------------------------------------------------------------------------

  Scenario: Pattern matching as function body
    Given the DataTwist source:
      """
      classify is [data ->
        | {type: "book"}  -> "book"
        | {type: "movie"} -> "movie"
        | nil             -> "nothing"
        | _               -> "unknown"
      ]
      """
    When I call classify with {type: "movie" title: "Arrival"}
    Then the result is "movie"

  Scenario: Function with guard (not structural)
    Given the DataTwist source:
      """
      abs is [x ->
        | x >= 0 -> x
        | _      -> 0 - x
      ]
      """
    When I call abs with -5
    Then the result is 5
    When I call abs with 3
    Then the result is 3

  Scenario: Function with mixed guard and structural branches
    Given the DataTwist source:
      """
      process is [input ->
        | nil          -> "nil-input"
        | {error: msg} -> format "Error: %s" msg
        | _            -> "ok"
      ]
      """
    When I call process with nil
    Then the result is "nil-input"
    When I call process with {error: "timeout"}
    Then the result is "Error: timeout"
    When I call process with {data: 42}
    Then the result is "ok"

  # ---------------------------------------------------------------------------
  # SECTION 10: Default / catch-all
  # ---------------------------------------------------------------------------

  Scenario: Default branch with underscore
    Given the DataTwist source:
      """
      safe-div is [a b ->
        | b = 0 -> nil
        | _     -> a / b
      ]
      """
    When I call safe-div with 10 and 0
    Then the result is nil
    When I call safe-div with 10 and 2
    Then the result is 5

  Scenario: Default branch fires when no other branch matches
    Given the DataTwist source:
      """
      identify is [x ->
        | "cat"  -> "feline"
        | "dog"  -> "canine"
        | _      -> "unknown species"
      ]
      """
    When I call identify with "hamster"
    Then the result is "unknown species"

  # ---------------------------------------------------------------------------
  # SECTION 11: First-match semantics (order matters)
  # ---------------------------------------------------------------------------

  Scenario: First matching branch wins
    Given the DataTwist source:
      """
      classify is [data ->
        | {type: "book"} -> "matched-first"
        | {type: "book"} -> "matched-second"
        | _              -> "default"
      ]
      """
    When I call classify with {type: "book"}
    Then the result is "matched-first"

  Scenario: More specific pattern should be listed before general
    Given the DataTwist source:
      """
      classify is [data ->
        | {type: "book" pages: p} when p > 500 -> "epic-book"
        | {type: "book"}                        -> "regular-book"
        | {type: _}                             -> "has-type"
        | _                                     -> "anything"
      ]
      """
    When I call classify with {type: "book" pages: 800}
    Then the result is "epic-book"
    When I call classify with {type: "book" pages: 100}
    Then the result is "regular-book"
    When I call classify with {type: "vinyl"}
    Then the result is "has-type"
    When I call classify with 42
    Then the result is "anything"

  # ---------------------------------------------------------------------------
  # SECTION 12: Multi-line and formatting
  # ---------------------------------------------------------------------------

  Scenario: Each guard branch on its own line
    Given the DataTwist source:
      """
      tier is
        | amount > 1000 -> "gold"
        | amount > 100  -> "silver"
        | _             -> "bronze"
      """
    Then the source parses successfully

  Scenario: All branches on one line
    Given the DataTwist source:
      """
      tier is | amount > 1000 -> "gold" | amount > 100 -> "silver" | _ -> "bronze"
      """
    Then the source parses successfully

  Scenario: Guard block indented inside object field
    Given the DataTwist source:
      """
      result is {
        tier:
          | _.spending > 1000 -> "gold"
          | _.spending > 100  -> "silver"
          | _                 -> "bronze"
        name: _.name
      }
      """
    Then the source parses successfully

  # ---------------------------------------------------------------------------
  # SECTION 13: Interaction with pipe operator
  # ---------------------------------------------------------------------------

  Scenario: Pipeline result feeds into guard via binding
    Given the DataTwist source:
      """
      total is orders |> map _.amount |> sum
      tier is
        | total > 1000 -> "gold"
        | _            -> "bronze"
      """
    When I compile and evaluate with orders bound to [{amount: 300} {amount: 400} {amount: 500}]
    Then the result of tier is "gold"

  Scenario: Guard expression as argument to pipeline map
    Given the DataTwist source:
      """
      items |> map [item ->
        | item.weight > 50 -> "heavy"
        | _                -> "light"
      ]
      """
    When I compile and evaluate with items bound to [{weight: 60} {weight: 20}]
    Then the result is ["heavy" "light"]

  # ---------------------------------------------------------------------------
  # SECTION 14: Exhaustiveness
  # ---------------------------------------------------------------------------

  Scenario: Guard block without default produces a warning
    Given the DataTwist source:
      """
      label is
        | x > 10 -> "big"
        | x > 0  -> "small"
      """
    When I compile
    Then a warning is emitted about non-exhaustive guard
    And the result is nil when no branch matches

  Scenario: Guard block with default produces no warning
    Given the DataTwist source:
      """
      label is
        | x > 10 -> "big"
        | _      -> "small"
      """
    When I compile
    Then no warning is emitted about non-exhaustive guard

  # ---------------------------------------------------------------------------
  # SECTION 15: Edge cases and corner cases
  # ---------------------------------------------------------------------------

  Scenario: Empty object pattern matches any object
    Given the DataTwist source:
      """
      is-obj is [data ->
        | {} -> "is-object"
        | _  -> "not-object"
      ]
      """
    When I call is-obj with {name: "Alice"}
    Then the result is "is-object"
    When I call is-obj with 42
    Then the result is "not-object"

  Scenario: Nil input to structural match
    Given the DataTwist source:
      """
      check is [data ->
        | nil         -> "nil"
        | {type: _}   -> "has-type"
        | _           -> "other"
      ]
      """
    When I call check with nil
    Then the result is "nil"

  Scenario: Deeply nested nil-tolerant access in guard
    Given the DataTwist source:
      """
      result is
        | data.config.settings.theme = "dark" -> "dark-mode"
        | _                                   -> "default-mode"
      """
    When I compile and evaluate with data bound to {config: nil}
    Then the result of result is "default-mode"

  Scenario: Guard condition referencing undefined variable evaluates to nil
    Given the DataTwist source:
      """
      result is
        | unknown-var > 10 -> "big"
        | _                -> "fallback"
      """
    When I compile and evaluate
    Then the result of result is "fallback"

  Scenario: Guard with result expression containing function call
    Given the DataTwist source:
      """
      greet is [person ->
        | {name: n age: a} when a >= 18 -> format "Mr/Ms %s" n
        | {name: n}                     -> format "Young %s" n
        | _                             -> "stranger"
      ]
      """
    When I call greet with {name: "Alice" age: 30}
    Then the result is "Mr/Ms Alice"

  Scenario: Guard result is a complex expression
    Given the DataTwist source:
      """
      compute is [x ->
        | x > 0 -> x * 2 + 1
        | _     -> 0
      ]
      """
    When I call compute with 5
    Then the result is 11

  Scenario: Guard result is an object
    Given the DataTwist source:
      """
      wrap is [val ->
        | val > 0 -> {status: "positive" value: val}
        | _       -> {status: "non-positive" value: 0}
      ]
      """
    When I call wrap with 5
    Then the result is {status: "positive" value: 5}

  Scenario: Guard result is a list
    Given the DataTwist source:
      """
      to-list is [val ->
        | nil -> []
        | _   -> [val]
      ]
      """
    When I call to-list with nil
    Then the result is []
    When I call to-list with 42
    Then the result is [42]

  Scenario: Nested guards are not allowed (parse error)
    Given the DataTwist source:
      """
      result is
        | x > 5 ->
          | x > 10 -> "very big"
          | _      -> "big"
        | _ -> "small"
      """
    When I compile
    Then a parse error is produced

  Scenario: Pattern matching with rest binding in list
    Given the DataTwist source:
      """
      len-class is [xs ->
        | []          -> "empty"
        | [_]         -> "one"
        | [_ _]       -> "two"
        | [_ _ & _]   -> "many"
      ]
      """
    When I call len-class with [1 2 3 4 5]
    Then the result is "many"

  Scenario: Structural match with literal value in object
    Given the DataTwist source:
      """
      check-status is [resp ->
        | {status: 200}          -> "ok"
        | {status: 404}          -> "not-found"
        | {status: s} when s >= 500 -> "server-error"
        | _                      -> "other"
      ]
      """
    When I call check-status with {status: 404 body: ""}
    Then the result is "not-found"
    When I call check-status with {status: 503 body: "unavailable"}
    Then the result is "server-error"


  # ===========================================================================
  # COMMENTS: Open Questions, Clojure Mapping, Corner Cases
  # ===========================================================================

  # ---------------------------------------------------------------------------
  # OPEN QUESTIONS
  # ---------------------------------------------------------------------------
  #
  # Q1: OR patterns -- `| "book" or "magazine" -> "reading"`
  #     Should DataTwist support OR patterns inside a single branch?
  #     Alternative: just use two branches with the same result.
  #     Pro: concise when many literals map to same result.
  #     Con: adds grammar complexity; interaction with structural patterns
  #     is non-obvious (e.g., `| {a: 1} or {b: 2} -> ...`).
  #     RECOMMENDATION: Defer. Two branches with same result is clear enough
  #     for v1. Revisit if users request it.
  #
  # Q2: Exhaustiveness enforcement -- warning vs hard error?
  #     Current design: emit a warning if no `| _` branch, return nil.
  #     Alternative: make it a hard compile error.
  #     Pro of warning: allows quick prototyping.
  #     Pro of error: catches bugs; Elm/Rust style.
  #     RECOMMENDATION: Warning in v1, optional strict mode later (e.g.,
  #     a compiler flag or `#![exhaustive]` annotation).
  #
  # Q3: Pipe directly into guard -- `data |> | cond -> result`
  #     Is `|>` followed by `|` ambiguous to the parser?
  #     `|>` is a two-character token, `|` is one character.
  #     The lexer must distinguish them.
  #     RECOMMENDATION: Do NOT allow `data |> | ...`. Require binding first:
  #       val is data |> transform
  #       result is | val > 5 -> "high" | _ -> "low"
  #     This avoids the `|>` / `|` adjacency problem entirely.
  #
  # Q4: Empty object pattern `| {}` semantics
  #     Option A: matches ANY object (map?-style check).
  #     Option B: matches only literally empty objects.
  #     Clojure core.match treats `{}` as "any map".
  #     RECOMMENDATION: Option A (matches any object). To match only
  #     empty: `| {} when count data = 0 -> "empty"`.
  #     This is consistent with structural matching being a subset check:
  #     "does the value have at least these fields?" with zero fields = any map.
  #
  # Q5: Type checking patterns -- `| (number?) -> "is a number"`
  #     Should patterns support predicate-based type tests?
  #     Alternative: use when clause: `| x when number? x -> ...`
  #     or guard form: `| number? data -> "is number"`.
  #     RECOMMENDATION: Rely on `when` + predicates for v1. No special
  #     syntax for type tests inside patterns.
  #
  # Q6: Context disambiguation depth
  #     Guards: `| expr -> result` where expr is a boolean expression.
  #     Structural: `| {..} -> result`, `| [..] -> result`, `| literal -> result`.
  #     What about `| (expr) -> ...`? Parenthesized expression = guard?
  #     RECOMMENDATION: Yes, `| (expr) -> result` is a guard (the parens are
  #     just grouping). Structural patterns are unambiguously introduced by
  #     `{`, `[`, a literal (number, string, boolean, nil), or `_`.
  #
  # Q7: Can the result (right side of `->`) be a guard block itself?
  #     i.e., nested guards. Example:
  #       | {type: "book"} -> | pages > 500 -> "epic" | _ -> "book"
  #     RECOMMENDATION: No. Nested guards are a parse error. Use `when` or
  #     a helper function instead. This keeps the grammar unambiguous.
  #
  # Q8: Shadowing of `_` inside patterns
  #     `_` means "catch-all" at the branch level, but inside a structural
  #     pattern `{name: _}` means "field exists, don't bind it."
  #     This overloading is consistent with Clojure/Elixir convention.
  #     No ambiguity because the branch-level `_` appears alone: `| _ -> ...`
  #     while the pattern-level `_` appears inside `{}` or `[]`.
  #
  # ---------------------------------------------------------------------------
  # CLOJURE COMPILATION MAPPING
  # ---------------------------------------------------------------------------
  #
  # Guard expressions -> cond
  #   tier is
  #     | amount > 1000 -> "gold"
  #     | _ -> "bronze"
  #   =>
  #   (def tier
  #     (cond
  #       (> amount 1000) "gold"
  #       :else "bronze"))
  #
  # Structural matching -> clojure.core.match/match
  #   classify is [data ->
  #     | {type: "book" pages: p} when p > 500 -> "epic"
  #     | {type: "book"} -> "book"
  #     | _ -> "other"
  #   ]
  #   =>
  #   (defn classify [data]
  #     (match data
  #       ({:type "book" :pages p} :guard [(> p 500)]) "epic"
  #       {:type "book"} "book"
  #       :else "other"))
  #
  # List structural matching -> core.match vector patterns
  #   | [x & rest] -> "collection"
  #   =>
  #   ([x & rest] :seq) "collection"    ; or use (match (vec data) ...)
  #
  # Literal patterns -> core.match literal matching
  #   | 42 -> "answer"
  #   =>
  #   42 "answer"
  #
  # when clause -> :guard in core.match
  #   | {name: n age: a} when a > 18 -> "adult"
  #   =>
  #   ({:name n :age a} :guard [(> a 18)]) "adult"
  #
  # Guards in object fields -> inline cond
  #   {tier: | _.spending > 1000 -> "gold" | _ -> "bronze"}
  #   =>
  #   {:tier (cond (> (:spending _) 1000) "gold" :else "bronze")}
  #   (where _ is the current pipeline element)
  #
  # Variable binding in patterns:
  #   Unquoted identifiers in pattern value positions become bound variables.
  #   | {name: n} -> ...  ;; n is bound to (:name data)
  #   Literal values in patterns must match exactly.
  #   | {status: 200} -> ...  ;; (:status data) must equal 200
  #
  # nil pattern -> (nil? data) check or core.match nil literal
  #   | nil -> "nothing"
  #   =>
  #   nil "nothing"
  #
  # ---------------------------------------------------------------------------
  # CORNER CASES
  # ---------------------------------------------------------------------------
  #
  # C1: Guard branch with side effect in condition
  #     | log! data "checking" -> "ok"
  #     Side-effect functions return their first arg (passthrough), so this
  #     evaluates the side effect and then tests truthiness. This is valid
  #     but potentially confusing. Compiler may warn.
  #
  # C2: Pattern matching on nested wildcards in pipeline map
  #     users |> map {tier: | _.address.country = "US" -> "domestic" | _ -> "intl"}
  #     The `_` inside the guard refers to the pipeline element (the user),
  #     while `_` as default branch means catch-all. Context disambiguates:
  #     `_.field` = wildcard access, bare `_` after `|` = catch-all.
  #
  # C3: Guard where condition is always true
  #     | true -> "always"
  #     | _ -> "never"
  #     This is valid. The `true` literal as a guard condition is always truthy.
  #     As a LITERAL pattern (structural context), `| true -> ...` matches the
  #     boolean value true. Disambiguation: in function body context with a
  #     match target, `true` is a literal pattern. In standalone guard (after
  #     `is`), `true` is a boolean expression. Parser uses context.
  #
  # C4: Ambiguity between literal pattern and guard expression
  #     Inside a function: `[x -> | 42 -> "answer" | _ -> "other"]`
  #     Is `42` a literal pattern (match x against 42) or a guard (42 is truthy)?
  #     RESOLUTION: In function body patterns, a bare literal after `|` is
  #     always a literal pattern match. Guard expressions require a comparison
  #     operator or predicate call. `| 42 -> ...` = literal match.
  #     `| x > 42 -> ...` = guard.
  #
  # C5: Structural pattern with computed key
  #     | {(get-key): value} -> ...
  #     NOT SUPPORTED in v1. Pattern keys must be static identifiers.
  #
  # C6: Pattern matching with destructuring `as` keyword
  #     | {name: n age: a} as person when a > 18 -> person
  #     Binds the whole matched value to `person`. This mirrors the
  #     destructuring `as` from Feature Area 5. Should work if core.match
  #     supports :as in map patterns (it does: {:keys [...] :as whole}).
  #     RECOMMENDATION: Support in v1 -- natural extension of destructuring.
  #
  # C7: Multiple when conditions
  #     | {x: a y: b} when a > 0 when b > 0 -> "positive"
  #     RECOMMENDATION: Only one `when` clause per branch. Use `and` for
  #     multiple conditions: `when a > 0 and b > 0`.
  #
  # C8: Guard branch result spanning multiple expressions
  #     | x > 5 -> log! x "big" ; x * 2
  #     The result of a branch is a single expression. If multi-expression
  #     results are needed, use a do-block or helper function. For v1,
  #     the result is one expression only.
  #
  # C9: Pattern match on keyword/symbol values (Clojure interop)
  #     Clojure keywords (:foo) do not exist in DataTwist surface syntax.
  #     Object keys become keywords internally, but pattern values are
  #     strings, numbers, booleans, or nil. No keyword literal patterns.
  #
  # C10: Performance -- many branches
  #      Guards compile to cond (linear scan). Structural matches compile
  #      to core.match (optimized decision tree). Mixed forms may need
  #      both. For large guard blocks (>20 branches), consider emitting
  #      a lookup map instead of cond. Optimization for later.
