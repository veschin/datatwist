# DataFlow Language Core

Minimal functional language. Pipeline passes result as first argument.

## Core Syntax

Variables: `name = "value"`
Data: `user = {id: 1 name: "Alice"}`  
Lists: `numbers = [1 2 3 4 5]`
Functions: `add = a b -> a + b`  
Anonymous: `[x -> x + 1]`
Pipeline: `data filter condition map transform`
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
    scores: user.scores filter even?
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
  | otherwise -> "regular"

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
# Variables
name = "Alice"
age = 28
active = true
threshold = 0.95

# Records
user = {id: 1 name: "Alice" age: 28}
config = {database: "localhost" port: 5432}

# Lists
numbers = [1 2 3 4 5]
users = [{id: 1 name: "Alice"} {id: 2 name: "Bob"}]

# Nested data
company = {
  name: "TechCorp"
  employees: [
    {id: 1 name: "Alice" department: "engineering"}
    {id: 2 name: "Bob" department: "sales"}
  ]
}
```

### Functions
```
# Named functions
add = a b -> a + b
multiply = a b -> a * b
is-adult = age -> age >= 18

# Multiple parameters
calculate-total = price tax-rate -> price + (price * tax-rate)

# Anonymous functions
double = [x -> x * 2]
is-even = [n -> n % 2 = 0]

# Anonymous functions in data
users
  map [user -> {user.name user.age}]
  filter [user -> user.age > 18]
```

### Predicates
```
# Basic predicates
even? = n -> n % 2 = 0
odd? = n -> n % 2 != 0
positive? = n -> n > 0
negative? = n -> n < 0
zero? = n -> n = 0

# Collection predicates
empty? = collection -> count collection = 0
any? = collection -> count collection > 0
single? = collection -> count collection = 1

# String predicates
empty-string? = s -> s = ""
contains? = s substring -> includes s substring
starts-with? = s prefix -> starts-with s prefix
ends-with? = s suffix -> ends-with s suffix

# Record predicates
has-key? = record key -> contains-key record key
valid-email? = email -> matches email r"^[^@]+@[^@]+\.[^@]+$"

# Using predicates
numbers filter even?
users filter [user -> user.age > 18]
emails filter valid-email?
strings filter [starts-with? "prefix"]
```

### Pipeline - Basic
```
# Simple pipeline
numbers
  filter even?
  map double
  take 5

# Pipeline with multiple arguments
data
  filter status = "active"
  map [user -> {user.name user.age}]
  sort-by age desc

# Equivalent to nested calls
result = sort-by (map (filter data status = "active") [user -> {user.name user.age}]) age desc
```

### Pipeline - Nested
```
# Two-level nesting
users
  filter age > 18
  map [user -> {
    name: user.name
    scores: user.scores filter even?
    avg-score: user.scores filter even? average
  }]

# Three-level nesting
company
  map [dept -> {
    name: dept.name
    employees: dept.employees
      filter [emp -> emp.age > 25]
      map [emp -> {emp.name emp.salary}]
      sort-by salary desc
      take 3
  }]
  filter [dept -> count dept.employees > 0]

# Complex nested pipeline
sales-data
  filter [sale -> sale.date > "2024-01-01"]
  group-by [sale -> sale.region]
  map [group -> {
    region: group.key
    total: sum group.amount
    count: count group
    average: average group.amount
    top-sales: group
      sort-by amount desc
      take 5
      map [sale -> {sale.date sale.amount}]
  }]
  sort-by total desc
```

### Grouping and Order Control
```
# Basic grouping
result = add (multiply 5 2) 10  # (5 * 2) + 10 = 20
result = multiply (add 10 5) 2  # (10 + 5) * 2 = 30

# Complex grouping
complex = add
  multiply (add 10 5) 2
  subtract 3 1

# Pipeline with grouping
data
  filter (condition1) (condition2)
  map [x -> (process x) (validate x)]

# Nested grouping
result = outer-function
  (inner-function data param1)
  (another-inner param2)
```

### Control Through Anonymous Functions
```
# When pipeline isn't enough
data
  map [x -> (process x) (validate x)]
  filter [x -> (check-quality x) threshold]
  map [x -> {
    original: x
    processed: (transform x) option1
    validated: (validate x) option2
  }]

