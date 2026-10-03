#!/usr/bin/env bash
# One-time setup for a fresh clone: enables the TDD git hooks.
set -euo pipefail
cd "$(dirname "$0")/.."
git config core.hooksPath .githooks
command -v java >/dev/null || echo "Install a JDK (17+) to run tests locally, e.g. apt install openjdk-21-jdk-headless"
echo "Git hooks enabled. Run tests with: ./gradlew :core:test"
