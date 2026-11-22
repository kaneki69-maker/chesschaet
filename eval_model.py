"""Per-class / per-style evaluation on test.npz. Usage: eval_model.py model.npz [--holdout name]"""
import sys, os, json, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cnn, features, synth, train

p = dict(np.load(sys.argv[1]))
x, y, s = train.load(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'data', 'test.npz'))
pred, prob = train.evaluate(p, x, y)
conf = prob.max(1)
res = {'overall_acc': float((pred == y).mean()), 'n': int(len(y))}
res['per_class_recall'] = {synth.CLASSES[c]: float((pred[y == c] == c).mean()) for c in range(13)}
res['per_style_acc'] = {synth.ALL_STYLES[k]: float((pred[s == k] == y[s == k]).mean()) for k in range(len(synth.ALL_STYLES)) if (s == k).any()}
res['piece_vs_empty'] = {'empty_called_piece': float(((pred != 0) & (y == 0)).sum() / (y == 0).sum()), 'piece_called_empty': float(((pred == 0) & (y != 0)).sum() / (y != 0).sum())}
res['color_confusions'] = int((((pred >= 1) & (pred <= 6) & (y >= 7)) | ((pred >= 7) & (y >= 1) & (y <= 6))).sum())
for th in (0.5, 0.6, 0.7, 0.8, 0.9):
    m = conf >= th
    res[f'thr{th}'] = {'coverage': float(m.mean()), 'acc_on_accepted': float((pred[m] == y[m]).mean())}
# whole-board exact accuracy (test.npz is stored board by board, 64 squares each)
nb = len(y) // 64
ok = (pred[:nb * 64].reshape(nb, 64) == y[:nb * 64].reshape(nb, 64)).all(1)
res['whole_board_exact'] = float(ok.mean()); res['boards'] = int(nb)
if len(sys.argv) > 2 and sys.argv[2] == '--holdout':
    k = synth.ALL_STYLES.index(sys.argv[3]); m = s == k
    res['holdout_style'] = {'style': sys.argv[3], 'acc': float((pred[m] == y[m]).mean()), 'n': int(m.sum())}
print(json.dumps(res, indent=1))
