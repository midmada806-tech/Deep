#!/usr/bin/env bash
# Fetches the native sources :mnn and :bergamot compile (they were empty git-submodule folders in the zip).
# Run once from the repo root. Needs git + network. If the repo is a git checkout, `git submodule update --init
# --recursive` does the same thing via .gitmodules.
set -euo pipefail
cd "$(dirname "$0")/.."

clone() { # url branch dest
  local url="$1" branch="$2" dest="$3"
  if [ -n "$(ls -A "$dest" 2>/dev/null)" ]; then echo "skip $dest (not empty)"; return; fi
  rm -rf "$dest"; mkdir -p "$(dirname "$dest")"
  if [ -n "$branch" ]; then git clone --recursive --branch "$branch" "$url" "$dest"
  else git clone --recursive "$url" "$dest"; fi
}

clone https://github.com/dominostars/MNN.git    playtranslate-android mnn/third-party/MNN
clone https://github.com/dominostars/slimt.git  playtranslate-android bergamot/third-party/slimt
clone https://github.com/KhronosGroup/OpenCL-Headers.git     "" vendor/OpenCL-Headers
clone https://github.com/KhronosGroup/OpenCL-ICD-Loader.git  "" vendor/OpenCL-ICD-Loader
echo "done. Build needs Android NDK 28.2.13676358 + CMake 3.22.1 (SDK Manager) for :mnn and :bergamot."
