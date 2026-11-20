package com.sijia.go;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 五子棋棋谱读写.
 *
 * 格式：SGF FF[4] 子集，棋种 GM[4]（Gomoku / Renju），文件后缀 .gmk，UTF-8 纯文本：
 *
 * (;GM[4]FF[4]CA[UTF-8]AP[QingYi-Android:1.2.4]SZ[19]RU[freestyle]DT[2026-11-09]
 * PB[黑方]PW[白方]RE[B+5]
 * ;B[jj]
 * ;W[kk]
 * )
 *
 * 坐标沿用 SGF：首字母 a..s = 列（左→右），次字母 a..s = 行（上→下），(9,9) 即 jj。
 * RU[freestyle] 无禁手 / RU[renju] 黑棋禁手；RE 为结果（B+5 / W+5），未终局可省略。
 * 该文件可逐手回放，也可直接用任意 SGF 阅读器查看。
 */
public final class GomokuRecord {
    public static final String EXT = ".gmk";
    /** 固定头：GM[4] 表示五子棋/连珠. */
    public static final String HEADER = "GM[4]";

    public static class Data {
        public int size = GomokuGame.SIZE;
        public boolean renju;
        public String date = "";
        public String result = "";
        /** {color, x, y}，按落子顺序. */
        public final List<int[]> moves = new ArrayList<>();
    }

    private GomokuRecord() {}

    // ---------------- 保存 ----------------

    public static String toSgf(GomokuGame g, String blackName, String whiteName, String result) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("(;GM[4]FF[4]CA[UTF-8]AP[QingYi-Android:").append(BuildConfig.VERSION_NAME)
                .append("]SZ[").append(g.size()).append(']')
                .append("RU[").append(g.isRenju() ? "renju" : "freestyle").append(']')
                .append("DT[").append(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date())).append(']')
                .append("PB[").append(esc(blackName)).append(']')
                .append("PW[").append(esc(whiteName)).append(']');
        if (result != null && !result.isEmpty()) sb.append("RE[").append(esc(result)).append(']');
        sb.append('\n');
        List<int[]> moves = g.history();
        for (int[] m : moves) {
            sb.append(';').append(m[0] == GomokuGame.BLACK ? 'B' : 'W')
                    .append('[').append(coord(m[1], m[2])).append("]\n");
        }
        sb.append(")\n");
        return sb.toString();
    }

    public static File saveToFile(GomokuGame g, String blackName, String whiteName,
                                  String result, File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录: " + dir);
        String name = "QYG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + EXT;
        File f = new File(dir, name);
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(toSgf(g, blackName, whiteName, result));
        }
        return f;
    }

    // ---------------- 读取 ----------------

    public static Data load(File f) throws IOException {
        String text = readAll(f);
        Data d = new Data();
        int sz = 19;
        try {
            String v = prop(text, "SZ").trim();
            if (!v.isEmpty()) sz = Integer.parseInt(v);
        } catch (Exception ignored) {
        }
        d.size = Math.max(5, Math.min(25, sz));
        d.renju = prop(text, "RU").toLowerCase(Locale.US).contains("renju");
        d.date = prop(text, "DT");
        d.result = prop(text, "RE");

        int n = text.length();
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) != ';' || i + 2 >= n) continue;
            char col = Character.toUpperCase(text.charAt(i + 1));
            if (col != 'B' && col != 'W') continue;
            if (text.charAt(i + 2) != '[') continue;
            int end = text.indexOf(']', i + 3);
            if (end < 0) break;
            String v = text.substring(i + 3, end);
            i = end;
            if (v.length() < 2 || v.charAt(0) == ']') continue;
            int x = v.charAt(0) - 'a';
            int y = v.charAt(1) - 'a';
            if (x < 0 || y < 0 || x >= d.size || y >= d.size) continue;
            d.moves.add(new int[]{col == 'B' ? GomokuGame.BLACK : GomokuGame.WHITE, x, y});
        }
        return d;
    }

    /** 目录下所有 .gmk，按文件名倒序（新存的前面）. */
    public static File[] list(File dir) {
        File[] all = dir.listFiles();
        if (all == null) return new File[0];
        List<File> out = new ArrayList<>();
        for (File f : all) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(EXT)) out.add(f);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return b.getName().compareTo(a.getName());
            }
        });
        return out.toArray(new File[0]);
    }

    // ---------------- 工具 ----------------

    private static String coord(int x, int y) {
        return new String(new char[]{(char) ('a' + x), (char) ('a' + y)});
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("]", "\\]");
    }

    /** 读取 SGF 根属性（取第一次出现）. */
    private static String prop(String text, String key) {
        int from = 0;
        while (true) {
            int i = text.indexOf(key + "[", from);
            if (i < 0) return "";
            boolean boundary = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
            if (!boundary) {
                from = i + key.length();
                continue;
            }
            StringBuilder sb = new StringBuilder();
            int j = i + key.length() + 1;
            while (j < text.length()) {
                char c = text.charAt(j);
                if (c == '\\' && j + 1 < text.length()) {
                    sb.append(text.charAt(j + 1));
                    j += 2;
                    continue;
                }
                if (c == ']') break;
                sb.append(c);
                j++;
            }
            return sb.toString();
        }
    }

    private static String readAll(File f) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(64, f.length()));
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        byte[] raw = bos.toByteArray();
        if (raw.length >= 3 && raw[0] == (byte) 0xEF && raw[1] == (byte) 0xBB && raw[2] == (byte) 0xBF) {
            return new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8);
        }
        return new String(raw, StandardCharsets.UTF_8);
    }
}
