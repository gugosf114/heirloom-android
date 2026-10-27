#!/usr/bin/env python3
"""Draws the Play Store phone screenshots. No phone, no emulator: the app's own colors, fonts and layout, redrawn
with real before/after photographs from docs/store/photos (Library of Congress, public domain; the "-restored" and
"-color" files are Heirloom's actual output). Run from anywhere:  python3 docs/store/make_screenshots.py
Writes docs/store/screenshots/0N-*.png at 1080x1920 (Play's 9:16 phone size)."""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFile
ImageFile.LOAD_TRUNCATED_IMAGES = True

ROOT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(ROOT, '..', '..'))
PHOTOS = os.path.join(ROOT, 'photos')
OUT = os.path.join(ROOT, 'screenshots')
FONT_DIR = os.path.join(REPO, 'app', 'src', 'main', 'res', 'font')

# GalleryDark from app/src/main/java/com/heirloom/app/ui/theme/Theme.kt
BG = (0x0C, 0x17, 0x1B); PANEL = (0x10, 0x1E, 0x22); SURFACE = (0x15, 0x23, 0x28); SURFACE_HI = (0x1D, 0x2E, 0x33)
INK = (0xF4, 0xEF, 0xE6); MUTED = (0xAC, 0xB9, 0xB9); BRASS = (0xE4, 0xC9, 0x9C); ON_BRASS = (0x17, 0x24, 0x27)
OUTLINE = (0x43, 0x53, 0x56); OUTLINE_SOFT = (0x2A, 0x3B, 0x40); SAGE = (0xB5, 0xC7, 0xB7)
W, H = 1080, 1920
S = 1080 / 411  # px per dp: the app on a 411dp-wide phone

def font(name, size, weight=None):
    f = ImageFont.truetype(os.path.join(FONT_DIR, name), int(size))
    if weight is not None:
        try: f.set_variation_by_axes([weight])
        except Exception: pass
    return f
def serif(size): return font('cormorant.ttf', size, 400)
def sans(size, weight=400): return font('manrope.ttf', size, weight)
def dp(v): return int(round(v * S))
def rounded(draw, box, r, fill=None, outline=None, width=1): draw.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=width)
def text(draw, xy, s, f, fill, spacing=4): draw.multiline_text(xy, s, font=f, fill=fill, spacing=spacing)
def photo(name): return Image.open(os.path.join(PHOTOS, name))

def fit(im, box_w, box_h):
    """ContentScale.Fit: the whole photo, centered."""
    im = im.convert('RGB'); r = min(box_w / im.width, box_h / im.height)
    return im.resize((max(1, int(im.width * r)), max(1, int(im.height * r))), Image.LANCZOS)

def cover(im, box_w, box_h, focus=(0.5, 0.5)):
    """ContentScale.Crop with a focus point: (0, 0) keeps the top-left, (1, 1) the bottom-right."""
    im = im.convert('RGB'); r = max(box_w / im.width, box_h / im.height)
    im = im.resize((int(im.width * r) + 1, int(im.height * r) + 1), Image.LANCZOS)
    x = int((im.width - box_w) * focus[0]); y = int((im.height - box_h) * focus[1])
    return im.crop((x, y, x + box_w, y + box_h))

# ---------- app pieces, drawn on the phone layer ----------
def header(draw, y):
    """heirloom wordmark, the credits chip, the menu dots, the rule."""
    x0 = dp(24)
    draw.ellipse([x0, y + dp(6), x0 + dp(26), y + dp(32)], outline=BRASS, width=dp(2))
    draw.ellipse([x0 + dp(8), y + dp(14), x0 + dp(18), y + dp(24)], fill=BRASS)
    text(draw, (x0 + dp(36), y - dp(2)), 'heirloom', serif(dp(30)), INK)
    chip = [W - dp(24) - dp(118), y + dp(2), W - dp(24) - dp(44), y + dp(38)]
    rounded(draw, chip, dp(6), outline=OUTLINE_SOFT, width=dp(1))
    text(draw, (chip[0] + dp(12), y + dp(9)), '3', sans(dp(16), 500), BRASS)
    text(draw, (chip[0] + dp(26), y + dp(13)), 'left', sans(dp(11), 500), MUTED)
    for i in range(3): draw.ellipse([W - dp(24) - dp(14), y + dp(12) + i * dp(7), W - dp(24) - dp(10), y + dp(16) + i * dp(7)], fill=MUTED)
    draw.line([x0, y + dp(52), W - dp(24), y + dp(52)], fill=OUTLINE_SOFT, width=dp(1))
    return y + dp(52)

