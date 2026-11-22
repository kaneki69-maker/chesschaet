"""Renders consecutive positions of a legal game as phone-like screenshots for the end-to-end JVM test.
Usage: make_fixtures.py game.txt out_dir name style flipped first_ply count seed"""
import sys, os, json, random, numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import synth


def fen_to_squares(fen):
    rows = fen.split()[0].split('/'); sq = []
    for r in rows:
        for c in r:
            if c.isdigit(): sq += [None] * int(c)
            else: sq.append(c)
    return sq


def main():
    game, out, name, style, flipped, first, count, seed = sys.argv[1:9]
    flipped = flipped == '1'; first = int(first); count = int(count); seed = int(seed)
    fens = [l.strip() for l in open(game) if l.strip()]
    os.makedirs(out, exist_ok=True)
    meta = {'name': name, 'style': style, 'flipped': flipped, 'frames': []}
    for k in range(count):
        fen = fens[first + k]
        sq = fen_to_squares(fen)
        view = sq[::-1] if flipped else sq          # what is displayed top-left -> bottom-right
        board = synth.draw_board(random.Random(seed), view, style, sq=64)
        board = synth.degrade(board, random.Random(seed + 1))   # same artefacts for every frame of a game
        shot, (l, t, s) = synth.make_screenshot(random.Random(seed + 2), board, W=540, H=1170)
        fn = f'{name}_{k:02d}.png'
        cv2.imwrite(os.path.join(out, fn), shot[..., ::-1])
        meta['frames'].append({'file': fn, 'fen': fen, 'left': l, 'top': t, 'side': s, 'width': 540, 'height': 1170})
    json.dump(meta, open(os.path.join(out, name + '.json'), 'w'), indent=1)
    print(name, count, 'frames')

main()
