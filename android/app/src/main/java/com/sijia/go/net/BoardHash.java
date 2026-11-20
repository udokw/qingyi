package com.sijia.go.net;

import com.sijia.go.GoGame;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 棋盘同步校验哈希。
 * canonical = "1|SZ=19|M=<moveNo>|T=<B|W>|K=<koX>,<koY>|<row>/<row>/..."
 * 行优先，字符 0/1/2；SHA-256 小写十六进制。
 */
public final class BoardHash {
    private BoardHash() {}

    public static String hash(GoGame g, int moveNo) {
        StringBuilder sb = new StringBuilder(420);
        sb.append("1|SZ=").append(g.size())
                .append("|M=").append(moveNo)
                .append("|T=").append(g.turn() == GoGame.BLACK ? 'B' : 'W')
                .append("|K=").append(g.koX()).append(',').append(g.koY()).append('|');
        int[][] b = g.board();
        for (int y = 0; y < g.size(); y++) {
            if (y > 0) sb.append('/');
            for (int x = 0; x < g.size(); x++) sb.append((char) ('0' + b[y][x]));
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte v : d) hex.append(String.format("%02x", v & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 仅在协议日志/调试时使用。 */
    public static String canonical(GoGame g, int moveNo) {
        StringBuilder sb = new StringBuilder(420);
        sb.append("1|SZ=").append(g.size())
                .append("|M=").append(moveNo)
                .append("|T=").append(g.turn() == GoGame.BLACK ? 'B' : 'W')
                .append("|K=").append(g.koX()).append(',').append(g.koY()).append('|');
        int[][] b = g.board();
        for (int y = 0; y < g.size(); y++) {
            if (y > 0) sb.append('/');
            for (int x = 0; x < g.size(); x++) sb.append((char) ('0' + b[y][x]));
        }
        return sb.toString();
    }
}
