package com.sijia.go;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * 围棋引擎 GTP 通信层.
 * 引擎以独立进程运行, 通过管道走 GTP 文本协议.
 * 支持双引擎: GNU Go 3.8(经典) / Pachi 12.90(MCTS 快棋).
 */
public class GtpEngine {
    private static final String TAG = "GtpEngine";
    public static final int ENGINE_GNU = 0;
    public static final int ENGINE_PACHI = 1;
    public static final int ENGINE_KATAGO = 2;

    private Process proc;
    private BufferedReader reader;
    private BufferedWriter writer;
    private int engineId = ENGINE_GNU;

    public static String engineName(int id) {
        return id == ENGINE_KATAGO ? "KataGo" : id == ENGINE_PACHI ? "Pachi" : "GNU Go";
    }

    /**
     * 返回引擎可执行文件路径.
     * 引擎以 libXxx.so 名义打包在 jniLibs, 由系统释放到 nativeLibraryDir;
     * 该目录带可执行标签, 可绕过 targetSdk 29+ 对 app 私有目录 exec 的 SELinux 限制.
     */
    public static String install(Context ctx, int engine) throws Exception {
        String so = engine == ENGINE_KATAGO ? "libkatago.so" : engine == ENGINE_PACHI ? "libpachi.so" : "libgnugo.so";
        File exe = new File(ctx.getApplicationInfo().nativeLibraryDir, so);
        if (!exe.exists()) throw new Exception("引擎文件缺失: " + exe.getAbsolutePath());
        exe.setExecutable(true, true);
        return exe.getAbsolutePath();
    }

    /** KataGo 模型与配置从 assets 释放到 filesDir/katago, 返回 [模型路径, 配置路径] */
    private static String[] installKatagoData(Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), "katago");
        dir.mkdirs();
        File model = new File(dir, "model.bin");
        File cfg = new File(dir, "gtp.cfg");
        if (model.length() == 0) copyAsset(ctx, "katago/model.bin", model);
        if (cfg.length() == 0) copyAsset(ctx, "katago/gtp.cfg", cfg);
        return new String[]{model.getAbsolutePath(), cfg.getAbsolutePath()};
    }

    private static void copyAsset(Context ctx, String name, File out) throws Exception {
        try (InputStream in = ctx.getAssets().open(name);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
    }

    /** Pachi 模式数据(定式/模式库)从 assets 释放到 filesDir/pachi, 返回数据目录 */
    private static File installPachiData(Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), "pachi");
        String[] files = {"patterns_mm.gamma", "patterns_mm.spat", "joseki19.gtp"};
        boolean ok = dir.isDirectory();
        if (ok) for (String f : files) ok &= new File(dir, f).length() > 0;
        if (!ok) {
            dir.mkdirs();
            for (String f : files) {
                try (InputStream in = ctx.getAssets().open("pachi/" + f);
                     FileOutputStream fos = new FileOutputStream(new File(dir, f))) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
            }
        }
        return dir;
    }

    public synchronized void start(Context ctx, int engine, int level) throws Exception {
        stop();
        this.engineId = engine;
        String path = install(ctx, engine);
        ProcessBuilder pb;
        if (engine == ENGINE_KATAGO) {
            // 棋力用 maxVisits 控制: level 1~10 -> 50~500 visits(实测 500v ≈ 11s/手)
            String[] km = installKatagoData(ctx);
            pb = new ProcessBuilder(path, "gtp", "-model", km[0], "-config", km[1],
                    "-override-config", "maxVisits=" + (Math.max(1, level) * 50) + ",numSearchThreads=4");
        } else if (engine == ENGINE_PACHI) {
            // 固定模拟次数控制棋力/速度: level 1~10 -> 1000~10000 playouts
            File dataDir = installPachiData(ctx);
            pb = new ProcessBuilder(path, "-d0", "-t", "=" + (Math.max(1, level) * 1000), "threads=4");
            pb.environment().put("DATA_DIR", dataDir.getAbsolutePath());
        } else {
            pb = new ProcessBuilder(path, "--mode", "gtp", "--quiet", "--level", String.valueOf(level));
        }
        pb.redirectErrorStream(false);
        proc = pb.start();
        reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
        String v = send("version");
        Log.i(TAG, engineName(engine) + " version: " + v);
    }

    /** 发送一条 GTP 命令并返回响应内容(去掉 = 前缀和空行) */
    public synchronized String send(String cmd) throws Exception {
        if (proc == null) throw new IllegalStateException("engine not started");
        writer.write(cmd + "\n");
        writer.flush();
        StringBuilder sb = new StringBuilder();
        String line;
        boolean started = false;
        while ((line = reader.readLine()) != null) {
            if (!started) {
                if (line.isEmpty()) continue;
                started = true;
                if (line.startsWith("=")) line = line.substring(1);
                else if (line.startsWith("?")) throw new Exception("GTP error: " + line);
                line = line.trim();
                if (!line.isEmpty()) sb.append(line);
                continue;
            }
            if (line.isEmpty()) break;  // 空行结束
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    public void boardsize(int n) throws Exception { send("boardsize " + n); }
    public void komi(double k) throws Exception { send("komi " + k); }

    /** GNU Go 专用: Pachi 无 level 命令, 棋力由启动参数 -t =N 决定 */
    public void level(int l) throws Exception {
        if (engineId == ENGINE_GNU) send("level " + l);
    }

    public void clearBoard() throws Exception { send("clear_board"); }

    /** 设置让子, 返回坐标列表(如 "D4 Q16") */
    public String fixedHandicap(int n) throws Exception { return send("fixed_handicap " + n); }

    public void play(boolean black, String vertex) throws Exception {
        send("play " + (black ? "black " : "white ") + vertex);
    }

    public String genmove(boolean black) throws Exception {
        return send("genmove " + (black ? "black" : "white")).trim();
    }

    public void undo() throws Exception { send("undo"); }

    /** 形势判断: GNU 用 estimate_score, Pachi 方言为 score_est, KataGo 用 final_score(返回 W+13.3 格式) */
    public String estimateScore() throws Exception {
        if (engineId == ENGINE_KATAGO) return send("final_score");
        return send(engineId == ENGINE_PACHI ? "score_est" : "estimate_score");
    }

    public String finalScore() throws Exception { return send("final_score"); }

    public synchronized void stop() {
        if (proc != null) {
            try { send("quit"); } catch (Exception ignored) {}
            proc.destroy();
            proc = null;
        }
    }
}
