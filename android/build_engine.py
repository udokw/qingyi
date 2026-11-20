# -*- coding: utf-8 -*-
"""
清弈 Android 移植 - GNU Go 3.8 引擎构建脚本
流程:
  1. zig cc 编译宿主机工具 (mkpat/mkeyes/joseki/mkmcpat/uncompress_fuseki)
  2. 运行工具生成 patterns 源文件
  3. NDK clang 交叉编译 Android 可执行文件 (arm64-v8a + armeabi-v7a)
"""
import os, subprocess, sys, glob, shutil

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "gnugo-3.8")
BUILD_HOST = os.path.join(ROOT, "build-host")
GEN = os.path.join(BUILD_HOST, "gen")
OUT = os.path.join(ROOT, "app", "src", "main", "jniLibs")

NDK = r"G:\Users\狂客\AppData\Local\Android\Sdk\ndk\28.2.13676358"
CLANG_DIR = os.path.join(NDK, "toolchains", "llvm", "prebuilt", "windows-x86_64", "bin")
API = 28

def run(cmd, cwd=None):
    print(">>", " ".join(str(c) for c in cmd) if isinstance(cmd, list) else cmd)
    r = subprocess.run(cmd, cwd=cwd)
    if r.returncode != 0:
        raise SystemExit(f"FAILED ({r.returncode}): {cmd}")

def run_shell(cmdline, cwd, stdout_file=None):
    """cmd /c 执行, 支持重定向"""
    print(">>", cmdline)
    fo = open(stdout_file, "wb") if stdout_file else None
    try:
        r = subprocess.run(f'cmd /c "{cmdline}"', cwd=cwd, stdout=fo)
    finally:
        if fo:
            fo.close()
    if r.returncode != 0:
        raise SystemExit(f"FAILED ({r.returncode}): {cmdline}")

# ---------- 源文件清单 ----------
UTILS = ["getopt.c", "getopt1.c", "random.c", "gg_utils.c", "winsocket.c"]
SGF = ["sgf_utils.c", "sgfnode.c", "sgftree.c"]
ENGINE = """aftermath board boardlib breakin cache clock combination dragon endgame
filllib fuseki genmove globals handicap hash influence interface matchpat montecarlo
move_reasons movelist optics oracle owl persistent printutils readconnect reading
semeai sgfdecide sgffile shapes showbord surround unconditional utils value_moves worm""".split()
BOARD = ["board.c", "boardlib.c", "hash.c", "printutils.c"]  # engine 子集
IFACE = ["main.c", "play_ascii.c", "play_gmp.c", "play_gtp.c",
         "play_solo.c", "play_test.c", "gmp.c", "gtp.c"]

def srcs(sub, names):
    return [os.path.join(SRC, sub, n if n.endswith(".c") else n + ".c") for n in names]

INCLUDES = [f"-I{BUILD_HOST}", f"-I{SRC}", f"-I{SRC}/engine",
            f"-I{SRC}/sgf", f"-I{SRC}/utils", f"-I{SRC}/patterns"]

CFLAGS_HOST = ["-O2", "-std=gnu89", "-w", "-fcommon", "-DHAVE_CONFIG_H"]

# ---------- config.h ----------
def write_config(path, long_size, is_windows):
    # 注意: 必须严格模仿 #cmakedefine 语义 —— false 的宏完全不能出现,
    # 因为 gnugo 源码用 #ifdef 判断, #define XXX 0 会被当作开启!
    vals = {
        "HAVE_SYS_TIME_H": 1, "HAVE_SYS_TYPES_H": 1, "TIME_WITH_SYS_TIME": 1,
        "HAVE_UNISTD_H": 1, "HAVE_VSNPRINTF": 1, "SIZEOF_LONG": long_size,
    }
    if is_windows:
        vals = dict(vals)
        vals.pop("HAVE_SYS_TIMES_H", None)
    else:
        vals["HAVE_SYS_TIMES_H"] = 1
        vals["HAVE_TIMES"] = 1
        vals["HAVE_USLEEP"] = 1
        vals["HAVE_GETTIMEOFDAY"] = 1
    lines = ["#define CHINESE_RULES 0", "#define RESIGNATION_ALLOWED 1",
             "#define DEFAULT_LEVEL 10", "#define COSMIC_GNUGO 0",
             "#define OWL_NODE_LIMIT 1000", "#define SEMEAI_NODE_LIMIT 500",
             "#define DEFAULT_MEMORY -1", "#define EXPERIMENTAL_OWL_EXT 0",
             "#define LARGE_SCALE 0", "#define ORACLE 0", "#define OWL_THREATS 0",
             "#define USE_BREAK_IN 1", "#define EXPERIMENTAL_CONNECTIONS 1",
             "#define ALTERNATE_CONNECTIONS 1", "#define GRID_OPT 1",
             '#define VERSION "3.8"']
    lines += [f"#define {k} {v}" for k, v in vals.items()]
    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")