def eyebrow(draw, y, s):
    text(draw, (dp(24), y), s.upper(), sans(dp(11), 600), BRASS)
    return y + dp(18)

def comparison(layer, draw, y, before, after, split=0.5, crop=None):
    """PhotoComparison: restored underneath, original clipped left of the split, white line + handle, labels, slider."""
    x0, x1 = dp(24), W - dp(24); bw = x1 - x0; bh = int(bw / 0.88)
    rounded(draw, [x0, y, x1, y + bh], dp(6), fill=SURFACE)
    if crop is not None: a, b = cover(after, bw, bh, crop), cover(before, bw, bh, crop)
    else: a, b = fit(after, bw, bh), fit(before, bw, bh)
    ax, ay = x0 + (bw - a.width) // 2, y + (bh - a.height) // 2
    layer.paste(a, (ax, ay))
    sx = x0 + int(bw * split)
    bx, by = x0 + (bw - b.width) // 2, y + (bh - b.height) // 2
    if sx > bx: layer.paste(b.crop((0, 0, max(1, min(b.width, sx - bx)), b.height)), (bx, by))
    draw.line([sx, y, sx, y + bh], fill='white', width=dp(2))
    cy = y + int(bh * 0.55); r = dp(17)
    draw.ellipse([sx - r, cy - r, sx + r, cy + r], fill='white')
    for d in (-1, 1):
        tip, base = sx + d * dp(8), sx + d * dp(4)
        draw.line([base, cy - dp(4), tip, cy], fill=(0x17, 0x27, 0x2B), width=dp(1.5))
        draw.line([base, cy + dp(4), tip, cy], fill=(0x17, 0x27, 0x2B), width=dp(1.5))
    f = sans(dp(11), 600)
    for label, right in (('ORIGINAL', False), ('RESTORED', True)):
        tw = draw.textlength(label, font=f)
        lx = x1 - dp(12) - tw - dp(16) if right else x0 + dp(12)
        rounded(draw, [lx, y + dp(12), lx + tw + dp(16), y + dp(12) + dp(24)], dp(3), fill=(0x10, 0x20, 0x24))
        text(draw, (lx + dp(8), y + dp(17)), label, f, (0xF5, 0xF0, 0xE8))
    ty = y + bh + dp(22)
    draw.line([x0, ty, x1, ty], fill=OUTLINE_SOFT, width=dp(3))
    draw.line([x0, ty, sx, ty], fill=BRASS, width=dp(3))
    draw.ellipse([sx - dp(8), ty - dp(8), sx + dp(8), ty + dp(8)], fill=BRASS)
    return ty + dp(22)

def trust_card(draw, y):
    x0, x1 = dp(24), W - dp(24)
    rounded(draw, [x0, y, x1, y + dp(78)], dp(8), fill=SURFACE)
    cx, cy = x0 + dp(24), y + dp(24)
    draw.ellipse([cx - dp(10), cy - dp(10), cx + dp(10), cy + dp(10)], outline=SAGE, width=dp(2))
    draw.line([cx - dp(5), cy, cx - dp(1), cy + dp(4), cx + dp(6), cy - dp(4)], fill=SAGE, width=dp(2), joint='curve')
    text(draw, (x0 + dp(44), y + dp(14)), 'Face check passed', sans(dp(14), 600), INK)
    text(draw, (x0 + dp(44), y + dp(38)), 'The restored face is similar to the original.\nGive it your own final look.', sans(dp(12)), MUTED, spacing=dp(3))
    return y + dp(78)

def primary_button(draw, box, label, arrow=True):
    rounded(draw, box, dp(8), fill=BRASS)
    text(draw, (box[0] + dp(20), box[1] + dp(19)), label, sans(dp(15), 600), ON_BRASS)
    if arrow:
        my = (box[1] + box[3]) // 2; ex = box[2] - dp(20)
        draw.line([ex - dp(16), my, ex, my], fill=ON_BRASS, width=dp(2))
        draw.line([ex - dp(6), my - dp(6), ex, my, ex - dp(6), my + dp(6)], fill=ON_BRASS, width=dp(2))

