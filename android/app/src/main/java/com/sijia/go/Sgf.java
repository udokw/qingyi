package com.sijia.go;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * SGF(FF[4]) 读写: 支持根属性/主变落子/摆子/注释, 兼容常见棋谱站格式.
 * 变着(=分支)只读取主变(第一个分支), 保证导入棋谱按原谱顺序推进.
 */
public class Sgf {
    public static class Node {
        public int x = -1, y = -1;   // -1 表示 PASS 或无着子
        public int color;            // BLACK / WHITE, 0 表示纯属性节点
        public String comment;
    }

    public static class Game {
        public String event = "", blackName = "", whiteName = "";
        public String blackRank = "", whiteRank = "", result = "", date = "";
        public int size = 19, handicap = 0;
        public double komi = 7.5;
        public int playerToMove = 0;              // 0 未指定, 否则 GoGame.BLACK / WHITE
        public List<int[]> setupBlack = new ArrayList<>();
        public List<int[]> setupWhite = new ArrayList<>();
        public List<int[]> setupEmpty = new ArrayList<>();
        public List<Node> nodes = new ArrayList<>();
    }

    // ---------------- 保存 ----------------
    public static String save(GoGame g, String blackName, String whiteName, String result) {
        StringBuilder sb = new StringBuilder();
        sb.append("(;GM[1]FF[4]CA[UTF-8]SZ[").append(g.size()).append("]AP[清弈Android:1.1]");
        sb.append("PB[").append(esc(blackName)).append("]");
        sb.append("PW[").append(esc(whiteName)).append("]");
        sb.append("KM[").append(g.komi()).append("]");
        sb.append("DT[").append(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date())).append("]");
        if (result != null) sb.append("RE[").append(esc(result)).append("]");
        sb.append("\n");
        // 让子与着法: history 是栈(顶=最后一手), 需要倒序
        List<GoGame.Move> moves = g.history();
        java.util.Collections.reverse(moves);
        for (GoGame.Move m : moves) {
            if (m.color == 0) continue;
            sb.append(';').append(m.color == GoGame.BLACK ? 'B' : 'W').append('[');
            if (m.x >= 0) sb.append(GoGame.toSgf(m.x, m.y));
            sb.append("]\n");
        }
        sb.append(")\n");
        return sb.toString();
    }

    public static File saveToFile(GoGame g, String blackName, String whiteName, String result, File dir) throws Exception {
        dir.mkdirs();
        String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".sgf";
        File f = new File(dir, name);
        try (FileWriter w = new FileWriter(f, StandardCharsets.UTF_8)) {
            w.write(save(g, blackName, whiteName, result));
        }
        return f;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("]", "\\]");
    }

    // ---------------- 加载 ----------------
    public static Game load(File f) throws Exception {
        byte[] raw = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while ((n = in.read(raw, off, raw.length - off)) > 0) off += n;
        }
        return parse(decode(raw));
    }

    /** 从任意输入流(文件选择器 Uri)读取 */
    public static Game load(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return parse(decode(bos.toByteArray()));
    }

    /**
     * 编码识别: 先按 UTF-8 严格解码(BOM 直接剥掉), 失败再按 GBK/GB18030 解码.
     * 棋谱站大多 UTF-8, 老软件/旧谱多为 GBK.
     */
    public static String decode(byte[] raw) {
        if (raw.length >= 3 && raw[0] == (byte) 0xEF && raw[1] == (byte) 0xBB && raw[2] == (byte) 0xBF) {
            return new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8);
        }
        if (raw.length >= 2 && raw[0] == (byte) 0xFF && raw[1] == (byte) 0xFE) {
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
        }
        try {
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return dec.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException ignored) {
            try {
                return new String(raw, Charset.forName("GB18030"));
            } catch (Exception e) {
                return new String(raw, StandardCharsets.UTF_8);
            }
        }
    }

    public static Game parse(String s) {
        Game g = new Game();
        int i = 0, n = s.length();
        while (i < n && s.charAt(i) != '(') i++;
        if (i >= n) return g;
        int depth = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
                i++;
                if (depth == 0) break;
            } else if (c == ')') {
                depth--;
                i++;
                if (depth == 0) break;   // 只读第一个棋局树
            } else if (c == ';') {
                i++;
                Node node = new Node();
                boolean touched = false;
                while (i < n) {
                    while (i < n && Character.isWhitespace(s.charAt(i))) i++;
                    if (i >= n || !Character.isUpperCase(s.charAt(i))) break;
                    int st = i;
                    while (i < n && Character.isUpperCase(s.charAt(i))) i++;
                    String prop = s.substring(st, i);
                    while (i < n && s.charAt(i) == '[') {
                        i++;
                        StringBuilder val = new StringBuilder();
                        while (i < n) {
                            char vc = s.charAt(i);
                            if (vc == '\\' && i + 1 < n) { val.append(s.charAt(i + 1)); i += 2; continue; }
                            if (vc == ']') { i++; break; }
                            val.append(vc); i++;
                        }
                        if (depth == 1) {
                            touched |= applyProp(g, node, prop, val.toString());
                        }
                    }
                }
                if (depth == 1 && (node.color != 0 || node.comment != null)) g.nodes.add(node);
            } else {
                i++;
            }
        }
        return g;
    }

    /** 返回是否把该属性写入了节点/对局 */
    private static boolean applyProp(Game g, Node node, String prop, String v) {
        switch (prop) {
            case "B":
            case "W":
                node.color = prop.equals("B") ? GoGame.BLACK : GoGame.WHITE;
                if (v.length() >= 2) {
                    int[] p = GoGame.fromSgf(v);
                    if (p[0] >= 0 && p[1] >= 0 && p[0] < g.size && p[1] < g.size) {
                        node.x = p[0];
                        node.y = p[1];
                    }
                }
                return true;
            case "C":
            case "GC":
                node.comment = v;
                return true;
            case "AB":
                addPoint(g, g.setupBlack, v);
                return true;
            case "AW":
                addPoint(g, g.setupWhite, v);
                return true;
            case "AE":
                addPoint(g, g.setupEmpty, v);
                return true;
            case "PB": g.blackName = v; return true;
            case "PW": g.whiteName = v; return true;
            case "BR": g.blackRank = v; return true;
            case "WR": g.whiteRank = v; return true;
            case "RE": g.result = v; return true;
            case "EV": g.event = v; return true;
            case "DT": g.date = v; return true;
            case "KM":
                try { g.komi = Double.parseDouble(v.trim()); } catch (Exception ignored) {}
                return true;
            case "SZ":
                try {
                    int sz = Integer.parseInt(v.trim());
                    if (sz >= 2 && sz <= 25) g.size = sz;
                } catch (Exception ignored) {}
                return true;
            case "HA":
                try { g.handicap = Integer.parseInt(v.trim()); } catch (Exception ignored) {}
                return true;
            case "PL":
                if (v.trim().equalsIgnoreCase("B")) g.playerToMove = GoGame.BLACK;
                else if (v.trim().equalsIgnoreCase("W")) g.playerToMove = GoGame.WHITE;
                return true;
            default:
                return false;
        }
    }

    private static void addPoint(Game g, List<int[]> list, String v) {
        if (v.length() < 2) return;
        int[] p = GoGame.fromSgf(v);
        if (p[0] >= 0 && p[1] >= 0 && p[0] < g.size && p[1] < g.size) list.add(p);
    }
}
