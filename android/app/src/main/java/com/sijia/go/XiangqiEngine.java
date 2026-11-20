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
 * 象棋引擎 UCI 通信层, 支持两套引擎:
 *   FAIRY     = Fairy-Stockfish (UCI_Variant=xiangqi, UCI_Elo 棋力分级)
 *   PIKAFISH  = Pikafish (NNUE, 棋力最强, 用深度+时间分级)
 * 每步用完整 FEN 定位, 避免悔棋/跳转带来的着法序列错位.
 */
public class XiangqiEngine {
    private static final String TAG = "XiangqiEngine";
    public static final int ENGINE_FAIRY = 0;
    public static final int ENGINE_PIKAFISH = 1;

    private Process proc;
    private BufferedReader reader;
    private BufferedWriter writer;
    private int engineId = ENGINE_FAIRY;
    private int level = 5;

    public static String engineName(int id) {
        return id == ENGINE_PIKAFISH ? "Pikafish" : "Fairy-Stockfish";
    }

    public static String install(Context ctx, int engine) throws Exception {
        String so = engine == ENGINE_PIKAFISH ? "libpikafish.so" : "libfairy.so";
        File exe = new File(ctx.getApplicationInfo().nativeLibraryDir, so);
        if (!exe.exists()) throw new Exception("象棋引擎缺失: " + exe.getAbsolutePath());
        exe.setExecutable(true, true);
        return exe.getAbsolutePath();
    }

    /** Pikafish 评估网络释放到 filesDir/pikafish; Fairy 不需要网络 */
    private static String installPikafishNet(Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), "pikafish");
            dir.mkdirs();
            File net = new File(dir, "pikafish.nnue");
            if (net.length() == 0) {
                try (InputStream in = ctx.getAssets().open("pikafish/pikafish.nnue");
                     FileOutputStream fos = new FileOutputStream(net)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
            }
            return net.length() > 0 ? net.getAbsolutePath() : null;
        } catch (Exception e) {
            Log.w(TAG, "net extract skipped: " + e.getMessage());
            return null;
        }
    }

    public synchronized void start(Context ctx, int engine, int level) throws Exception {
        stop();
        this.engineId = engine;
        this.level = Math.max(1, Math.min(10, level));
        String path = install(ctx, engine);
        proc = new ProcessBuilder(path).redirectErrorStream(false).start();
        reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
        sendRaw("uci");
        if (!waitFor("uciok", 15000)) throw new Exception("引擎未响应 uciok");
        sendRaw("setoption name Threads value " + (this.level >= 4 ? 4 : 2));
        sendRaw("setoption name Hash value 64");
        sendRaw("setoption name Move Overhead value 50");
        sendRaw("setoption name Ponder value false");
        if (engine == ENGINE_FAIRY) {
            sendRaw("setoption name UCI_Variant value xiangqi");
            sendRaw("setoption name UCI_LimitStrength value true");
            sendRaw("setoption name UCI_Elo value " + eloForLevel(this.level));
        } else {
            String net = installPikafishNet(ctx);
            if (net != null) sendRaw("setoption name EvalFile value " + net);
        }
        sendRaw("isready");
        if (!waitFor("readyok", 30000)) throw new Exception("引擎未响应 readyok");
        Log.i(TAG, engineName(engine) + " ready, level=" + this.level);
    }

    /** Fairy 的棋力等级: 10 档 Elo, 覆盖入门到大师 */
    public static int eloForLevel(int level) {
        int[] elo = {600, 800, 1000, 1200, 1400, 1600, 1800, 2000, 2400, 2800};
        return elo[Math.max(0, Math.min(9, level - 1))];
    }

    /** Pikafish: 低等级靠限深自然变弱 */
    public int thinkDepth() {
        int[] d = {1, 2, 3, 4, 6, 8, 10, 12, 14, 18};
        return d[Math.max(0, Math.min(9, level - 1))];
    }

    /** level -> 思考时间, 兼顾手机性能与棋力 */
    public long thinkMillis() {
        long[] t = {120, 180, 250, 350, 500, 700, 900, 1200, 1600, 2200};
        return t[Math.max(0, Math.min(9, level - 1))];
    }

    public synchronized String bestMove(String fen) throws Exception {
        if (proc == null) throw new IllegalStateException("engine not started");
        sendRaw("position fen " + fen);
        if (engineId == ENGINE_FAIRY)
            sendRaw("go movetime " + thinkMillis());
        else
            sendRaw("go depth " + thinkDepth() + " movetime " + thinkMillis());
        String line;
        while ((line = readLine()) != null) {
            line = line.trim();
            if (line.startsWith("bestmove")) {
                String[] parts = line.split("\\s+");
                String mv = parts.length > 1 && !parts[1].equals("(none)") && !parts[1].equals("0000")
                        ? parts[1] : null;
                // Fairy 大棋盘用 rank 1-10; 统一成 rank 0-9 再交给棋局
                if (mv != null && engineId == ENGINE_FAIRY) mv = XiangqiGame.normalizeFairyUci(mv);
                return mv;
            }
        }
        return null;
    }

    private synchronized void sendRaw(String cmd) throws Exception {
        writer.write(cmd + "\n");
        writer.flush();
    }

    private String readLine() throws Exception {
        return reader.readLine();
    }

    private boolean waitFor(String token, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        String line;
        int shown = 0;
        while (System.currentTimeMillis() < end && (line = readLine()) != null) {
            if (shown < 4 || line.trim().equals(token)) {
                Log.d(TAG, "engine> " + line);
                shown++;
            }
            if (line.trim().equals(token)) return true;
        }
        return false;
    }

    public synchronized void stop() {
        if (proc != null) {
            try { sendRaw("quit"); } catch (Exception ignored) {}
            proc.destroy();
            proc = null;
        }
    }
}
