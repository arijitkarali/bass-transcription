#!/bin/sh
set -eu
cd "$(dirname "$0")/../frontend"
# Prefer the user's package manager; use the desktop's bundled runtime on this Mac.
if command -v node >/dev/null 2>&1 && command -v pnpm >/dev/null 2>&1; then
  exec pnpm "$@"
fi
runtime="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies"
if [ -x "$runtime/bin/fallback/pnpm" ]; then
  export PATH="$runtime/node/bin:$PATH"
  exec "$runtime/bin/fallback/pnpm" "$@"
fi
printf '%s\n' 'Install Node.js 22.12+ and pnpm, then run this command again.' >&2
exit 1
