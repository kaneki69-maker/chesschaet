"""Generates train/val/test squares as uint8 RGB 32x32 using the same crop+tensorize path as the app."""
import sys, random, numpy as np, argparse, multiprocessing as mp
sys.path.insert(0, __file__.rsplit('/', 1)[0])
import synth, features

LABEL = {c: i for i, c in enumerate(synth.CLASSES)}


def one_board(args):
    seed, styles, jitter = args
    rng = random.Random(seed)
    style = rng.choice(styles)
    pos = synth.random_position(rng)
    flipped = False
    board = synth.draw_board(rng, pos, style)
    board = synth.degrade(board, rng)
    h, w = board.shape[:2]
    # crop error as produced by an imperfect board detector (up to ~jitter of the side, each edge)
    j = int(min(h, w) * jitter)
    dl, dt, dr, db = [rng.randint(-j, j) for _ in range(4)] if j > 0 else (0, 0, 0, 0)
    pad = j + 1
    big = np.pad(board, ((pad, pad), (pad, pad), (0, 0)), mode='edge')
    crop = big[pad + dt: pad + h + db, pad + dl: pad + w + dr]
    sq = features.tensorize_uint8(crop)
    y = np.array([LABEL[p] if p else 0 for p in pos], np.int8)
    return sq, y, np.full(64, synth.ALL_STYLES.index(style), np.int8)


def build(n_boards, seed0, styles, jitter, workers=2):
    with mp.Pool(workers) as pool:
        res = pool.map(one_board, [(seed0 + i, styles, jitter) for i in range(n_boards)], chunksize=16)
    return (np.concatenate([r[0] for r in res]), np.concatenate([r[1] for r in res]), np.concatenate([r[2] for r in res]))


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True); ap.add_argument('--boards', type=int, required=True)
    ap.add_argument('--seed', type=int, required=True); ap.add_argument('--styles', default='all')
    ap.add_argument('--jitter', type=float, default=0.012)
    a = ap.parse_args()
    styles = synth.ALL_STYLES if a.styles == 'all' else a.styles.split(',')
    x, y, s = build(a.boards, a.seed, styles, a.jitter)
    np.savez_compressed(a.out, x=x, y=y, style=s)
    print(a.out, x.shape, np.bincount(y.astype(int), minlength=13))
