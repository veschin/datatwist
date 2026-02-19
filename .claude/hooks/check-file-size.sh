#!/bin/bash

INPUT=$(cat)

FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')
LIMIT=$(echo "$INPUT" | jq -r '.tool_input.limit // empty')

# If limit is set, allow — user is reading a chunk
if [ -n "$LIMIT" ]; then
  exit 0
fi

# If file doesn't exist, let Read handle the error
if [ -z "$FILE_PATH" ] || [ ! -f "$FILE_PATH" ]; then
  exit 0
fi

LINE_COUNT=$(wc -l < "$FILE_PATH")

if [ "$LINE_COUNT" -gt 500 ]; then
  jq -n \
    --arg lines "$LINE_COUNT" \
    '{
      "hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": "File has \($lines) lines (>500). Use offset/limit or Grep."
      }
    }'
  exit 0
fi

exit 0
