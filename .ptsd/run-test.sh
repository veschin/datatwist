#!/bin/bash
# PTSD test runner for DataTwist
# Receives argument from ptsd - could be feature name or file path
ARG="$1"

# If it looks like a file path, use it directly
if [[ "$ARG" == *.clj ]]; then
  FILE="$ARG"
else
  # It's a feature name - just run all tests
  exec make test
fi

# Convert path to namespace: test/datatwist/literals_test.clj -> datatwist.literals-test
NS=$(echo "$FILE" | sed 's|^test/||; s|\.clj$||; s|/|.|g; s|_|-|g')

exec clj -M -e "(require 'clojure.test '${NS}) (let [r (clojure.test/run-tests '${NS})] (System/exit (+ (:fail r) (:error r))))"