def buttons(draw, y, primary, secondary):
    x0, x1 = dp(24), W - dp(24); gap = dp(10); h = dp(58)
    pw = int((x1 - x0 - gap) / 1.7); sw = x1 - x0 - gap - pw
    primary_button(draw, [x0, y, x0 + pw, y + h], primary)
    rounded(draw, [x0 + pw + gap, y, x1, y + h], dp(8), outline=OUTLINE, width=dp(1))
    f = sans(dp(15), 600); tw = draw.textlength(secondary, font=f)
    text(draw, (x0 + pw + gap + (sw - tw) / 2, y + dp(19)), secondary, f, INK)
    return y + h

# ---------- the store picture: caption band + phone panel ----------
def caption(draw, headline, sub):
    """Cormorant headline, Manrope line under it. Returns the y where the phone panel starts."""
    hl = headline.count('\n') + 1; sl = sub.count('\n') + 1
    text(draw, (dp(24), dp(36)), headline, serif(dp(44)), INK, spacing=dp(2))
    sub_y = dp(36) + hl * dp(50) + dp(8)
    text(draw, (dp(24), sub_y), sub, sans(dp(15)), MUTED, spacing=dp(4))
    return sub_y + sl * dp(21) + dp(24)

class Shot:
    """One store picture. The phone surface is drawn tall on `layer`, then shown from `scroll` dp down inside a rounded panel."""
    def __init__(self, headline, sub, scroll=0):
        self.canvas = Image.new('RGB', (W, H), BG)
        self.top = caption(ImageDraw.Draw(self.canvas), headline, sub)
        self.scroll = dp(scroll)
        self.layer = Image.new('RGB', (W, 3200), PANEL); self.draw = ImageDraw.Draw(self.layer)
    def save(self, out):
        panel = [dp(12), self.top, W - dp(12), H + dp(40)]
        mask = Image.new('L', (W, H), 0); ImageDraw.Draw(mask).rounded_rectangle(panel, radius=dp(28), fill=255)
        shown = Image.new('RGB', (W, H), BG)
        shown.paste(self.layer.crop((0, self.scroll, W, self.scroll + H)), (0, self.top + dp(26)))
        self.canvas.paste(shown, (0, 0), mask)
        ImageDraw.Draw(self.canvas).rounded_rectangle(panel, radius=dp(28), outline=OUTLINE_SOFT, width=dp(1))
        self.canvas.save(out); print('wrote', os.path.relpath(out, REPO))

def done_screen(before, after, split, headline, sub, out, scroll=0, crop=None):
    sh = Shot(headline, sub, scroll); d, L = sh.draw, sh.layer
    y = header(d, 0) + dp(24)
    y = eyebrow(d, y, '03 / A new chapter')
    text(d, (dp(24), y + dp(4)), 'Worth keeping.', serif(dp(34)), INK); y += dp(48)
    text(d, (dp(24), y), 'Slide across to see what changed.', sans(dp(14)), MUTED); y += dp(34)
    y = comparison(L, d, y, before, after, split, crop) + dp(4)
    y = trust_card(d, y) + dp(18)
    buttons(d, y, 'Save photo', 'Share')
    sh.save(out)

