#!/bin/bash
# skillhub CLI wrapper for agent skills management
# Usage: ./usage.sh '<json>'
# Example: ./usage.sh '{"action": "search", "query": "weather"}'

set -e

JSON_INPUT="$1"

if [ -z "$JSON_INPUT" ]; then
    echo "Usage: ./usage.sh '<json>'"
    echo ""
    echo "Actions:"
    echo "  search       Search skills in the store"
    echo "  install      Install a skill by slug"
    echo "  upgrade      Upgrade installed skills"
    echo "  list         List locally installed skills"
    echo "  self-upgrade Self-upgrade the skillhub CLI"
    echo ""
    echo "Examples:"
    echo "  ./usage.sh '{\"action\": \"search\", \"query\": \"weather\"}'"
    echo "  ./usage.sh '{\"action\": \"install\", \"slug\": \"weather\"}'"
    echo "  ./usage.sh '{\"action\": \"upgrade\"}'"
    echo "  ./usage.sh '{\"action\": \"list\"}'"
    exit 1
fi

# Validate JSON
if ! echo "$JSON_INPUT" | jq empty 2>/dev/null; then
    echo "Error: Invalid JSON input"
    exit 1
fi

# Extract action
ACTION=$(echo "$JSON_INPUT" | jq -r '.action // empty')

if [ -z "$ACTION" ]; then
    echo "Error: 'action' field is required (search, install, upgrade, list, self-upgrade)"
    exit 1
fi

# Validate slug/query: reject values containing shell metacharacters to prevent injection
validate_arg() {
    local arg="$1"
    local name="$2"
    if [ -n "$arg" ] && [[ "$arg" =~ [\"\`\$\\\|\;\&\<\>\(\)] ]]; then
        echo "Error: Invalid characters in '$name' (disallowed for security)"
        exit 1
    fi
}

# Build and execute skillhub command using arrays (no eval)
case "$ACTION" in
    search)
        QUERY=$(echo "$JSON_INPUT" | jq -r '.query // empty')
        LIMIT=$(echo "$JSON_INPUT" | jq -r '.limit // 20')
        TIMEOUT=$(echo "$JSON_INPUT" | jq -r '.timeout // 6')
        JSON_OUTPUT=$(echo "$JSON_INPUT" | jq -r '.json // false')
        validate_arg "$QUERY" "query"
        validate_arg "$LIMIT" "limit"
        validate_arg "$TIMEOUT" "timeout"

        ARGS=(skillhub search)
        [ -n "$QUERY" ] && ARGS+=("$QUERY")
        ARGS+=(--search-limit "$LIMIT" --search-timeout "$TIMEOUT")
        [ "$JSON_OUTPUT" = "true" ] && ARGS+=(--json)
        ;;

    install)
        SLUG=$(echo "$JSON_INPUT" | jq -r '.slug // empty')
        FORCE=$(echo "$JSON_INPUT" | jq -r '.force // false')

        if [ -z "$SLUG" ]; then
            echo "Error: 'slug' is required for install action"
            exit 1
        fi
        validate_arg "$SLUG" "slug"

        ARGS=(skillhub install)
        [ "$FORCE" = "true" ] && ARGS+=(--force)
        ARGS+=("$SLUG")
        ;;

    upgrade)
        SLUG=$(echo "$JSON_INPUT" | jq -r '.slug // empty')
        CHECK_ONLY=$(echo "$JSON_INPUT" | jq -r '.check_only // false')
        TIMEOUT=$(echo "$JSON_INPUT" | jq -r '.timeout // 20')
        validate_arg "$SLUG" "slug"
        validate_arg "$TIMEOUT" "timeout"

        ARGS=(skillhub upgrade)
        [ "$CHECK_ONLY" = "true" ] && ARGS+=(--check-only)
        ARGS+=(--timeout "$TIMEOUT")
        [ -n "$SLUG" ] && ARGS+=("$SLUG")
        ;;

    list)
        ARGS=(skillhub list)
        ;;

    self-upgrade)
        CHECK_ONLY=$(echo "$JSON_INPUT" | jq -r '.check_only // false')
        CURRENT_VERSION=$(echo "$JSON_INPUT" | jq -r '.current_version // empty')
        TIMEOUT=$(echo "$JSON_INPUT" | jq -r '.timeout // 20')
        validate_arg "$CURRENT_VERSION" "current_version"
        validate_arg "$TIMEOUT" "timeout"

        ARGS=(skillhub self-upgrade)
        [ "$CHECK_ONLY" = "true" ] && ARGS+=(--check-only)
        [ -n "$CURRENT_VERSION" ] && ARGS+=(--current-version "$CURRENT_VERSION")
        ARGS+=(--timeout "$TIMEOUT")
        ;;

    *)
        echo "Error: Unknown action '$ACTION'"
        echo "Valid actions: search, install, upgrade, list, self-upgrade"
        exit 1
        ;;
esac

# Execute the command (arguments passed safely, no eval)
"${ARGS[@]}"
