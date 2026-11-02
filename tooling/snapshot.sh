#!/usr/bin/env bash
# Packs source dumps CI cannot fetch (BNE behind Cloudflare, CiNii crawl) into a release.
# usage: tooling/snapshot.sh <bne dir> <cinii pages dir> <out dir>
set -euo pipefail

[ $# -eq 3 ] || { echo "usage: $0 <bne dir> <cinii pages dir> <out dir>" >&2; exit 2; }
bne=$1
cinii=$2
out=$3
tag="sources-$(date -u +%Y-%m)"
part=1900M

pack() {
  zstd -19 -T0 -q -c | split -b "$part" -d -a 2 - "$out/$1.zst.part"
}

mkdir -p "$out"
for name in monomodernas recelectronicos persona; do
  pack "bne-$name.mrc" < "$bne/$name-MARC.mrc"
done
cat "$cinii"/*.jsonl.gz | gzip -dc | pack cinii.jsonl
(cd "$out" && sha256sum ./*.part* > SHA256SUMS)
ls -lh "$out"

upload=(gh release create "$tag" --latest=false --title "Sources $tag"
  --notes "BNE (CC0) and CiNii Books (CC BY 4.0, NII) snapshots for the database workflow."
  "$out"/*)
printf 'Upload command:\n  %s\n' "${upload[*]}"
read -r -p "Upload now? [y/N] " answer
[ "$answer" = y ] && "${upload[@]}"
