#!/usr/bin/env bash
# Pull real public-domain face fixtures for the cosine-correctness test tier.
#
#   same_a + same_b   : two distinct photos of Einstein (different decades,
#                       different photographers, different lighting). Cosine
#                       should be > 0.5 with the AdaFace IR-101 model.
#   diff_a            : Niels Bohr. Cosine vs same_a should be < 0.3.
#
# All sources are Wikimedia Commons with policy-compliant User-Agent.
# Run from the cog-adaface/ directory:
#   bash test/download_fixtures.sh

set -euo pipefail

UA="HeirloomCogTest/0.1 (gugosf@gmail.com; https://github.com/gugosf114/heirloom-android)"
DIR="$(cd "$(dirname "$0")" && pwd)/fixtures"
mkdir -p "$DIR"

declare -A FIXTURES=(
  ["same_a.jpg"]="https://upload.wikimedia.org/wikipedia/commons/d/d3/Albert_Einstein_Head.jpg"
  ["same_b.jpg"]="https://upload.wikimedia.org/wikipedia/commons/3/3e/Einstein_1921_by_F_Schmutzer_-_restoration.jpg"
  ["diff_a.jpg"]="https://upload.wikimedia.org/wikipedia/commons/6/6d/Niels_Bohr.jpg"
)

for name in "${!FIXTURES[@]}"; do
  url="${FIXTURES[$name]}"
  out="$DIR/$name"
  if [ -f "$out" ]; then
    echo "exists  $name"
    continue
  fi
  echo "fetch   $name <- $url"
  curl -fsSL -A "$UA" "$url" -o "$out"
  size=$(wc -c < "$out")
  echo "    ${size} bytes"
done

echo ""
echo "fixtures ready in $DIR"
echo "now run:  USE_CPU=1 python test/local_test.py --full"
