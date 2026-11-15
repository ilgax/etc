#!/usr/bin/env bash
# Renders build/katex-fixtures/*.json (written by KatexFixtureDump) with the bundled KaTeX page in headless
# Chrome and stores transparent PNGs in app/src/test/resources/katex/ for the Roborazzi screenshots.
# Usage: scripts/render-katex-fixtures.sh   (after ./gradlew :feature-math:testDebugUnitTest)
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
src="$root/feature-math/build/katex-fixtures"
out="$root/app/src/test/resources/katex"
assets="$root/feature-math/src/main/assets/katex"
tmp="$(mktemp -d)"
mkdir -p "$out"
for json in "$src"/*.json; do
  name="$(basename "$json" .json)"
  page="$assets/page-$name.html"
  python3 - "$assets/card.html" "$json" "$page" <<'PY'
import sys
html = open(sys.argv[1]).read().replace("body { font:", "body { width: 380px; font:")
html = html.replace("</body>", "<script>render(" + open(sys.argv[2]).read() + ");</script></body>")
open(sys.argv[3], "w").write(html)
PY
  google-chrome --headless=new --no-sandbox --disable-gpu --hide-scrollbars --default-background-color=00000000 \
    --force-device-scale-factor=2 --window-size=380,2400 --virtual-time-budget=4000 \
    --screenshot="$tmp/$name.png" "file://$page" >/dev/null 2>&1
  rm -f "$page"
  python3 - "$tmp/$name.png" "$out/$name.png" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGBA")
bbox = im.getchannel("A").getbbox()
h = (bbox[3] + 4) if bbox else 8
im.crop((0, 0, im.width, h)).save(sys.argv[2])
print(sys.argv[2], im.width, h)
PY
done
rm -rf "$tmp"
