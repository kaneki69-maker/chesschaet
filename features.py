"""Python twin of BoardSquareTensorizer (Kotlin) + PieceFeatureExtractor (Kotlin). Must stay bit-compatible."""
import numpy as np

INPUT = 32
INSET = 0.06


def _bilinear_uint8(img, xs, ys):
    h, w = img.shape[:2]
    x = np.clip(xs, 0, w - 1).astype(np.float32); y = np.clip(ys, 0, h - 1).astype(np.float32)
    x0 = x.astype(np.int32); y0 = y.astype(np.int32)
    x1 = np.minimum(x0 + 1, w - 1); y1 = np.minimum(y0 + 1, h - 1)
    fx = (x - x0)[..., None]; fy = (y - y0)[..., None]
    c00 = img[y0, x0].astype(np.float32); c10 = img[y0, x1].astype(np.float32)
    c01 = img[y1, x0].astype(np.float32); c11 = img[y1, x1].astype(np.float32)
    top = c00 + (c10 - c00) * fx; bot = c01 + (c11 - c01) * fx
    return np.clip(np.trunc(top + (bot - top) * fy), 0, 255).astype(np.uint8)


def tensorize_uint8(board_rgb, flipped=False, out=INPUT, inset=INSET):
    """board_rgb: HxWx3 uint8 crop of the board. Returns (64, out, out, 3) uint8 in canonical a8..h1 order."""
    h, w = board_rgb.shape[:2]
    cw, ch = w / 8.0, h / 8.0
    span = 1.0 - 2 * inset
    o = (np.arange(out, dtype=np.float32) + 0.5) / out
    res = np.zeros((64, out, out, 3), np.uint8)
    for canon in range(64):
        view = 63 - canon if flipped else canon
        row, col = divmod(view, 8)
        xs = (col + inset) * cw + o * span * cw - 0.5
        ys = (row + inset) * ch + o * span * ch - 0.5
        X, Y = np.meshgrid(xs.astype(np.float32), ys.astype(np.float32))
        res[canon] = _bilinear_uint8(board_rgb, X, Y)
    return res


def features(sq_uint8):
    """sq_uint8: (N, 32, 32, 3) uint8 -> (N, 4, 32, 32) float32 background-relative features.
    ch0-2: (pixel - bg)/255*2 clipped to [-1,1]; ch3: absolute luma in [-1,1]. bg = per-channel median of the border ring."""
    n = sq_uint8.shape[0]
    x = sq_uint8.astype(np.float32)
    ring = np.concatenate([x[:, 0, :, :], x[:, -1, :, :], x[:, 1:-1, 0, :], x[:, 1:-1, -1, :]], axis=1)  # (N, 124, 3)
    bg = np.median(ring, axis=1)  # (N,3)
    diff = np.clip((x - bg[:, None, None, :]) / 255.0 * 2.0, -1.0, 1.0)
    luma = (0.2126 * x[..., 0] + 0.7152 * x[..., 1] + 0.0722 * x[..., 2]) / 127.5 - 1.0
    out = np.concatenate([diff, luma[..., None]], axis=3).astype(np.float32)
    return np.ascontiguousarray(out.transpose(0, 3, 1, 2))
