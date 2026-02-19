#!/usr/bin/env bash
set -euo pipefail

CHANGELOG="$(cd "$(dirname "$0")/.." && pwd)/CHANGELOG.md"

usage() {
    echo "Usage:"
    echo "  $0 \"DATATWIST-11: Feature Name\" commit1 commit2 commit3"
    echo "  $0 \"DATATWIST-11: Feature Name\" abc1234..def5678"
    echo ""
    echo "If the title does not include a DATATWIST-N number, the next"
    echo "number is auto-detected from CHANGELOG.md and prepended."
    exit 1
}

if [[ $# -lt 1 ]]; then
    usage
fi

TITLE="$1"
shift

# Auto-detect next DATATWIST number if title doesn't include one
if ! echo "$TITLE" | grep -qE '^DATATWIST-[0-9]+:'; then
    last_num=$(grep -oE 'DATATWIST-[0-9]+' "$CHANGELOG" | grep -oE '[0-9]+' | sort -n | tail -1)
    if [[ -z "$last_num" ]]; then
        last_num=0
    fi
    next_num=$((last_num + 1))
    TITLE="DATATWIST-${next_num}: ${TITLE}"
fi

if [[ $# -lt 1 ]]; then
    echo "Error: no commits specified." >&2
    usage
fi

# Collect commit lines
commit_lines=()

for arg in "$@"; do
    if echo "$arg" | grep -q '\.\.'; then
        # It's a range
        while IFS= read -r line; do
            commit_lines+=("$line")
        done < <(git -C "$(dirname "$CHANGELOG")" log --format="%h %s" --reverse "$arg")
    else
        # Individual commit hash
        line=$(git -C "$(dirname "$CHANGELOG")" log --format="%h %s" -1 "$arg")
        commit_lines+=("$line")
    fi
done

if [[ ${#commit_lines[@]} -eq 0 ]]; then
    echo "Error: no commits found for the given arguments." >&2
    exit 1
fi

# Build the markdown block
block=""
block+="\n## ${TITLE}"
for line in "${commit_lines[@]}"; do
    hash="${line%% *}"
    subject="${line#* }"
    block+="\n- \`${hash}\` ${subject}"
done

# Append to CHANGELOG.md
printf "%b\n" "$block" >> "$CHANGELOG"

# Print to stdout for review
printf "%b\n" "$block"
