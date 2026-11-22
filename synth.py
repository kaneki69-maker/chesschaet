"""Synthetic chess-board screenshot generator used to train/evaluate the piece classifier.

Piece artwork comes ONLY from assets available offline on a development machine:
  * unicode chess glyphs rendered from 4 font families (DejaVu Sans/Bold, FreeSerif, FreeMono, Unifont),
  * FontAwesome-solid and Material-Design chess icons (rendered with rsvg-convert).
That gives 7 visually distinct "piece sets". Real chess apps use other artwork, so accuracy measured here is
NOT a guarantee for real screenshots (see ML_MODEL_SPEC.md).
"""
import glob, io, os, subprocess, random
import numpy as np
import cv2
from PIL import Image, ImageDraw, ImageFont

CLASSES = ['empty', 'P', 'N', 'B', 'R', 'Q', 'K', 'p', 'n', 'b', 'r', 'q', 'k']
TYPE_ORDER = 'pnbrqk'
GLYPH = {'k': '♚', 'q': '♛', 'r': '♜', 'b': '♝', 'n': '♞', 'p': '♟'}
ICON_DIR = '/usr/local/lib/python3.13/dist-packages/material/templates/.icons'
FONT_STYLES = {
    'dejavu': '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
    'dejavu_bold': '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
    'freeserif': '/usr/share/fonts/truetype/freefont/FreeSerif.ttf',
    'freemono': '/usr/share/fonts/truetype/freefont/FreeMono.ttf',
    'unifont': '/usr/share/fonts/opentype/unifont/unifont.otf',
}
ICON_STYLES = {'fa_solid': 'fontawesome/solid', 'material': 'material'}
ALL_STYLES = list(FONT_STYLES) + list(ICON_STYLES)
BASE = 256  # master mask resolution
_mask_cache = {}


