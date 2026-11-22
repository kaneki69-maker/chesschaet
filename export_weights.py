"""Exports trained NumPy weights to piece_cnn.bin (see NativePieceNet.kt for the format) and
writes a parity fixture (features + logits for sample squares) used by the JVM tests.
Usage: export_weights.py model.npz out_dir"""
import sys, os, struct, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cnn, features


def main():
    src, out = sys.argv[1], sys.argv[2]
    p = dict(np.load(src))
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, 'piece_cnn.bin'), 'wb') as f:
        f.write(b'CCNN'); f.write(struct.pack('<ii', 1, len(cnn.ARCH)))
        for i in range(len(cnn.ARCH)):
            f.write(p[f'w{i}'].astype('<f4').tobytes()); f.write(p[f'b{i}'].astype('<f4').tobytes())
    print('wrote', os.path.getsize(os.path.join(out, 'piece_cnn.bin')), 'bytes')
    # parity fixture: 40 val squares (raw uint8 32x32x3) + expected logits
    d = np.load(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'data', 'test.npz'))
    rng = np.random.default_rng(3)
    idx = np.sort(rng.choice(len(d['x']), 40, replace=False))
    x = d['x'][idx]
    logits, _ = cnn.forward(p, features.features(x))
    feats = features.features(x)
    with open(os.path.join(out, 'parity.bin'), 'wb') as f:
        f.write(struct.pack('<i', len(idx)))
        for k in range(len(idx)):
            f.write(x[k].astype(np.uint8).tobytes())          # 3072 bytes, [y][x][c]
            f.write(feats[k].astype('<f4').tobytes())          # 4096 floats
            f.write(logits[k].astype('<f4').tobytes())         # 13 floats
    print('parity fixture for', len(idx), 'squares')


main()