# ---------- 阶段1: 宿主机工具 ----------
def build_host_tools():
    os.makedirs(BUILD_HOST, exist_ok=True)
    write_config(os.path.join(BUILD_HOST, "config.h"), 4, True)
    ZIG = [sys.executable, "-m", "ziglang", "cc",
           "-target", "x86_64-windows-gnu"] + CFLAGS_HOST + INCLUDES

    tools = {
        "mkpat": srcs("patterns", ["mkpat.c", "transform.c", "dfa.c"]) + srcs("utils", UTILS),
        "mkeyes": srcs("patterns", ["mkeyes.c"]) + srcs("utils", UTILS),
        "joseki": srcs("patterns", ["joseki.c"]) + srcs("engine", BOARD)
                  + srcs("sgf", SGF) + srcs("utils", UTILS),
        "uncompress_fuseki": srcs("patterns", ["uncompress_fuseki.c"])
                   + srcs("utils", UTILS) + srcs("engine", BOARD) + srcs("sgf", SGF),
    }
    for name, files in tools.items():
        out = os.path.join(BUILD_HOST, name + ".exe")
        if os.path.exists(out):
            print("skip", name)
            continue
        run(ZIG + ["-o", out] + files + ["-lws2_32", "-lm"])

    # mkmcpat 链接整个 engine, 直接链 .c 会要求解析 engine 中对生成文件的符号引用
    # (pat_db/owl_attackpat_db 等, 鸡生蛋问题). 先归档成静态库让链接器按需拉取.
    out = os.path.join(BUILD_HOST, "mkmcpat.exe")
    if not os.path.exists(out):
        objdir = os.path.join(BUILD_HOST, "obj")
        os.makedirs(objdir, exist_ok=True)
        lib_srcs = (srcs("engine", ENGINE) + srcs("sgf", SGF) + srcs("utils", UTILS))
        objs = []
        for s in lib_srcs:
            o = os.path.join(objdir, os.path.basename(s) + ".o")
            objs.append(o)
            if not os.path.exists(o):
                run(ZIG + ["-c", s, "-o", o])
        lib = os.path.join(BUILD_HOST, "libgnugo_host.a")
        run([sys.executable, "-m", "ziglang", "ar", "rcs", lib] + objs)
        run(ZIG + ["-o", out] + srcs("patterns", ["mkmcpat.c"])
            + [lib, "-lws2_32", "-lm"])

