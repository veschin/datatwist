Feature: String Pattern Destructuring (#p)
  The #p"..." reader macro enables reverse-format string matching.
  Captures become object fields for natural pipeline processing.
  Patterns are first-class values: they can be bound with `is`,
  passed to functions, and used in guard expressions.

  Three tiers of capture syntax:
    Tier 1 — Simple: {var} captures up to the next literal (non-greedy)
    Tier 2 — Type hints: {var:d} digits, {var:w} word chars, {var:Nd} exactly N digits
    Tier 3 — Full constraints: {var: many digit}, {var: 'http' maybe 's'}, etc.

  # ---------------------------------------------------------------------------
  # SECTION 1: Tier 1 — Simple captures
  # ---------------------------------------------------------------------------

  Scenario: Email pattern extracts user and domain
    Given the DataTwist source:
      """
      "alice@example.com" | #p"{user}@{domain}" -> {user domain}
      """
    When it is evaluated
    Then the result is {user: "alice" domain: "example.com"}

  Scenario: IPv4 pattern extracts four octets
    Given the DataTwist source:
      """
      "192.168.1.42" | #p"{a}.{b}.{c}.{d}" -> {a b c d}
      """
    When it is evaluated
    Then the result is {a: "192" b: "168" c: "1" d: "42"}

  Scenario: Log pattern with complex literal separators
    Given the DataTwist source:
      """
      line is "GET /index.html HTTP/1.1"
      line | #p"{method} {url} HTTP/{ver}" -> {method url ver}
      """
    When it is evaluated
    Then the result is {method: "GET" url: "/index.html" ver: "1.1"}

  Scenario: Named pattern bound with is
    Given the DataTwist source:
      """
      email-pat is #p"{user}@{domain}"
      "bob@test.org" | email-pat -> {user domain}
      """
    When it is evaluated
    Then the result is {user: "bob" domain: "test.org"}

  Scenario: Single capture consumes entire string when no surrounding literals
    Given the DataTwist source:
      """
      "hello world" | #p"{msg}" -> msg
      """
    When it is evaluated
    Then the result is "hello world"

  Scenario: Empty capture when literal matches but no chars between
    Given the DataTwist source:
      """
      "user@" | #p"{user}@{domain}" -> {user domain}
      """
    When it is evaluated
    Then the result is {user: "user" domain: ""}

  Scenario: Non-greedy default — first separator wins on repeated delimiter
    Given the DataTwist source:
      """
      "x-y-z" | #p"{a}-{b}" -> {a b}
      """
    When it is evaluated
    Then the result is {a: "x" b: "y-z"}

  Scenario: Pattern does not match returns nil in guard fall-through
    Given the DataTwist source:
      """
      "hello"
        | #p"{a}@{b}" -> "email"
        | _           -> "other"
      """
    When it is evaluated
    Then the result is "other"

  Scenario: Unmatched leading literal causes no-match
    Given the DataTwist source:
      """
      "Goodbye World" | #p"Hello {who}" -> who
                      | _               -> nil
      """
    When it is evaluated
    Then the result is nil

  # ---------------------------------------------------------------------------
  # SECTION 2: Tier 2 — Type hint shorthand
  # ---------------------------------------------------------------------------

  Scenario: ISO date with 4d-2d-2d type hints
    Given the DataTwist source:
      """
      "2024-01-15" | #p"{y:4d}-{m:2d}-{d:2d}" -> {y m d}
      """
    When it is evaluated
    Then the result is {y: "2024" m: "01" d: "15"}

  Scenario: Type hint :d enforces digits-only octets
    Given the DataTwist source:
      """
      "10.0.0.1" | #p"{a:d}.{b:d}.{c:d}.{d:d}" -> {a b c d}
      """
    When it is evaluated
    Then the result is {a: "10" b: "0" c: "0" d: "1"}

  Scenario: Type hint :d rejects non-digit content
    Given the DataTwist source:
      """
      "abc.def.ghi.jkl" | #p"{a:d}.{b:d}.{c:d}.{d:d}" -> "matched"
                         | _                            -> "no match"
      """
    When it is evaluated
    Then the result is "no match"

  Scenario: Exact-length type hint :N captures fixed char count
    Given the DataTwist source:
      """
      "ABC-remainder" | #p"{code:3}-{rest}" -> {code rest}
      """
    When it is evaluated
    Then the result is {code: "ABC" rest: "remainder"}

  Scenario: Exact-length type hint :Nd captures fixed digit count
    Given the DataTwist source:
      """
      "2024-01-15" | #p"{y:4d}-{m:2d}-{d:2d}" -> {y: to-int y m: to-int m d: to-int d}
      """
    When it is evaluated
    Then the result is {y: 2024 m: 1 d: 15}

  Scenario: Type hint :w captures word characters only
    Given the DataTwist source:
      """
      "hello world" | #p"{word:w} {rest}" -> {word rest}
      """
    When it is evaluated
    Then the result is {word: "hello" rest: "world"}

  Scenario: Type hint :d does not match letters
    Given the DataTwist source:
      """
      "ab-12" | #p"{x:2d}-{y}" -> "matched"
               | _              -> "no match"
      """
    When it is evaluated
    Then the result is "no match"

  # ---------------------------------------------------------------------------
  # SECTION 3: Tier 3 — Full constraint syntax
  # ---------------------------------------------------------------------------

  Scenario: many constraint matches one or more characters
    Given the DataTwist source:
      """
      "abc123" | #p"{letters: many letter}{digits: many digit}" -> {letters digits}
      """
    When it is evaluated
    Then the result is {letters: "abc" digits: "123"}

  Scenario: maybe constraint matches optional suffix
    Given the DataTwist source:
      """
      "https" | #p"{proto: 'http' maybe 's'}" -> proto
      """
    When it is evaluated
    Then the result is "https"

  Scenario: maybe constraint allows absent optional part
    Given the DataTwist source:
      """
      "http" | #p"{proto: 'http' maybe 's'}" -> proto
      """
    When it is evaluated
    Then the result is "http"

  Scenario: not constraint excludes a character class
    Given the DataTwist source:
      """
      "foo/bar" | #p"{host: many (not '/')}/{path: rest}" -> {host path}
      """
    When it is evaluated
    Then the result is {host: "foo" path: "bar"}

  Scenario: URL pattern with not and maybe constraints
    Given the DataTwist source:
      """
      "https://example.com/index.html"
        | #p"{proto: 'http' maybe 's'}://{host: many (not '/')}/{path: rest}" -> {proto host path}
      """
    When it is evaluated
    Then the result is {proto: "https" host: "example.com" path: "index.html"}

  Scenario: Alternation constraint matches one of several literals
    Given the DataTwist source:
      """
      "photo.jpg" | #p"{name: many (not '.')}.{ext: 'jpg' | 'png' | 'gif'}" -> {name ext}
      """
    When it is evaluated
    Then the result is {name: "photo" ext: "jpg"}

  Scenario: Alternation constraint rejects non-matching literal
    Given the DataTwist source:
      """
      "photo.bmp" | #p"{name: many (not '.')}.{ext: 'jpg' | 'png' | 'gif'}" -> "matched"
                  | _                                                         -> "no match"
      """
    When it is evaluated
    Then the result is "no match"

  Scenario: rest constraint captures everything remaining
    Given the DataTwist source:
      """
      "/foo/bar/baz" | #p"/{path: rest}" -> path
      """
    When it is evaluated
    Then the result is "foo/bar/baz"

  Scenario: Exact count constraint with digit
    Given the DataTwist source:
      """
      "2024-01" | #p"{y: 4 digit}-{m: 2 digit}" -> {y m}
      """
    When it is evaluated
    Then the result is {y: "2024" m: "01"}

  Scenario: many digit used for IP-style parsing
    Given the DataTwist source:
      """
      "192.168.1.42"
        | #p"{a: many digit}.{b: many digit}.{c: many digit}.{d: many digit}" -> {a b c d}
      """
    When it is evaluated
    Then the result is {a: "192" b: "168" c: "1" d: "42"}

  Scenario: Grouped alternation with parentheses
    Given the DataTwist source:
      """
      "ftp://files.example.com"
        | #p"{proto: ('http' maybe 's') | 'ftp'}://{host: rest}" -> {proto host}
      """
    When it is evaluated
    Then the result is {proto: "ftp" host: "files.example.com"}

  # ---------------------------------------------------------------------------
  # SECTION 4: Brace escaping
  # ---------------------------------------------------------------------------

  Scenario: Double-brace escape produces literal opening brace
    Given the DataTwist source:
      """
      "{key}: hello" | #p"{{key}}: {value}" -> value
      """
    When it is evaluated
    Then the result is "hello"

  Scenario: Double-brace escape in pattern with multiple captures
    Given the DataTwist source:
      """
      kv-pat is #p"{{key}}: {value}"
      "{key}: world" | kv-pat -> value
      """
    When it is evaluated
    Then the result is "world"

  Scenario: Closing double-brace produces literal closing brace
    Given the DataTwist source:
      """
      "result {42}" | #p"result {{{n}}}" -> n
      """
    When it is evaluated
    Then the result is "42"

  # ---------------------------------------------------------------------------
  # SECTION 5: Guard integration
  # ---------------------------------------------------------------------------

  Scenario: Pattern guard matches first applicable branch
    Given the DataTwist source:
      """
      classify is [input ->
        input
          | #p"{name}@{domain}" -> {type: "email" name domain}
          | #p"{proto}://{host}" -> {type: "url" proto host}
          | _                    -> {type: "unknown" value: input}
      ]
      classify "alice@example.com"
      """
    When it is evaluated
    Then the result is {type: "email" name: "alice" domain: "example.com"}

  Scenario: Pattern guard falls through to next arm on no-match
    Given the DataTwist source:
      """
      classify is [input ->
        input
          | #p"{name}@{domain}" -> "email"
          | #p"{proto}://{host}" -> "url"
          | _                    -> "unknown"
      ]
      classify "just some text"
      """
    When it is evaluated
    Then the result is "unknown"

  Scenario: Pattern guard selects url branch for URL-shaped input
    Given the DataTwist source:
      """
      classify is [input ->
        input
          | #p"{name}@{domain}" -> "email"
          | #p"{proto}://{host}" -> "url"
          | _                    -> "unknown"
      ]
      classify "https://example.com"
      """
    When it is evaluated
    Then the result is "url"

  Scenario: Multiple pattern guards in sequence — each tested independently
    Given the DataTwist source:
      """
      results is [
        "alice@example.com" | #p"{u}@{d}" -> "email"
                            | _           -> "other"
        "https://example.com" | #p"{u}@{d}" -> "email"
                              | _           -> "other"
      ]
      results
      """
    When it is evaluated
    Then the result is ["email" "other"]

  Scenario: Pattern guard with when clause for additional condition
    Given the DataTwist source:
      """
      "alice@example.com"
        | #p"{user}@{domain}" when domain = "example.com" -> "internal"
        | #p"{user}@{domain}"                             -> "external"
        | _                                               -> "unknown"
      """
    When it is evaluated
    Then the result is "internal"

  # ---------------------------------------------------------------------------
  # SECTION 6: Named patterns as first-class values
  # ---------------------------------------------------------------------------

  Scenario: Pattern bound with is is a first-class value
    Given the DataTwist source:
      """
      date-fmt is #p"{y:4d}-{m:2d}-{d:2d}"
      "2024-03-22" | date-fmt -> {y m d}
      """
    When it is evaluated
    Then the result is {y: "2024" m: "03" d: "22"}

  Scenario: Named pattern reused across multiple match sites
    Given the DataTwist source:
      """
      ip-pat is #p"{a}.{b}.{c}.{d}"
      r1 is "10.0.0.1" | ip-pat -> a
      r2 is "192.168.1.1" | ip-pat -> a
      {r1 r2}
      """
    When it is evaluated
    Then the result is {r1: "10" r2: "192"}

  Scenario: Pattern passed as function argument
    Given the DataTwist source:
      """
      parse-field is [text pat ->
        text | pat -> text
             | _   -> nil
      ]
      email-pat is #p"{user}@{domain}"
      parse-field "alice@example.com" email-pat
      """
    When it is evaluated
    Then the result is "alice@example.com"

  # ---------------------------------------------------------------------------
  # SECTION 7: Pipeline integration
  # ---------------------------------------------------------------------------

  Scenario: extract function applies pattern and returns object or nil
    Given the DataTwist source:
      """
      date-fmt is #p"{y:4d}-{m:2d}-{d:2d}"
      extract "2024-01-15" date-fmt
      """
    When it is evaluated
    Then the result is {y: "2024" m: "01" d: "15"}

  Scenario: extract returns nil when pattern does not match
    Given the DataTwist source:
      """
      date-fmt is #p"{y:4d}-{m:2d}-{d:2d}"
      extract "not-a-date" date-fmt
      """
    When it is evaluated
    Then the result is nil

  Scenario: Pipeline map with extract over list of strings
    Given the DataTwist source:
      """
      date-fmt is #p"{y:4d}-{m:2d}-{d:2d}"
      dates is ["2024-01-15" "2024-02-28" "2024-12-01"]
      dates |> map (extract _ date-fmt) |> map _.y
      """
    When it is evaluated
    Then the result is ["2024" "2024" "2024"]

  Scenario: Pipeline filter with match? keeps only matching strings
    Given the DataTwist source:
      """
      email-pat is #p"{user}@{domain}"
      inputs is ["alice@example.com" "not-an-email" "bob@test.org"]
      inputs |> filter (match? _ email-pat)
      """
    When it is evaluated
    Then the result is ["alice@example.com" "bob@test.org"]

  Scenario: Pipeline combining extract and filter to drop non-matches
    Given the DataTwist source:
      """
      date-fmt is #p"{y:4d}-{m:2d}-{d:2d}"
      lines is ["2024-01-15" "bad-data" "2024-03-22"]
      lines |> map (extract _ date-fmt) |> filter (_ != nil)
      """
    When it is evaluated
    Then the result is [{y: "2024" m: "01" d: "15"} {y: "2024" m: "03" d: "22"}]

  Scenario: Pipeline filtering extracted month field
    Given the DataTwist source:
      """
      date-fmt is #p"{y:4d}-{m:2d}-{d:2d}"
      dates is ["2024-01-15" "2024-02-28" "2024-01-20"]
      dates |> map (extract _ date-fmt) |> filter _.m = "01" |> map _.d
      """
    When it is evaluated
    Then the result is ["15" "20"]

  # ---------------------------------------------------------------------------
  # SECTION 8: Compile-time errors
  # ---------------------------------------------------------------------------

  Scenario: Adjacent unconstrained captures are a compile-time error
    Given the DataTwist source:
      """
      #p"{a}{b}"
      """
    When it is evaluated
    Then it should raise a compile error mentioning "adjacent captures"

  Scenario: Constrained adjacent captures are valid
    Given the DataTwist source:
      """
      "ABC123" | #p"{code: 3 any}{rest}" -> {code rest}
      """
    When it is evaluated
    Then the result is {code: "ABC" rest: "123"}

  Scenario: rest in non-final position is a compile-time error
    Given the DataTwist source:
      """
      #p"{head: rest}-{tail}"
      """
    When it is evaluated
    Then it should raise a compile error mentioning "rest"

  Scenario: Nested quantifiers are a compile-time error
    Given the DataTwist source:
      """
      #p"{x: many (many digit)}"
      """
    When it is evaluated
    Then it should raise a compile error mentioning "nested quantifier"

  # ---------------------------------------------------------------------------
  # SECTION 9: Edge cases and failure modes
  # ---------------------------------------------------------------------------

  Scenario: Empty capture is valid — matched text happens to be empty
    Given the DataTwist source:
      """
      "-" | #p"{a}-{b}" -> {a b}
      """
    When it is evaluated
    Then the result is {a: "" b: ""}

  Scenario: Pattern applied to empty string can match
    Given the DataTwist source:
      """
      "" | #p"{a}" -> a
      """
    When it is evaluated
    Then the result is ""

  Scenario: Pattern is full-match anchored — trailing chars cause no-match
    Given the DataTwist source:
      """
      "Hello World extra" | #p"Hello {who: many letter}" -> who
                          | _                            -> "no match"
      """
    When it is evaluated
    Then the result is "no match"

  Scenario: Non-string input falls through in guard — no error
    Given the DataTwist source:
      """
      42 | #p"{x}" -> "matched"
         | _       -> "not a string"
      """
    When it is evaluated
    Then the result is "not a string"

  Scenario: extract returns nil for non-string input
    Given the DataTwist source:
      """
      extract 42 #p"{x}"
      """
    When it is evaluated
    Then the result is nil

  Scenario: Unsatisfiable constraint — too few chars — returns no-match
    Given the DataTwist source:
      """
      "ab" | #p"{x: 5 digit}" -> "matched"
            | _               -> "no match"
      """
    When it is evaluated
    Then the result is "no match"

  Scenario: All captured values are strings — explicit conversion needed for arithmetic
    Given the DataTwist source:
      """
      "2024-01-15" | #p"{y:4d}-{m:2d}-{d:2d}" -> to-int y + to-int m + to-int d
      """
    When it is evaluated
    Then the result is 2040

  Scenario: Wildcard capture {_} matches but does not bind
    Given the DataTwist source:
      """
      inputs is ["alice@example.com" "bob@test.org" "not-an-email"]
      inputs |> filter (match? _ #p"{_}@{_}")
      """
    When it is evaluated
    Then the result is ["alice@example.com" "bob@test.org"]

  Scenario: Pattern destructuring via is binds all captures in scope
    Given the DataTwist source:
      """
      #p"{y:4d}-{m:2d}-{d:2d}" is "2024-03-22"
      {y m d}
      """
    When it is evaluated
    Then the result is {y: "2024" m: "03" d: "22"}

  Scenario: Pattern destructuring via is throws on no-match
    Given the DataTwist source:
      """
      #p"{y:4d}-{m:2d}-{d:2d}" is "not-a-date"
      """
    When it is evaluated
    Then it should raise a runtime error mentioning "pattern does not match"

  # ---------------------------------------------------------------------------
  # SECTION 10: Nginx log example (integration smoke test)
  # ---------------------------------------------------------------------------

  Scenario: Nginx-style log pattern extracts all fields
    Given the DataTwist source:
      """
      log-pat is #p"{ip} - {user} [{time}] \"{method} {url} HTTP/{ver}\" {status} {bytes}"
      line is "127.0.0.1 - alice [10/Jan/2024:13:55:36 +0000] \"GET /index.html HTTP/1.1\" 200 1024"
      line | log-pat -> {ip method url status}
      """
    When it is evaluated
    Then the result is {ip: "127.0.0.1" method: "GET" url: "/index.html" status: "200"}
