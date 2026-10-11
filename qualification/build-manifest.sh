#!/usr/bin/env bash
# Build the frozen input manifest: one JSONL row per unique input file.
#
# Records the independent, pre-repair facts (source path, SHA-256, byte size,
# page count, qpdf --check exit code) so every later claim can be compared back
# to a baseline that was captured before any operation ran. Inputs are immutable;
# this script never writes into qualification/inputs/.
#
# Usage: qualification/build-manifest.sh
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
inroot="$root/qualification/inputs"
out="$root/qualification/manifests/inputs.jsonl"
mkdir -p "$(dirname "$out")"
: > "$out"

lane_for() {
  case "$1" in
    general|generated) echo "healthy_preservation" ;;
    pathological)      echo "robustness" ;;
    *)                 echo "unknown" ;;
  esac
}

# expectedOpenable flag from the fixture author, kept separate from any repair
# expectation (a fixture that is expected to fail conformance is not thereby
# expected to be structurally damaged). Looked up per id with awk because the
# host /bin/bash is 3.2 and has no associative arrays.
pm="$inroot/pathological/pathological-manifest.tsv"

emit() {
  local group="$1" file="$2"
  local id sha bytes pages qrc exp
  id="$(basename "$file")"
  # Each probe is guarded so a non-zero status (qpdf 2=errors/3=warnings, or an
  # unreadable PDF for pdfinfo) is RECORDED, not allowed to abort the manifest
  # under `set -e`/`set -o pipefail`.
  sha="$(shasum -a 256 "$file" | awk '{print $1}')" || sha=""
  bytes="$(wc -c < "$file" | tr -d ' ')" || bytes="0"
  pages="$({ pdfinfo "$file" 2>/dev/null || true; } | awk -F': *' '/^Pages:/{print $2}')" || pages=""
  [ -z "$pages" ] && pages="-1"
  qrc=0
  qpdf --check "$file" >/dev/null 2>&1 || qrc=$?
  exp="n/a"
  if [ "$group" = "pathological" ] && [ -f "$pm" ]; then
    exp="$(awk -F'\t' -v id="${id%.pdf}" 'NR>1 && $1==id {print $3; found=1} END {if(!found) print "unknown"}' "$pm")"
  fi
  printf '{"id":"%s","input":"%s","sha256":"%s","bytes":%s,"page_count":%s,"qpdf_check":%s,"group":"%s","lane":"%s","expected_openable":"%s"}\n' \
    "$id" "$file" "$sha" "$bytes" "$pages" "$qrc" "$group" "$(lane_for "$group")" "$exp" >> "$out"
}

for group in general generated pathological; do
  [ -d "$inroot/$group" ] || continue
  while IFS= read -r -d '' f; do
    emit "$group" "$f"
  done < <(find "$inroot/$group" -maxdepth 1 -type f -name '*.pdf' -print0 | sort -z)
done

echo "manifest: $(wc -l < "$out" | tr -d ' ') rows -> $out"
