#!/usr/bin/env bash
# Diffs a PR's base..head range and reports API/schema-relevant changes to
# api-schema-findings.md (consumed by the api-schema-review workflow).
set -euo pipefail

BASE_SHA="${1:?base sha required}"
HEAD_SHA="${2:?head sha required}"
OUT="api-schema-findings.md"

# File patterns treated as schema/contract definitions.
SCHEMA_PATTERN='\.(proto|graphql|gql|avsc|thrift)$|(^|/)(openapi|swagger)[^/]*\.(ya?ml|json)$|\.sql$|(^|/)migrations/'
# File patterns treated as source that exposes a public API.
SOURCE_PATTERN='\.(java|ts|tsx|go|py|kt|rb)$'
# Lines that look like a public/exported signature, per language.
SIGNATURE_PATTERN='^\+|^-'
SIGNATURE_GREP='public |protected |export |func |def |interface |class '

changed_files=$(git diff --name-only "${BASE_SHA}" "${HEAD_SHA}" || true)

schema_files=$(echo "${changed_files}" | grep -E "${SCHEMA_PATTERN}" || true)
source_files=$(echo "${changed_files}" | grep -E "${SOURCE_PATTERN}" || true)

{
  echo "## API/Schema Review"
  echo
  echo "Comparing \`${BASE_SHA:0:7}\`...\`${HEAD_SHA:0:7}\`."
  echo
} > "${OUT}"

found_any=0

if [ -n "${schema_files}" ]; then
  found_any=1
  echo "### Schema definition changes" >> "${OUT}"
  echo >> "${OUT}"
  while IFS= read -r f; do
    [ -z "${f}" ] && continue
    echo "<details><summary><code>${f}</code></summary>" >> "${OUT}"
    echo >> "${OUT}"
    echo '```diff' >> "${OUT}"
    git diff "${BASE_SHA}" "${HEAD_SHA}" -- "${f}" >> "${OUT}" || true
    echo '```' >> "${OUT}"
    echo "</details>" >> "${OUT}"
    echo >> "${OUT}"
  done <<< "${schema_files}"
fi

if [ -n "${source_files}" ]; then
  sig_report=""
  while IFS= read -r f; do
    [ -z "${f}" ] && continue
    diff_out=$(git diff "${BASE_SHA}" "${HEAD_SHA}" -- "${f}" | grep -E "${SIGNATURE_PATTERN}" | grep -E "${SIGNATURE_GREP}" || true)
    if [ -n "${diff_out}" ]; then
      sig_report="${sig_report}\n#### \`${f}\`\n\n\`\`\`diff\n${diff_out}\n\`\`\`\n"
    fi
  done <<< "${source_files}"

  if [ -n "${sig_report}" ]; then
    found_any=1
    echo "### Public API signature changes" >> "${OUT}"
    echo >> "${OUT}"
    printf '%b\n' "${sig_report}" >> "${OUT}"
  fi
fi

if [ "${found_any}" -eq 0 ]; then
  echo "No API or schema-affecting changes detected in this PR." >> "${OUT}"
fi

cat "${OUT}"