# Complex control logic
numbers
  map [n -> 
    if even? n then n * 2 else n * 3
  ]
  filter [n -> n > 10]
  map [n -> {
    number: n
    is-perfect: (check-perfect n) strict-mode
    factors: (get-factors n) limit: 10
  }]
```

### Let Bindings
```
# Basic let
result = let {
  base-price = 100
  tax-rate = 0.08
  tax = base-price * tax-rate
} in base-price + tax

# Let in pipeline
processed = raw-data
  let {
    filtered = filter age > 18
    grouped = group-by department
  } in grouped
  calculate [{total: sum salary}]

# Complex let
analysis = data
  let {
    active-users = filter status = "active"
    by-region = group-by region
    metrics = transform by-region [group -> {
      region: group.key
      count: count group
      avg-age: average group.age
      total-spent: sum group.spent
    }]
  } in metrics
    sort-by count desc
    take 5
```

### Conditionals
```
# Simple if
result = if score > 90 then "A" else "B"

# Multi-way conditional
grade = score ->
  if score > 90 then "A"
  else if score > 80 then "B"
  else if score > 70 then "C"
  else "F"

# Conditional in pipeline
users
  map [user -> {
    name: user.name
    category: if user.age < 18 then "minor" else "adult"
    status: if user.active then "active" else "inactive"
  }]

# Complex conditional logic
process-data = data ->
  if empty? data then "no data"
  else if single? data then "single record"
  else if count data > 1000 then "large dataset"
  else "normal dataset"
```

### Complete Example
```
# Real-world data processing
sales-report = let {
  raw-sales = read-csv "sales.csv"
  
  cleaned = raw-sales
    filter [sale -> sale.amount > 0 and sale.date != null]
    map [sale -> {
      date: parse-date sale.date
      amount: to-decimal sale.amount
      region: normalize-text sale.region
      category: normalize-text sale.category
    }]
  
  by-region = cleaned
    group-by region
    map [group -> {
      region: group.region
      total: sum group.amount
      count: count group
      average: average group.amount
    }]
  
  top-regions = by-region
    sort-by total desc
    take 5
} in top-regions
  map [region -> {
    region: region.region
    revenue: region.total
    orders: region.count
    avg-order: region.average
    rank: index + 1
  }]

### Pattern Matching
```
# Basic pattern matching
classify-user = user ->
  | {age: a} when a < 18 -> "minor"
  | {age: a} when a >= 65 -> "senior"
  | {status: "vip"} -> "premium"
  | otherwise -> "regular"

# Pattern matching in pipeline
users
  map [user ->
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

### Try-Catch
```
# Basic try-catch
data = try
  read-csv "data.csv"
catch error -> "Failed to read file: {error}"

# Try-catch in pipeline
processed = raw-data
  try
    filter [item -> item.amount > 0]
    map [item -> parse-date item.date]
  catch error ->
    log "Processing failed: {error}"
    empty-list

# Multiple catch blocks
result = try
  load-from-api endpoint
  process-data
catch HttpError status -> "HTTP error: {status}"
catch TimeoutError -> "Request timed out"
catch error -> "Unknown error: {error}"

# Try-catch with let
analysis = let {
  data = try
    read-database "analytics"
  catch error -> []
  
  processed = try
    transform data
  catch error -> default-data
} in processed
```

### Side Effects (!)
```
# Dirty operations always return input for pipeline continuation
# Naming convention: function-name!

# Basic side effect functions
log! = data message -> (print message) data
save! = data filename -> (write-file filename data) data
notify! = data message -> (send-notification message) data

# Using side effects in pipeline
processed = raw-data
  filter amount > 0
  log! "Filtering complete"
  map [item -> item * 2]
  log! "Transformation complete"
  save! "processed.json"
  sort-by amount desc

# Side effects with custom messages
users
  filter active = true
  log! ["Found {count users} active users"]
  map [user -> {user.name user.email}]
  save! "active-users.json"
  notify! ["User report generated at {current-time}"]

# Multiple side effects
data
  clean
  log! "Data cleaned"
  validate
  log! "Data validated"
  save! "clean-data.json"
  log! "Data saved"
  notify! "Processing complete"

# Side effects in error handling
result = try
  process-data
  save! "result.json"
  log! "Processing successful"
catch error ->
  log! ["Error occurred: {error}"]
  notify! ["Processing failed: {error}"]
  empty-list

