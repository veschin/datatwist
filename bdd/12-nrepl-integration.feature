Feature: nREPL Integration
  DataTwist provides an nREPL server via the datatwist-nrepl middleware. Any
  nREPL-compatible client (CIDER, Calva, Conjure, nREPL CLI) can connect and
  evaluate DataTwist expressions interactively.

  The middleware follows the Piggieback pattern: it intercepts nREPL eval messages
  and routes them through the DataTwist parser and evaluator (same JVM process),
  returning results in the standard nREPL response format.

  Key design constraints:
    - Session environments persist bindings across evaluations within a session
    - Each new session starts with the stdlib default environment
    - Sessions are isolated: bindings in session A are not visible in session B
    - The middleware only intercepts eval messages for DataTwist sessions;
      all other messages fall through to the default nREPL handler chain
    - Errors during evaluation return an :err response, not an exception on the wire

  # ===========================================================================
  # SECTION 1: Connection
  # ===========================================================================

  Scenario: Start an nREPL server with DataTwist middleware
    Given a deps.edn project with the datatwist-nrepl dependency
    When the user runs "clj -M:nrepl"
    Then an nREPL server starts on a local port
    And the server writes the port number to ".nrepl-port"
    And the server accepts nREPL client connections

  Scenario: Connect a client and receive a welcome response
    Given an nREPL server is running with DataTwist middleware
    When an nREPL client connects
    Then the client receives a response with :versions containing "nrepl" and "datatwist"
    And the response includes the DataTwist middleware version

  Scenario: Multiple clients can connect to the same server simultaneously
    Given an nREPL server is running
    When two nREPL clients connect at the same time
    Then both clients receive independent welcome responses
    And each client can evaluate expressions without interference

  # ===========================================================================
  # SECTION 2: Evaluation
  # ===========================================================================

  Scenario: Evaluate a simple integer expression
    Given an nREPL session is open
    When the client sends eval op with code "42"
    Then the response contains :value "42"
    And the response contains :status "done"

  Scenario: Evaluate a string expression
    Given an nREPL session is open
    When the client sends eval op with code "\"hello world\""
    Then the response contains :value "\"hello world\""

  Scenario: Evaluate an arithmetic expression
    Given an nREPL session is open
    When the client sends eval op with code "3 + 4 * 2"
    Then the response contains :value "11"

  Scenario: Evaluate a binding and return the bound value
    Given an nREPL session is open
    When the client sends eval op with code "name is \"Alice\""
    Then the response contains :value "\"Alice\""

  Scenario: Evaluate a pipeline expression
    Given an nREPL session is open
    When the client sends eval op with code "[1 2 3 4 5] |> filter [x -> x > 3]"
    Then the response contains :value "[4 5]"

  Scenario: Evaluate a function definition and return a function description
    Given an nREPL session is open
    When the client sends eval op with code "double is [x -> x * 2]"
    Then the response :value indicates a function was defined
    And the response does not contain an error

  Scenario: Apply a function immediately after defining it
    Given an nREPL session is open
    When the client sends eval op with code "add is [a b -> a + b]\nadd 3 4"
    Then the response contains :value "7"

  Scenario: Evaluate a multi-line program, return the last value
    Given an nREPL session is open
    When the client sends eval op with code:
      """
      a is 10
      b is 20
      a + b
      """
    Then the response contains :value "30"

  # ===========================================================================
  # SECTION 3: Session Persistence
  # ===========================================================================

  Scenario: A binding defined in one eval is accessible in the next eval
    Given an nREPL session is open
    When the client sends eval op with code "name is \"Alice\""
    And the client sends eval op with code "name"
    Then the second response contains :value "\"Alice\""

  Scenario: A function defined in one eval is callable in a later eval
    Given an nREPL session is open
    When the client sends eval op with code "double is [x -> x * 2]"
    And the client sends eval op with code "double 21"
    Then the second response contains :value "42"

  Scenario: Bindings in one session do not leak into another session
    Given two separate nREPL sessions are open: session-A and session-B
    When session-A evaluates "secret is 42"
    And session-B evaluates "secret"
    Then session-B receives a :err response indicating "secret" is undefined

  Scenario: A new binding in a session shadows a previously defined one
    Given an nREPL session is open
    When the client sends eval op with code "x is 1"
    And the client sends eval op with code "x is 2"
    And the client sends eval op with code "x"
    Then the third response contains :value "2"

  Scenario: Closing and reopening a session starts with a fresh environment
    Given a session was previously used and had "name is \"Alice\"" evaluated
    When the session is closed and a new session is opened
    And the client sends eval op with code "name"
    Then the response is a :err response indicating "name" is undefined

  # ===========================================================================
  # SECTION 4: Completion
  # ===========================================================================

  Scenario: Completion returns stdlib function names
    Given an nREPL session is open
    When the client sends complete op with prefix "fi"
    Then the response candidates include "filter"
    And the response candidates include "find"

  Scenario: Completion returns names bound in the current session
    Given an nREPL session is open
    And the client has evaluated "total-price is 99.99"
    When the client sends complete op with prefix "total"
    Then the response candidates include "total-price"

  Scenario: Completion returns an empty list for a prefix with no matches
    Given an nREPL session is open
    When the client sends complete op with prefix "zzz"
    Then the response candidates list is empty

  Scenario: Completion results include the candidate type
    Given an nREPL session is open
    When the client sends complete op with prefix "map"
    Then the candidate "map" has type "function"

  # ===========================================================================
  # SECTION 5: Inspect
  # ===========================================================================

  Scenario: inspect-start on a map renders its keys and values
    Given an nREPL session is open
    When the client sends inspect-start op with code "{name: \"Alice\" age: 25}"
    Then the response contains a rendered representation including "name:"
    And the response contains a rendered representation including "\"Alice\""

  Scenario: inspect-push drills into a nested structure
    Given an nREPL session is open
    And inspect-start was called with "{profile: {city: \"Moscow\"}}"
    When the client sends inspect-push op with idx for the "profile:" entry
    Then the response shows the nested object {city: "Moscow"}
    And the response contains "city:" and "\"Moscow\""

  Scenario: inspect-pop returns to the parent after drilling in
    Given an nREPL session is open
    And inspect-start was called with "{profile: {city: \"Moscow\"}}"
    And inspect-push was called to drill into "profile:"
    When the client sends inspect-pop op
    Then the response shows the original top-level object
    And the response contains "profile:"

  Scenario: inspect-start on a list shows indexed entries
    Given an nREPL session is open
    When the client sends inspect-start op with code "[\"Alice\" \"Bob\" \"Carol\"]"
    Then the response shows "0:" followed by "\"Alice\""
    And the response shows "1:" followed by "\"Bob\""

  Scenario: Object keys in the inspector use DataTwist postfix colon syntax
    Given an nREPL session is open
    When the client sends inspect-start op with code "{name: \"Alice\"}"
    Then the rendered output shows "name:" not ":name"

  # ===========================================================================
  # SECTION 6: Load File
  # ===========================================================================

  Scenario: load-file evaluates all expressions in a .dt file
    Given an nREPL session is open
    And a file "data.dt" contains:
      """
      users is [{name: "Alice"} {name: "Bob"}]
      count users
      """
    When the client sends load-file op with the path to "data.dt"
    Then the response contains :value "2"

  Scenario: Bindings defined in a loaded file are available in the session
    Given an nREPL session is open
    And a file "defs.dt" contains "base-rate is 0.05"
    When the client sends load-file op with the path to "defs.dt"
    And the client sends eval op with code "base-rate"
    Then the response contains :value "0.05"

  Scenario: load-file reports parse errors in the file with line numbers
    Given an nREPL session is open
    And a file "broken.dt" contains "x = 10" on line 1
    When the client sends load-file op with the path to "broken.dt"
    Then the response contains :err indicating a parse error
    And the error message includes "line 1"

  # ===========================================================================
  # SECTION 7: Error Handling
  # ===========================================================================

  Scenario: A parse error in eval returns an :err response
    Given an nREPL session is open
    When the client sends eval op with code "x = 42"
    Then the response contains :err with a parse error message
    And the response does not contain :value
    And the session remains open for further evaluation

  Scenario: A runtime error in eval returns an :err response
    Given an nREPL session is open
    When the client sends eval op with code "[x -> x + 1] \"not-a-number\""
    Then the response contains :err with a runtime error message
    And the session environment is unchanged from before the failing eval

  Scenario: An error in one eval does not affect subsequent evals
    Given an nREPL session is open
    And the client has evaluated "x is 5"
    When the client sends eval op with code "bad parse ==="
    And the client sends eval op with code "x"
    Then the final response contains :value "5"