def _glyph_mask(path, ch):
    font = ImageFont.truetype(path, 200)
    img = Image.new('L', (BASE, BASE), 0)
    d = ImageDraw.Draw(img)
    bb = d.textbbox((0, 0), ch, font=font)
    w, h = bb[2] - bb[0], bb[3] - bb[1]
    scale = min(1.0, 220 / max(w, h))
    if scale < 1.0:
        font = ImageFont.truetype(path, int(200 * scale)); bb = d.textbbox((0, 0), ch, font=font); w, h = bb[2] - bb[0], bb[3] - bb[1]
    d.text(((BASE - w) // 2 - bb[0], (BASE - h) // 2 - bb[1]), ch, fill=255, font=font)
    return np.array(img)


def _icon_mask(folder, name):
    svg = os.path.join(ICON_DIR, folder, f'chess-{name}.svg')
    png = subprocess.run(['rsvg-convert', '-w', str(BASE), '-h', str(BASE), svg], capture_output=True, check=True).stdout
    img = np.array(Image.open(io.BytesIO(png)).convert('RGBA'))
    a = img[..., 3]
    ys, xs = np.nonzero(a > 8)
    crop = a[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    s = 220 / max(crop.shape)
    crop = cv2.resize(crop, (max(1, int(crop.shape[1] * s)), max(1, int(crop.shape[0] * s))), interpolation=cv2.INTER_AREA)
    out = np.zeros((BASE, BASE), np.uint8)
    y0, x0 = (BASE - crop.shape[0]) // 2, (BASE - crop.shape[1]) // 2
    out[y0:y0 + crop.shape[0], x0:x0 + crop.shape[1]] = crop
    return out


def mask(style, ptype):
    key = (style, ptype)
    if key not in _mask_cache:
        if style in FONT_STYLES:
            _mask_cache[key] = _glyph_mask(FONT_STYLES[style], GLYPH[ptype])
        else:
            names = {'p': 'pawn', 'n': 'knight', 'b': 'bishop', 'r': 'rook', 'q': 'queen', 'k': 'king'}
            _mask_cache[key] = _icon_mask(ICON_STYLES[style], names[ptype])
    return _mask_cache[key]


PRESET_THEMES = [((240, 217, 181), (181, 136, 99)), ((238, 238, 210), (118, 150, 86)), ((222, 227, 230), (140, 162, 173)),
                 ((220, 220, 220), (150, 150, 150)), ((239, 239, 239), (125, 135, 150)), ((234, 233, 210), (75, 115, 153)),
                 ((255, 206, 158), (209, 139, 71)), ((235, 236, 208), (115, 149, 82)), ((200, 200, 200), (100, 100, 100))]


def random_theme(rng):
    if rng.random() < 0.5:
        l, d = rng.choice(PRESET_THEMES)
        j = lambda c: tuple(int(np.clip(v + rng.randint(-14, 14), 0, 255)) for v in c)
        return j(l), j(d)
    import colorsys
    h = rng.random(); s1 = rng.uniform(0, .3); s2 = rng.uniform(.15, .6)
    l = colorsys.hsv_to_rgb(h, s1, rng.uniform(.8, .97)); d = colorsys.hsv_to_rgb((h + rng.uniform(-.05, .05)) % 1, s2, rng.uniform(.35, .72))
    return tuple(int(255 * v) for v in l), tuple(int(255 * v) for v in d)


def random_piece_colors(rng):
    white = [(250, 250, 250), (240, 235, 220), (255, 248, 235), (230, 230, 235), (245, 245, 245)]
    black = [(30, 30, 30), (55, 45, 40), (20, 20, 50), (70, 70, 75), (15, 15, 15), (45, 45, 45)]
    wf = rng.choice(white); bf = rng.choice(black)
    j = lambda c: tuple(int(np.clip(v + rng.randint(-8, 8), 0, 255)) for v in c)
    wo = tuple(rng.randint(10, 70) for _ in range(3))
    bo = rng.choice([tuple(rng.randint(0, 40) for _ in range(3)), tuple(rng.randint(150, 230) for _ in range(3))])
    return j(wf), wo, j(bf), bo


def render_piece(style, ch, size, colors, rng):
    """Returns RGBA float image (size x size) for a piece character like 'N' or 'p'."""
    wf, wo, bf, bo = colors
    white = ch.isupper()
    fill, outline = (wf, wo) if white else (bf, bo)
    m = mask(style, ch.lower())
    # random geometric jitter at master resolution
    ang = rng.uniform(-2.5, 2.5); sc = rng.uniform(0.84, 1.0); shear = rng.uniform(-0.04, 0.04)
    tx = rng.uniform(-0.03, 0.03) * BASE; ty = rng.uniform(-0.03, 0.03) * BASE
    M = cv2.getRotationMatrix2D((BASE / 2, BASE / 2), ang, sc); M[0, 1] += shear; M[0, 2] += tx; M[1, 2] += ty
    m = cv2.warpAffine(m, M, (BASE, BASE), flags=cv2.INTER_LINEAR)
    m = cv2.resize(m, (size, size), interpolation=cv2.INTER_AREA).astype(np.float32) / 255
    ow = max(1, int(round(size * rng.uniform(0.012, 0.035))))
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * ow + 1, 2 * ow + 1))
    outer = cv2.dilate(m, k)
    # body gradient (cheap shading)
    grad = np.linspace(1 + rng.uniform(0, .12), 1 - rng.uniform(0, .12), size)[:, None, None]
    body = np.clip(np.array(fill, np.float32)[None, None, :] * grad, 0, 255) * np.ones((size, size, 1), np.float32)
    rgb = body * m[..., None] + np.array(outline, np.float32)[None, None, :] * (outer - m)[..., None]
    a = outer
    # optional drop shadow
    return rgb, a


def draw_board(rng, position, style, sq=None, flipped=False):
    """position: list of 64 chars/None in a8..h1 order (board index). Returns uint8 RGB board image (without margin)."""
    sq = sq or rng.randint(44, 128)
    light, dark = random_theme(rng)
    colors = random_piece_colors(rng)
    tex = rng.random() < 0.35
    img = np.zeros((8 * sq, 8 * sq, 3), np.float32)
    hl = set(rng.sample(range(64), 2)) if rng.random() < 0.5 else set()
    hl_col = rng.choice([(255, 255, 0), (155, 199, 0), (20, 85, 30), (246, 246, 105), (100, 200, 255)])
    hl_alpha = rng.uniform(0.25, 0.5)
    dots = set(rng.sample(range(64), rng.randint(0, 6))) if rng.random() < 0.3 else set()
    coords = rng.random() < 0.5
    check_sq = rng.choice([i for i in range(64) if position[i] in ('K', 'k')] or [None]) if rng.random() < 0.1 else None
    nrng = np.random.RandomState(rng.randint(0, 2 ** 31))
    for i in range(64):
        r, c = divmod(i, 8)
        is_light = (r + c) % 2 == 0
        base = np.array(light if is_light else dark, np.float32)
        cell = np.ones((sq, sq, 3), np.float32) * base
        if tex:
            cell += nrng.normal(0, rng.uniform(1, 6), (sq, sq, 1)) + np.linspace(-6, 6, sq)[:, None, None] * rng.uniform(-1, 1)
        if i in hl:
            cell = cell * (1 - hl_alpha) + np.array(hl_col, np.float32) * hl_alpha
        if check_sq == i:
            yy, xx = np.mgrid[0:sq, 0:sq]; d = np.sqrt((yy - sq / 2) ** 2 + (xx - sq / 2) ** 2) / (sq / 2)
            a = np.clip(1 - d, 0, 1)[..., None] * 0.8
            cell = cell * (1 - a) + np.array((255, 40, 40), np.float32) * a
        p = position[i]
        if p:
            psize = max(8, int(sq * rng.uniform(0.82, 1.0)))
            rgb, a = render_piece(style, p, psize, colors, rng)
            ox = (sq - psize) // 2 + rng.randint(-int(sq * .02), int(sq * .02)); oy = (sq - psize) // 2 + rng.randint(-int(sq * .02), int(sq * .02))
            x0, y0 = max(0, ox), max(0, oy); x1, y1 = min(sq, ox + psize), min(sq, oy + psize)
            sub = rgb[y0 - oy:y1 - oy, x0 - ox:x1 - ox]; sa = a[y0 - oy:y1 - oy, x0 - ox:x1 - ox, None]
            cell[y0:y1, x0:x1] = cell[y0:y1, x0:x1] * (1 - sa) + sub * sa
        if i in dots:
            yy, xx = np.mgrid[0:sq, 0:sq]; d = np.sqrt((yy - sq / 2) ** 2 + (xx - sq / 2) ** 2) / sq
            a = ((d < 0.14) if not p else ((d > 0.4) & (d < 0.46)))[..., None] * 0.18
            cell = cell * (1 - a)
        if coords and (c == 0 or r == 7):
            tmp = Image.fromarray(np.clip(cell, 0, 255).astype(np.uint8)); dr = ImageDraw.Draw(tmp)
            f = ImageFont.truetype(FONT_STYLES['dejavu_bold'], max(7, sq // 6))
            col = tuple(int(v) for v in (dark if is_light else light))
            if c == 0: dr.text((int(sq * .05), int(sq * .02)), str(8 - r), fill=col, font=f)
            if r == 7: dr.text((int(sq * .78), int(sq * .70)), 'abcdefgh'[c], fill=col, font=f)
            cell = np.array(tmp, np.float32)
        img[r * sq:(r + 1) * sq, c * sq:(c + 1) * sq] = cell
    out = np.clip(img, 0, 255).astype(np.uint8)
    if flipped:
        out = out[::-1, ::-1].copy()
    return out


def degrade(img, rng):
    """Resize/blur/JPEG artefacts typical of phone screenshots."""
    s = rng.uniform(0.85, 1.12)
    h, w = img.shape[:2]
    out = cv2.resize(img, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA if s < 1 else cv2.INTER_LINEAR)
    if rng.random() < 0.4:
        out = cv2.GaussianBlur(out, (0, 0), rng.uniform(0.3, 0.9))
    if rng.random() < 0.3:
        ok, enc = cv2.imencode('.jpg', out[..., ::-1], [cv2.IMWRITE_JPEG_QUALITY, rng.randint(55, 95)])
        out = cv2.imdecode(enc, 1)[..., ::-1]
    return out


def random_position(rng):
    p_empty = rng.uniform(0.3, 0.8)
    pos = []
    for i in range(64):
        if rng.random() < p_empty: pos.append(None)
        else:
            c = rng.choice('PNBRQKpnbrqk')
            if c in 'Pp' and i // 8 in (0, 7): pos.append(None)
            else: pos.append(c)
    return pos


def make_screenshot(rng, board, W=None, H=None):
    """Embeds a board image into a phone-like screenshot with UI chrome. Returns (img, (left, top, side))."""
    bs = board.shape[0]
    W = W or max(int(bs * rng.uniform(1.0, 1.25)), 360)
    H = H or int(W * rng.uniform(1.6, 2.2))
    if bs > W: board = cv2.resize(board, (W, W), interpolation=cv2.INTER_AREA); bs = W
    bg = np.array([rng.randint(0, 255) for _ in range(3)], np.float32)
    img = np.ones((H, W, 3), np.float32) * bg
    # UI chrome: random coloured bars/gradients and text-like noise
    for _ in range(rng.randint(2, 7)):
        y0 = rng.randint(0, H - 20); h = rng.randint(10, max(11, H // 8)); x0 = rng.randint(0, W // 3); x1 = rng.randint(W // 2, W)
        img[y0:y0 + h, x0:x1] = np.array([rng.randint(0, 255) for _ in range(3)], np.float32)
    left = (W - bs) // 2 + rng.randint(-(W - bs) // 2, (W - bs) // 2 + 1) if W > bs else 0
    top = rng.randint(int(H * 0.15), max(int(H * 0.15) + 1, H - bs - int(H * 0.1)))
    border = rng.random() < 0.3
    if border:
        img[max(0, top - 4):top + bs + 4, max(0, left - 4):left + bs + 4] = np.array([rng.randint(0, 255) for _ in range(3)], np.float32)
    img[top:top + bs, left:left + bs] = board
    return np.clip(img, 0, 255).astype(np.uint8), (left, top, bs)