def picked_screen(before, headline, sub, out, mode_idx=0, color_on=True, scroll=0):
    sh = Shot(headline, sub, scroll); d, L = sh.draw, sh.layer
    y = header(d, 0) + dp(24)
    y = eyebrow(d, y, '01 / Your photograph')
    text(d, (dp(24), y + dp(4)), "Let's bring it back.", serif(dp(30)), INK); y += dp(58)
    x0, x1 = dp(24), W - dp(24); bw = x1 - x0; bh = int(bw / 0.92)
    rounded(d, [x0, y, x1, y + bh], dp(6), fill=SURFACE, outline=OUTLINE_SOFT, width=dp(1))
    p = fit(before, bw - dp(20), bh - dp(20)); L.paste(p, (x0 + (bw - p.width) // 2, y + (bh - p.height) // 2)); y += bh + dp(20)
    text(d, (dp(24), y), 'The original stays yours, untouched.', sans(dp(14)), MUTED); y += dp(34)
    x = dp(24)
    for i, label in enumerate(['Full restore', 'Repair only', 'Sharpen only', 'Color only']):
        f = sans(dp(13), 500); tw = d.textlength(label, font=f); box = [x, y, x + tw + dp(28), y + dp(32)]
        if i == mode_idx: rounded(d, box, dp(8), fill=SURFACE_HI, outline=BRASS, width=dp(1)); text(d, (x + dp(14), y + dp(8)), label, f, BRASS)
        else: rounded(d, box, dp(8), outline=OUTLINE, width=dp(1)); text(d, (x + dp(14), y + dp(8)), label, f, INK)
        x = box[2] + dp(8)
    y += dp(40)
    text(d, (dp(24), y), 'Repair damage, refine faces, sharpen detail.', sans(dp(12)), MUTED); y += dp(32)
    text(d, (dp(24), y), 'Add color', sans(dp(14), 600), INK)
    text(d, (dp(24), y + dp(20)), 'For black-and-white photos. AI guesses the colors.', sans(dp(12)), MUTED)
    tx = W - dp(24) - dp(52)
    rounded(d, [tx, y + dp(4), tx + dp(52), y + dp(32)], dp(14), fill=BRASS if color_on else OUTLINE)
    kx = tx + dp(28) if color_on else tx + dp(4)
    d.ellipse([kx, y + dp(8), kx + dp(20), y + dp(28)], fill=ON_BRASS if color_on else INK)
    y += dp(56)
    primary_button(d, [x0, y, x1, y + dp(58)], 'Restore this photograph')
    sh.save(out)

def idle_screen(before, headline, sub, out):
    sh = Shot(headline, sub); d, L = sh.draw, sh.layer
    y = header(d, 0) + dp(24)
    y = eyebrow(d, y, 'A little care. A little time travel.')
    text(d, (dp(24), y + dp(6)), 'A second life\nfor old photos.', serif(dp(44)), INK, spacing=0); y += dp(118)
    ph = dp(236); x0, x1 = dp(24), W - dp(24)
    tilt = Image.new('RGBA', (x1 - x0 - dp(36), ph - dp(18)), BRASS + (36,)).rotate(3, expand=True)
    L.paste(tilt, (x0 + dp(26), y + dp(6)), tilt)
    mount = [x0, y, x1 - dp(24), y + ph - dp(4)]
    rounded(d, mount, dp(2), fill=(0xEC, 0xE4, 0xD6))
    p = before.convert('RGB'); r = (mount[3] - mount[1] - dp(18)) / p.height
    p = p.resize((int(p.width * r), int(p.height * r)), Image.LANCZOS)
    pw = mount[2] - mount[0] - dp(18) - dp(30)
    if p.width > pw: p = p.crop(((p.width - pw) // 2, 0, (p.width - pw) // 2 + pw, p.height))
    L.paste(p, (mount[0] + dp(9), mount[1] + dp(9)))
    text(d, (mount[2] - dp(26), mount[1] + dp(9)), '01', sans(dp(11)), (0x43, 0x51, 0x4C))
    y += ph + dp(14)
    text(d, (dp(24), y), 'Repair the wear.\nKeep what matters.', sans(dp(14)), MUTED, spacing=dp(4)); y += dp(60)
    primary_button(d, [x0, y, x1, y + dp(58)], 'Choose a photograph'); y += dp(58) + dp(14)
    f = sans(dp(14)); s = 'Scan a printed photo'; tw = d.textlength(s, font=f); text(d, ((W - tw) / 2, y), s, f, INK); y += dp(40)
    f = sans(dp(12)); s = 'Private by design  ·  No subscription'; tw = d.textlength(s, font=f); text(d, ((W - tw) / 2, y), s, f, MUTED)
    sh.save(out)

if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    done_screen(photo('migrant-mother-1936.jpg'), photo('migrant-mother-1936-color.jpg'), 0.5,
                'Bring old family\nphotos back.', 'Scratches, fading and blur repaired.\nColor only when you ask for it.',
                os.path.join(OUT, '01-restore.png'))
    done_screen(photo('bride-1920.jpg'), photo('bride-1920-restored.jpg'), 0.38,
                'Slide to compare.', 'The original on the left, the restored\ncopy on the right. Your call, every time.',
                os.path.join(OUT, '02-compare.png'))
    done_screen(photo('boy-1909.jpg'), photo('boy-1909-restored.jpg'), 0.62,
                'Still actually them.', 'Heirloom checks the restored face against\nthe original and tells you what it found.',
                os.path.join(OUT, '03-face-check.png'), scroll=250, crop=(0.66, 0.15))
    picked_screen(photo('grandmother-1900.jpg'),
                  'Keep the tones.\nOr add color.', 'Full restore, repair only, sharpen only,\nor color only. One tap each.',
                  os.path.join(OUT, '04-modes.png'), scroll=215)
    idle_screen(photo('migrant-mother-1936.jpg'),
                'Private by design.\nNo subscription.', 'No account. No ads. Photos are deleted\nfrom the server when the restore finishes.',
                os.path.join(OUT, '05-private.png'))
