#!/bin/sh
set -e

# 1. Clone sibling Rust repositories expected at ../../../olive-solve and ../../../olive-engine
REPO_ROOT="${CI_PRIMARY_REPOSITORY_PATH:-$(cd "$(dirname "$0")/../.." && pwd)}"
PARENT_DIR="$(cd "$REPO_ROOT/.." && pwd)"

if [ ! -d "$PARENT_DIR/olive-solve" ]; then
    git clone --depth 1 https://github.com/oakamil/olive-solve.git "$PARENT_DIR/olive-solve"
fi

if [ ! -d "$PARENT_DIR/olive-engine" ]; then
    git clone --depth 1 https://github.com/oakamil/olive-engine.git "$PARENT_DIR/olive-engine"
fi

# 2. Install OpenJDK 21 (for Kotlin Multiplatform / Gradle) and CMake (for turbojpeg-sys)
HOMEBREW_NO_AUTO_UPDATE=1 brew install openjdk@21 cmake

# 3. Install Rust and iOS targets for olive-ios
if ! command -v rustup >/dev/null 2>&1; then
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
fi
export PATH="$HOME/.cargo/bin:$PATH"
rustup target add aarch64-apple-ios aarch64-apple-ios-sim
