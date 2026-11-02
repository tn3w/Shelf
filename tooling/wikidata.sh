#!/usr/bin/env bash
# Runs tooling/wikidata/*.rq on QLever into <out dir>/<name>.tsv; labels per language.
# usage: tooling/wikidata.sh <out dir>
set -euo pipefail

[ $# -eq 1 ] || { echo "usage: $0 <out dir>" >&2; exit 2; }
out=$1
queries=$(dirname "$0")/wikidata
endpoint=https://qlever.dev/api/wikidata
languages="en de fr es et sv pl cs ko fi ja lv"

fetch() {
  curl -sSfL --retry 5 --retry-all-errors --max-time 900 "$endpoint" \
    -H "Accept: text/tab-separated-values" --data-urlencode "query=$2" >> "$out/$1.tsv"
}

mkdir -p "$out"
for query in "$queries"/*.rq; do
  name=$(basename "$query" .rq)
  : > "$out/$name.tsv"
  if [ "$name" = label ]; then
    for language in $languages; do
      fetch label "$(sed "s/LANGUAGE/$language/" "$query")"
    done
  else
    fetch "$name" "$(cat "$query")"
  fi
  echo "$name: $(wc -l < "$out/$name.tsv") rows"
done
