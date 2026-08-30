#!/usr/bin/env bash
set -euo pipefail

if matches=$(git grep -nI -E '[[:blank:]]+$' -- \
    '*.kt' '*.kts' '*.py' '*.sh' '*.yml' '*.yaml' '*.md' \
    ':(exclude)AGENTS.md'); then
    printf '%s\n' "$matches"
    printf '%s\n' 'trailing whitespace found' >&2
    exit 1
fi
