# -*- coding: utf-8 -*-
"""编译 Windows 版 gnugo 冒烟测试程序. 用法: python build_win_smoke.py [-O0|-O2|-O0san]"""
import subprocess, sys, os

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, 'gnugo-3.8')
BUILD_HOST = os.path.join(ROOT, 'build-host')
GEN = os.path.join(BUILD_HOST, 'gen')

ENGINE = '''aftermath board boardlib breakin cache clock combination dragon endgame
filllib fuseki genmove globals handicap hash influence interface matchpat montecarlo
move_reasons movelist optics oracle owl persistent printutils readconnect reading
semeai sgfdecide sgffile shapes showbord surround unconditional utils value_moves worm'''.split()

def build(out, opt_flags):
    zig = [sys.executable, '-m', 'ziglang', 'cc', '-target', 'x86_64-windows-gnu'] + opt_flags + [
        '-std=gnu89', '-w', '-fcommon', '-DHAVE_CONFIG_H',
        f'-I{BUILD_HOST}', f'-I{SRC}', f'-I{SRC}/engine', f'-I{SRC}/sgf', f'-I{SRC}/utils', f'-I{SRC}/patterns']
    srcs = ([f'{SRC}/engine/{n}.c' for n in ENGINE]
            + [f'{SRC}/sgf/{n}.c' for n in ['sgf_utils', 'sgfnode', 'sgftree']]
            + [f'{SRC}/utils/{n}.c' for n in ['getopt', 'getopt1', 'random', 'gg_utils', 'winsocket']]
            + [f'{SRC}/patterns/{n}.c' for n in ['connections', 'helpers', 'transform']]
            + [f'{GEN}/{n}.c' for n in ['apatterns', 'dpatterns', 'handipat', 'influence', 'barriers',
                'endgame', 'conn', 'fusekipat', 'aa_attackpat', 'owl_vital_apat', 'owl_attackpat',
                'owl_defendpat', 'fuseki9', 'fuseki13', 'fuseki19', 'josekidb', 'patterns', 'eyes', 'mcpat']]
            + [f'{SRC}/interface/{n}.c' for n in ['main', 'play_ascii', 'play_gmp', 'play_gtp',
                                                 'play_solo', 'play_test', 'gmp', 'gtp']])
    r = subprocess.run(zig + ['-o', out] + srcs + ['-lws2_32', '-lm'],
                       cwd=SRC, capture_output=True, text=True, errors='replace')
    print(os.path.basename(out), 'rc=', r.returncode)
    if r.returncode:
        print((r.stdout + r.stderr)[-1500:])

if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) > 1 else '-O2'
    if mode == '-O0':
        build(os.path.join(BUILD_HOST, 'gnugo-o0ns.exe'), ['-O0', '-fno-sanitize=all'])
    elif mode == '-O0san':
        build(os.path.join(BUILD_HOST, 'gnugo-o0san.exe'), ['-O0'])
    elif mode == '-O2safe':
        build(os.path.join(BUILD_HOST, 'gnugo-o2safe.exe'),
              ['-O2', '-fno-strict-aliasing', '-fwrapv', '-fno-sanitize=all'])
    else:
        build(os.path.join(BUILD_HOST, 'gnugo-win.exe'), ['-O2', '-fno-sanitize=all'])
