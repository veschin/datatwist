# DataTwist Language Core

DataTwist is a functional language designed for data processing with three core principles:
- **Simple data access**: Intuitive syntax for accessing nested data structures
- **Simple aggregations**: Built-in operations for common data transformations
- **Simple composition**: Pipeline-based approach for chaining operations

The language uses a pipeline metaphor where each operation passes its result as the first argument to the next operation.

## Core Syntax

Variables: `name = "value"`
Data: `user = {id: 1 name: "Alice" age: 28}`  
Lists: `numbers = [1 2 3 4 5]`
Functions: `add = [a b -> a + b]`  
Anonymous: `[x -> x + 1]`
Pipeline: 
```
test-data = data
  filter condition
  map transform
```
Grouping: `add (multiply 5 2) 10`
Let: `let {x = 1} in x + 1`
If: `if condition then expr1 else expr2`

## Examples

### Basic
```
user = {id: 1 name: "Alice" age: 28}
numbers = [1 2 3 4 5]

add = a b -> a + b
double = [x -> x * 2]

users
  filter [user -> user.age > 18]
  map [user -> {user.name user.age}]
```

### Pipeline
```
# Basic
numbers filter even? map double take 5

# Nested
users
  filter age > 18
  map [user -> {
    name: user.name
    scores: filter user.scores even?
  }]

# Control through anonymous functions
data
  map [x -> (process x) (validate x)]
  filter [x -> (check-quality x) threshold]
```

### Wildcard in Functions
```
# Use _ for direct field access
users filter _.age > 18
employees filter _.salary > 50000
products filter _.price < 100

# In nested operations
users
  filter _.age > 18
  map [user -> {
    name:      user.name
    scores:    filter user.scores even?
    avg-score: average (filter user.scores even?)
  }]

# Complex with multiple wildcards
sales
  filter _.amount > 0
  group-by _.region
  map [group -> {
    region: group.region
    total:  sum group.amount
    count:  count group
  }]
```

### Predicates
```
even? = n -> n % 2 = 0
empty? = collection -> count collection = 0
contains? = s substring -> includes s substring

numbers filter even?
strings filter [starts-with? "prefix"]
```

### Pattern Matching
```
classify-user = user ->
  | {age: a} when a < 18 -> "minor"
  | {status: "vip"} -> "premium"
  | _ -> "regular"

users
  map [user ->
    | {age: a} when a < 18 -> {user.name category: "minor"}
    | otherwise -> {user.name category: "adult"}
  ]
```

### Try-Catch
```
data = try
  read-csv "data.csv"
catch error -> "Failed to read file"

result = try
  load-from-api endpoint
catch HttpError status -> "HTTP error: {status}"
```

### Side Effects (!)
```
data
  clean
  log! _ "Data cleaned"
  save! _ "result.json"
  notify! _ "Processing complete"
```

### String Formatting
```
format "Hello %s" "World"
format "Total: $%.2f (%d items)" 123.45 3
format "%d%% complete" 75

users
  log! _ (format "Found %d active users" (count users))
  notify! _ (format "Report generated at %s" (current-time))
```


    | {age: a} when a < 18 -> {user.name category: "minor"}
    | {salary: s} when s > 100000 -> {user.name category: "high-earner"}
    | otherwise -> {user.name category: "regular"}
  ]

# Complex pattern matching
process-result = result ->
  | {status: "success" data: d} -> "Success: {count d} items"
  | {status: "error" message: m} -> "Error: {m}"
  | {status: "pending"} -> "Processing..."
  | otherwise -> "Unknown status"
```