# ---------- 阶段2: 生成 patterns 源 ----------
def gen_patterns():
    os.makedirs(GEN, exist_ok=True)
    P = os.path.join(SRC, "patterns")
    def exe(n): return os.path.join(BUILD_HOST, n + ".exe")
    def g(n): return os.path.join(GEN, n)

    jobs = [
        # (工具参数, 输出文件)
        (f'"{exe("mkeyes")}"', "eyes.c", True),                     # stdin/stdout 特殊处理
    ]
    # mkeyes < eyes.db > eyes.c
    if not os.path.exists(g("eyes.c")):
        with open(os.path.join(P, "eyes.db"), "rb") as fi, open(g("eyes.c"), "wb") as fo:
            r = subprocess.run([exe("mkeyes")], cwd=P, stdin=fi, stdout=fo)
            assert r.returncode == 0

    mkpat_jobs = [
        ("-X attpat", "attack.db", None, "apatterns.c"),
        ("defpat", "defense.db", None, "dpatterns.c"),
        ("-b handipat", "handicap.db", None, "handipat.c"),
        ("-c influencepat", "influence.db", None, "influence.c"),
        ("-c -b barrierspat", "barriers.db", None, "barriers.c"),
        ("-b endpat", "endgame.db", None, "endgame.c"),
        ("-c conn", "conn.db", None, "conn.c"),
        ("-b fusekipat", "fuseki.db", None, "fusekipat.c"),
        ("-D -m -b aa_attackpat", "aa_attackpats.db", "aa_attackpats.dtr", "aa_attackpat.c"),
        ("-D -m -b owl_vital_apat", "owl_vital_apats.db", "owl_vital_apats.dtr", "owl_vital_apat.c"),
        ("-D -m -b owl_attackpat", "owl_attackpats.db", "owl_attackpats.dtr", "owl_attackpat.c"),
        ("-D -m -b owl_defendpat", "owl_defendpats.db", "owl_defendpats.dtr", "owl_defendpat.c"),
    ]
    for opts, db, dtr, out in mkpat_jobs:
        if os.path.exists(g(out)):
            continue
        args = [exe("mkpat")] + opts.split()
        if dtr:
            args += ["-t", os.path.join(P, dtr)]
        args += ["-i", os.path.join(P, db), "-o", g(out)]
        run(args, cwd=P)

    # patterns.c (两个输入)
    if not os.path.exists(g("patterns.c")):
        run([exe("mkpat"), "-b", "pat",
             "-i", os.path.join(P, "patterns.db"),
             "-i", os.path.join(P, "patterns2.db"),
             "-o", g("patterns.c")], cwd=P)

    # fuseki
    for n in (9, 13, 19):
        if not os.path.exists(g(f"fuseki{n}.c")):
            with open(g(f"fuseki{n}.c"), "wb") as fo:
                r = subprocess.run([exe("uncompress_fuseki"), str(n),
                                    os.path.join(P, f"fuseki{n}.dbz"), "c"],
                                   cwd=P, stdout=fo)
                assert r.returncode == 0

    # joseki db -> josekidb.c
    joseki_defs = [("gogo", "JG"), ("hoshi_keima", "JHK"), ("hoshi_other", "JHO"),
                   ("komoku", "JK"), ("sansan", "JS"), ("mokuhazushi", "JM"),
                   ("takamoku", "JT")]
    dbs = []
    for name, prefix in joseki_defs:
        dbf = g(name + ".db")
        dbs.append(dbf)
        if not os.path.exists(dbf):
            with open(dbf, "wb") as fo:
                r = subprocess.run([exe("joseki"), prefix,
                                    os.path.join(P, name + ".sgf")], cwd=P, stdout=fo)
                assert r.returncode == 0
    if not os.path.exists(g("josekidb.c")):
        run([exe("mkpat"), "-C", "joseki"] +
            sum((["-i", d] for d in dbs), []) +
            ["-o", g("josekidb.c")], cwd=P)

    # mcpat.c —— mkmcpat 只剥离 '/' 路径分隔符, Windows 下必须传纯文件名
    if not os.path.exists(g("mcpat.c")):
        with open(g("mcpat.c"), "wb") as fo:
            r = subprocess.run([exe("mkmcpat"),
                                "mc_montegnu_classic.db",
                                "mc_mogo_classic.db",
                                "mc_uniform.db"], cwd=P, stdout=fo)
            assert r.returncode == 0

# ---------- 阶段3: Android 交叉编译 ----------
def build_android():
    PAT_EXTRA = ["connections.c", "helpers.c", "transform.c"]
    GEN_C = ["apatterns.c", "dpatterns.c", "handipat.c", "influence.c",
             "barriers.c", "endgame.c", "conn.c", "fusekipat.c",
             "aa_attackpat.c", "owl_vital_apat.c", "owl_attackpat.c",
             "owl_defendpat.c", "fuseki9.c", "fuseki13.c", "fuseki19.c",
             "josekidb.c", "patterns.c", "eyes.c", "mcpat.c"]
    all_srcs = (srcs("engine", ENGINE) + srcs("sgf", SGF) + srcs("utils", UTILS)
                + srcs("patterns", PAT_EXTRA)
                + [os.path.join(GEN, c) for c in GEN_C]
                + srcs("interface", IFACE))

    targets = [
        ("arm64-v8a", f"aarch64-linux-android{API}-clang.cmd", 8),
        ("armeabi-v7a", f"armv7a-linux-androideabi{API}-clang.cmd", 4),
    ]
    for abi, clang, long_size in targets:
        write_config(os.path.join(BUILD_HOST, "config.h"), long_size, False)
        outdir = os.path.join(OUT, abi)
        os.makedirs(outdir, exist_ok=True)
        outfile = os.path.join(outdir, "libgnugo.so")
        if os.path.exists(outfile):
            print("skip", abi)
            continue
        cmd = f'cmd /c ""{os.path.join(CLANG_DIR, clang)}" -O2 -std=gnu89 -w -fcommon -DHAVE_CONFIG_H ' \
              + " ".join(f'"{i}"' for i in INCLUDES) + " -fPIE -pie " \
              + " ".join(f'"{s}"' for s in all_srcs) + f' -lm -o "{outfile}""'
        run_shell(cmd, cwd=SRC)
        print("built", outfile, os.path.getsize(outfile), "bytes")

if __name__ == "__main__":
    build_host_tools()
    gen_patterns()
    build_android()
    print("ALL DONE")
