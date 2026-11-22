"""Small CNN implemented in pure NumPy (forward + backward + Adam). No ML framework is available offline.

Architecture (input 4x32x32):
  conv3x3(4->12)+ReLU+maxpool2 -> conv3x3(12->24)+ReLU+maxpool2 -> conv3x3(24->32)+ReLU+maxpool2
  -> flatten(512) -> dense(64)+ReLU -> dense(13)
Weights layout for export: conv kernels are (out, in, 3, 3); dense weights are (in, out).
"""
import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

ARCH = [('conv', 4, 12), ('conv', 12, 24), ('conv', 24, 32), ('dense', 512, 64), ('dense', 64, 13)]


def init_params(rng):
    p = {}
    for i, (kind, cin, cout) in enumerate(ARCH):
        fan_in = cin * 9 if kind == 'conv' else cin
        shape = (cout, cin, 3, 3) if kind == 'conv' else (cin, cout)
        p[f'w{i}'] = (rng.standard_normal(shape) * np.sqrt(2.0 / fan_in)).astype(np.float32)
        p[f'b{i}'] = np.zeros(cout, np.float32)
    return p


def _im2col(x):  # x (N,C,H,W) -> (N*H*W, C*9) with padding 1
    n, c, h, w = x.shape
    xp = np.pad(x, ((0, 0), (0, 0), (1, 1), (1, 1)))
    win = sliding_window_view(xp, (3, 3), axis=(2, 3))  # (N,C,H,W,3,3)
    return win.transpose(0, 2, 3, 1, 4, 5).reshape(n * h * w, c * 9)


def _col2im(dcol, shape):  # inverse accumulate; dcol (N*H*W, C*9)
    n, c, h, w = shape
    d = dcol.reshape(n, h, w, c, 3, 3)
    dx = np.zeros((n, c, h + 2, w + 2), np.float32)
    for ky in range(3):
        for kx in range(3):
            dx[:, :, ky:ky + h, kx:kx + w] += d[:, :, :, :, ky, kx].transpose(0, 3, 1, 2)
    return dx[:, :, 1:-1, 1:-1]


def forward(p, x, train=False, drop=0.0, rng=None):
    cache = []
    a = x
    for i, (kind, cin, cout) in enumerate(ARCH):
        if kind == 'conv':
            n, c, h, w = a.shape
            col = _im2col(a)
            z = col @ p[f'w{i}'].reshape(cout, -1).T + p[f'b{i}']
            z = z.reshape(n, h, w, cout).transpose(0, 3, 1, 2)
            r = np.maximum(z, 0)
            pr = r.reshape(n, cout, h // 2, 2, w // 2, 2)
            pooled = pr.max(axis=(3, 5))
            mask = (pr == pooled[:, :, :, None, :, None])
            cache.append((col, a.shape, z, mask, pr.shape))
            a = pooled
        else:
            if a.ndim == 4:
                cache.append(('flat', a.shape)); a = a.reshape(a.shape[0], -1)
            z = a @ p[f'w{i}'] + p[f'b{i}']
            last = i == len(ARCH) - 1
            r = z if last else np.maximum(z, 0)
            dm = None
            if train and drop > 0 and not last:
                dm = (rng.random(r.shape) >= drop).astype(np.float32) / (1 - drop); r = r * dm
            cache.append((a, z, dm))
            a = r
    return a, cache


def backward(p, cache, dlogits):
    g = {}
    d = dlogits
    for i in reversed(range(len(ARCH))):
        kind, cin, cout = ARCH[i]
        if kind == 'dense':
            a, z, dm = cache[i + (1 if False else 0)] if False else cache_lookup(cache, i)
            last = i == len(ARCH) - 1
            if not last:
                if dm is not None: d = d * dm
                d = d * (z > 0)
            g[f'w{i}'] = a.T @ d; g[f'b{i}'] = d.sum(0)
            d = d @ p[f'w{i}'].T
        else:
            col, in_shape, z, mask, prs = cache_lookup(cache, i)
            n, c, h, w = in_shape
            if d.ndim == 2:
                d = d.reshape(shape_after_conv(i, n))
            # unpool: spread gradient to max positions (split ties evenly)
            cnt = mask.sum(axis=(3, 5), keepdims=True)
            dr = (mask / cnt) * d[:, :, :, None, :, None]
            dr = dr.reshape(n, cout, h, w)
            dz = dr * (z > 0)
            dz2 = dz.transpose(0, 2, 3, 1).reshape(n * h * w, cout)
            g[f'w{i}'] = (dz2.T @ col).reshape(cout, cin, 3, 3); g[f'b{i}'] = dz2.sum(0)
            if i > 0:
                dcol = dz2 @ p[f'w{i}'].reshape(cout, -1)
                d = _col2im(dcol, in_shape)
    return g


def cache_lookup(cache, i):
    # cache has one entry per layer plus a 'flat' marker before the first dense layer
    idx = i if i < 3 else i + 1
    return cache[idx]


def shape_after_conv(i, n):
    sizes = {0: (n, 12, 16, 16), 1: (n, 24, 8, 8), 2: (n, 32, 4, 4)}
    return sizes[i]


def softmax(z):
    z = z - z.max(axis=1, keepdims=True)
    e = np.exp(z); return e / e.sum(axis=1, keepdims=True)


class Adam:
    def __init__(self, p, lr=2e-3, b1=0.9, b2=0.999, wd=1e-4):
        self.m = {k: np.zeros_like(v) for k, v in p.items()}; self.v = {k: np.zeros_like(v) for k, v in p.items()}
        self.t = 0; self.lr, self.b1, self.b2, self.wd = lr, b1, b2, wd

    def step(self, p, g, lr=None):
        self.t += 1; lr = lr or self.lr
        for k in p:
            gk = g[k] + (self.wd * p[k] if k.startswith('w') else 0)
            self.m[k] = self.b1 * self.m[k] + (1 - self.b1) * gk
            self.v[k] = self.b2 * self.v[k] + (1 - self.b2) * gk * gk
            mh = self.m[k] / (1 - self.b1 ** self.t); vh = self.v[k] / (1 - self.b2 ** self.t)
            p[k] -= (lr * mh / (np.sqrt(vh) + 1e-8)).astype(np.float32)
