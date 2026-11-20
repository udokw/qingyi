# -*- coding: utf-8 -*-
"""
Pachi 12.90 (Jowa) Android 交叉编译脚本
- 无 DCNN / 无 JOSEKIFIX / 无分布式, 纯 UCT+MCTS
- 产物改名 libpachi.so 放入 jniLibs, 由系统释放到 nativeLibraryDir 绕过 SELinux exec 限制
- 数据文件(patterns_mm.gamma/.spat, joseki19.gtp)复制到 assets, 运行时释放并设 DATA_DIR 环境变量
"""
import os, subprocess, sys, shutil
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "pachi-src")
BUILD = os.path.join(ROOT, "build-pachi")
OUT = os.path.join(ROOT, "app", "src", "main", "jniLibs")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "pachi")

NDK = r"G:\Users\狂客\AppData\Local\Android\Sdk\ndk\28.2.13676358"
CLANG_DIR = os.path.join(NDK, "toolchains", "llvm", "prebuilt", "windows-x86_64", "bin")
API = 28

# 无 DCNN 构建的完整源文件清单 (按各子目录 Makefile OBJS 整理)
SOURCES = [
    # 根目录
    "board.c", "board_undo.c", "engine.c", "gogui.c", "gtp.c", "move.c",
    "ownermap.c", "pachi.c", "pattern3.c", "playout.c", "random.c", "stone.c",
    "timeinfo.c", "fbook.c", "chat.c", "util.c",
    # uct
    "uct/dynkomi.c", "uct/tree.c", "uct/uct.c", "uct/prior.c", "uct/search.c", "uct/walk.c",
    # engines
    "engines/montecarlo.c", "engines/random.c", "engines/replay.c",
    # playout
    "playout/moggy.c", "playout/light.c",
    # tactics
    "tactics/dragon.c", "tactics/seki.c", "tactics/1lib.c", "tactics/2lib.c",
    "tactics/nlib.c", "tactics/ladder.c", "tactics/nakade.c", "tactics/selfatari.c",
    "tactics/bent4.c", "tactics/util.c",
    # pattern
    "pattern/pattern.c", "pattern/mcowner.c", "pattern/criticality.c",
    "pattern/pattern_engine.c", "pattern/prob.c", "pattern/spatial.c",
    # joseki
    "joseki/joseki.c", "joseki/joseki_engine.c", "joseki/josekiload.c",
    # uct/policy
    "uct/policy/generic.c", "uct/policy/ucb1.c", "uct/policy/ucb1amaf.c",
    # t-predict
    "t-predict/predict.c",
    # t-unit (gtp.c/pachi.c 引用 unit_test 入口)
    "t-unit/test.c",
]

DATAFILES = ["patterns_mm.gamma", "patterns_mm.spat", "joseki19.gtp"]

BUILD_H = r"""
#define PACHI_GIT_HASH "android"
#define PACHI_GIT_BRANCH "sijiago"
#define PACHI_BUILD_TARGET "android"
#define PACHI_BUILD_DATE __DATE__
#define PACHI_COMPILER "clang"
#define PACHI_CFLAGS "-O3"
#define PACHI_CC1 ""
"""

ARCHS = {
    "arm64-v8a":   "aarch64-linux-android",
    "armeabi-v7a": "armv7a-linux-androideabi",
}

def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print("FAILED:", " ".join(cmd))
        print(r.stdout[-3000:])
        print(r.stderr[-3000:])
        raise SystemExit(1)
    return r

def gen_build_h():
    d = os.path.join(BUILD, "gen")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "build.h"), "w") as f:
        f.write(BUILD_H)
    return d

def build_arch(abi, target, gen_dir):
    objdir = os.path.join(BUILD, "obj", abi)
    os.makedirs(objdir, exist_ok=True)
    cc = os.path.join(CLANG_DIR, f"{target}{API}-clang.cmd")
    cflags = ["-O3", "-fPIE", "-D_GNU_SOURCE", "-std=gnu99", "-pthread", "-w",
              "-I", SRC, "-I", gen_dir]
    objs = []
    def compile_one(rel):
        src = os.path.join(SRC, rel.replace("/", os.sep))
        obj = os.path.join(objdir, rel.replace("/", "_") + ".o")
        run([cc, *cflags, "-c", src, "-o", obj])
        return obj
    with ThreadPoolExecutor(max_workers=8) as ex:
        objs = list(ex.map(compile_one, SOURCES))
    out_dir = os.path.join(OUT, abi)
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, "libpachi.so")
    run([cc, *objs, "-pie", "-pthread", "-lm", "-o", out])
    print(f"[{abi}] -> {out} ({os.path.getsize(out)//1024} KB)")

def copy_datafiles():
    os.makedirs(ASSETS, exist_ok=True)
    for f in DATAFILES:
        shutil.copy2(os.path.join(SRC, f), os.path.join(ASSETS, f))
        print("asset:", f)

if __name__ == "__main__":
    gen_dir = gen_build_h()
    for abi, target in ARCHS.items():
        build_arch(abi, target, gen_dir)
    copy_datafiles()
    print("OK")
