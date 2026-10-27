# Store pictures

`make_screenshots.py` draws the five Play Store phone screenshots (1080x1920) from the photos in `photos/`,
using the app's own colors, fonts and layouts. GitHub redraws them on every change (`.github/workflows/store-screenshots.yml`).

Photos are Library of Congress prints in the public domain. The `-restored` and `-color` files are Heirloom's real output
from the live pipeline on 2026-10-26 (face check 0.97 Migrant Mother, 0.91 bride, 0.97 boy).

| file | source |
|---|---|
| migrant-mother-1936 | Dorothea Lange, FSA, LC-DIG-fsa-8b29516 |
| bride-1920 | LC-DIG-ppmsca / cph 3f06075 |
| boy-1909 | Portuguese-Hawaiian boy, ppmsca 38999 |
| grandmother-1900 | Katerina Breshkovskaia card, pcrd 1b13542 |

Upload to Play from the box (service account `play-publisher`):
```
python3 - <<'PY'
import sys; sys.argv=['x']; exec(open('/root/.play/play.py').read().rsplit('\n',2)[0])
pkg='com.wimlabs.heirloom'; e=edit(pkg); base=f'/applications/{pkg}/edits/{e}/listings/en-US/phoneScreenshots'
req('DELETE', base)
for f in sorted(__import__('glob').glob('docs/store/screenshots/*.png')):
    print(f, req('POST', base+'?uploadType=media', raw=open(f,'rb').read(), ctype='image/png', base=UP)['image']['id'])
print(req('POST', f'/applications/{pkg}/edits/{e}:commit'))
PY
```