# Custom side effect functions
export-to-api! = data endpoint -> 
  (post-data endpoint data) data

backup-database! = config ->
  (create-backup config) config

audit-log! = operation user ->
  (write-audit-log operation user timestamp) operation

# Complex pipeline with side effects
sales-report = raw-sales
  filter amount > 0
  log! ["Processing {count raw-sales} sales records"]
  group-by region
  map [group -> {
    region: group.region
    total: sum group.amount
    count: count group
  }]
  sort-by total desc
  take 10
  log! "Top 10 regions calculated"
  save! "top-regions.json"
  export-to-api! "https://api.company.com/reports"
  notify! ["Sales report ready: {count top-regions} regions"]

# Side effects in let bindings
analysis = let {
  data = load-data "input.csv"
  cleaned = data
    clean
    log! "Data cleaned"
    validate
    log! "Data validated"
  
  result = processed
    calculate-metrics
    log! "Metrics calculated"
    save! "analysis.json"
} in result
```

### Side Effects (!)
```
# Dirty operations always return input for pipeline continuation
# Naming convention: function-name!
# First argument is data (underscore when unused), then parameters

# Basic side effect functions
log! = _ message -> (print message) _
save! = data filename -> (write-file filename data) data
notify! = _ message -> (send-notification message) _

# Using side effects in pipeline
processed = raw-data
  filter amount > 0
  log! _ "Filtering complete"
  map [item -> item * 2]
  log! _ "Transformation complete"
  save! _ "processed.json"
  sort-by amount desc

# Side effects with formatted messages
users
  filter active = true
  log! _ (format "Found %d active users" (count users))
  map [user -> {user.name user.email}]
  save! _ "active-users.json"
  notify! _ (format "User report generated at %s" (current-time))

# Multiple side effects
data
  clean
  log! _ "Data cleaned"
  validate
  log! _ "Data validated"
  save! _ "clean-data.json"
  log! _ "Data saved"
  notify! _ "Processing complete"

# Side effects in error handling
result = try
  process-data
  save! _ "result.json"
  log! _ "Processing successful"
catch error ->
  log! _ (format "Error occurred: %s" error)
  notify! _ (format "Processing failed: %s" error)
  empty-list

# Complex pipeline with side effects
sales-report = raw-sales
  filter amount > 0
  log! _ (format "Processing %d sales records" (count raw-sales))
  group-by region
  map [group -> {
    region: group.region
    total: sum group.amount
    count: count group
  }]
  sort-by total desc
  take 10
  log! _ "Top 10 regions calculated"
  save! _ "top-regions.json"
  notify! _ (format "Sales report ready: %d regions" (count top-regions))
```

### String Formatting
```
# Basic format function
format = format-string values...

# Format specifiers
# %s - string
# %d - integer  
# %f - float/decimal
# %b - boolean
# %% - literal percent

# Basic examples
format "Hello %s" "World"           # "Hello World"
format "Age: %d" 25                  # "Age: 25"
format "Price: %.2f" 19.99           # "Price: 19.99"
format "Active: %b" true             # "Active: true"
format "%d%% complete" 75             # "75% complete"

# Multiple values
format "%s is %d years old" "Alice" 28  # "Alice is 28 years old"
format "Total: $%.2f (%d items)" 123.45 3  # "Total: $123.45 (3 items)"

# In predicates and functions
describe-user = user -> 
  format "%s (%d) - %s" user.name user.age user.department

status-message = count -> 
  if count = 0 then "No records"
  else if count = 1 then format "%d record" count
  else format "%d records" count

# In side effects
users
  filter active = true
  log! _ (format "Found %d active users" (count users))
  map [user -> log! _ (format "Processing user: %s" user.name)]

# Complex formatting
generate-report = data ->
  format """
Report Summary:
  Total records: %d
  Success rate: %.2f%%
  Generated at: %s
  Status: %s
  """ (count data) success-rate (current-time) status

# Formatting in pattern matching
process-result = result ->
  | {status: "success" count: c} -> format "Success: %d items processed" c
  | {status: "error" message: m} -> format "Error: %s" m
  | {status: "pending"} -> "Processing..."
  | otherwise -> format "Unknown status: %s" result.status

# In let bindings
summary = let {
  total = sum data.amount
  average = total / (count data)
  message = format "Total: $%.2f, Average: $%.2f" total average
} in message
```
```