package com.sijia.go;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 象棋棋谱读取: 支持 FEN 局面 + ICCS 坐标着法(如 h2e2)的 PGN/文本.
 * 常见来源: Pikafish 对局记录、象棋巫师导出的坐标 PGN、手工整理的 FEN.
 */
public class XiangqiPgn {
    public static class Record {
        public String title = "";
        public String baseFen = null;        // null 表示标准开局
        public List<String> moves = new ArrayList<>();
        public int applied = 0;
        public String warning = "";
    }

    private static final Pattern FEN_TAG = Pattern.compile("[Ff][Ee][Nn]\\s+\"([^\"]+)\"");
    private static final Pattern EVENT_TAG = Pattern.compile("[Ee]vent\\s+\"([^\"]+)\"");
    private static final Pattern MOVE = Pattern.compile("[a-iA-I](?:10|[0-9])[a-iA-I](?:10|[0-9])");

    public static Record load(File f) throws Exception {
        byte[] raw = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while ((n = in.read(raw, off, raw.length - off)) > 0) off += n;
        }
        return parse(Sgf.decode(raw), f.getName());
    }

    public static Record parse(String text, String fileName) {
        Record r = new Record();
        String name = fileName == null ? "" : fileName;
        if (name.toLowerCase().endsWith(".pgn") || name.toLowerCase().endsWith(".txt"))
            name = name.substring(0, name.lastIndexOf('.'));
        r.title = name;

        Matcher ev = EVENT_TAG.matcher(text);
        if (ev.find()) r.title = ev.group(1).trim();

        Matcher fen = FEN_TAG.matcher(text);
        if (fen.find()) r.baseFen = fen.group(1).trim();

        // 没有 FEN 标签时, 尝试把单独一行当作 FEN(必须含 9 个 '/')
        if (r.baseFen == null) {
            for (String line : text.split("\n")) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                if (s.split("/").length == XiangqiGame.ROWS && s.matches("[0-9a-zA-Z/]+.*")) {
                    if (s.matches("^[0-9a-zA-Z/]+( [wbWB] .*)?$")) {
                        r.baseFen = s;
                        break;
                    }
                }
            }
        }

        // 只从标签区之后的着法正文里提取, 避免把 FEN 里的 "c5c1" 当成着法
        String body = text.replaceAll("\\[[^\\]]*\\]", " ");
        if (r.baseFen != null) body = body.replace(r.baseFen, " ");
        body = body.replaceAll("[0-9a-zA-Z/]{15,}", " ");
        Matcher mv = MOVE.matcher(body);
        while (mv.find()) r.moves.add(mv.group().toLowerCase());
        return r;
    }

    /** 把棋谱应用到棋局, 返回成功应用的着数 */
    public static int applyAll(Record r, XiangqiGame game) {
        if (r.baseFen != null) {
            if (!game.loadFen(r.baseFen)) {
                r.warning = "FEN 无法识别, 按标准开局";
                game.reset();
            }
        } else {
            game.reset();
        }
        int ok = 0;
        for (String m : r.moves) {
            if (!game.playUci(m)) break;
            ok++;
        }
        r.applied = ok;
        if (ok < r.moves.size()) {
            r.warning = "第 " + (ok + 1) + " 着无法识别, 其后着法已忽略";
        }
        return ok;
    }

    public static byte[] readAll(File f) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static byte[] readDefault(File f) throws Exception {
        return readAll(f);
    }
}