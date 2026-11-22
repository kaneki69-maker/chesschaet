import sys, time, json, argparse, numpy as np
sys.path.insert(0, __file__.rsplit('/', 1)[0])
import cnn, features, synth


def load(path):
    d = np.load(path); return d['x'], d['y'].astype(np.int64), d['style'].astype(np.int64)


def evaluate(p, x, y, bs=512):
    preds = []; probs = []
    for i in range(0, len(x), bs):
        out, _ = cnn.forward(p, features.features(x[i:i + bs])); pr = cnn.softmax(out); probs.append(pr); preds.append(pr.argmax(1))
    return np.concatenate(preds), np.concatenate(probs)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--train', required=True); ap.add_argument('--val', required=True); ap.add_argument('--out', required=True)
    ap.add_argument('--holdout', default=''); ap.add_argument('--epochs', type=int, default=8); ap.add_argument('--bs', type=int, default=128)
    ap.add_argument('--lr', type=float, default=3e-3); ap.add_argument('--empty_keep', type=float, default=0.4); ap.add_argument('--seed', type=int, default=0)
    a = ap.parse_args()
    rng = np.random.default_rng(a.seed)
    x, y, s = load(a.train)
    hold = [synth.ALL_STYLES.index(h) for h in a.holdout.split(',') if h]
    keep = ~np.isin(s, hold)
    keep &= (y != 0) | (rng.random(len(y)) < a.empty_keep)
    x, y = x[keep], y[keep]
    vx, vy, vs = load(a.val)
    vkeep = ~np.isin(vs, hold); vx, vy = vx[vkeep], vy[vkeep]
    print(f'train {len(x)} val {len(vx)} holdout={a.holdout}', flush=True)
    p = cnn.init_params(rng); opt = cnn.Adam(p, lr=a.lr)
    n = len(x); steps = a.epochs * (n // a.bs); step = 0; best = (-1, None)
    for ep in range(a.epochs):
        perm = rng.permutation(n); t0 = time.time(); tl = 0; nb = 0
        for i in range(0, n - a.bs + 1, a.bs):
            idx = perm[i:i + a.bs]
            f = features.features(x[idx])
            f += rng.normal(0, 0.02, f.shape).astype(np.float32)
            out, cache = cnn.forward(p, f, True, 0.25, rng)
            pr = cnn.softmax(out)
            eps = 0.05; tgt = np.full_like(pr, eps / 12); tgt[np.arange(len(idx)), y[idx]] = 1 - eps
            loss = -(tgt * np.log(pr + 1e-9)).sum(1).mean()
            g = cnn.backward(p, cache, (pr - tgt) / len(idx))
            lr = a.lr * 0.5 * (1 + np.cos(np.pi * step / steps)) + 1e-5
            opt.step(p, g, lr); step += 1; tl += loss; nb += 1
        pv, _ = evaluate(p, vx, vy); acc = (pv == vy).mean()
        print(f'epoch {ep + 1}/{a.epochs} loss {tl / nb:.4f} val_acc {acc:.4f} ({time.time() - t0:.0f}s)', flush=True)
        if acc >= best[0]:
            best = (acc, {k: v.copy() for k, v in p.items()})
            np.savez(a.out, **best[1])  # checkpoint: a stopped run still leaves usable weights
    np.savez(a.out, **best[1])
    json.dump({'val_acc': float(best[0]), 'holdout': a.holdout, 'train_squares': int(n), 'epochs': a.epochs}, open(a.out + '.json', 'w'))
    print('saved', a.out, 'best val', best[0])
