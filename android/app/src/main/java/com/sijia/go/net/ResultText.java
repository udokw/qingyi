package com.sijia.go.net;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联机对局结果的中文展示文案。
 * 协议里的 result / reason / detail 保持原样（机器可读），本类只负责展示层映射，
 * 大厅、对局页、结果弹窗、通知统一走这里，避免一处中文一处英文。
 */
public final class ResultText {
    /** 围棋结果码：B+R / W+R / B+T / W+T / B+7.5 / W+3 / draw。 */
    private static final Pattern GO_CODE = Pattern.compile("^([BW])\\+(.+)$", Pattern.CASE_INSENSITIVE);

    private ResultText() {}

    /**
     * @param gameId go / xiangqi / gomoku / banqi
     * @param code   协议结果码
     * @param reason resign / timeout / disconnect / no_moves / draw / rule / score
     * @param detail 规则层给出的中文细节，作为兜底
     */
    public static String of(String gameId, String code, String reason, String detail) {
        String c = code == null ? "" : code.trim();
        String r = reason == null ? "" : reason.trim();
        String d = detail == null ? "" : detail.trim();
        if (c.isEmpty()) return d.isEmpty() ? "对局结束" : d;
        boolean xiangqi = GameAdapter.XIANGQI.equals(gameId);
        boolean gomoku = GameAdapter.GOMOKU.equals(gameId);
        boolean banqi = GameAdapter.BANQI.equals(gameId);
        if ("draw".equalsIgnoreCase(c)) {
            if (xiangqi) return xiangqiDraw(d);
            if (banqi) return banqiResult(c, r, d);
            return gomoku ? "和棋（棋盘已满）" : "和棋";
        }
        if (banqi) return banqiResult(c, r, d);
        if (gomoku) return gomokuResult(c, r, d);
        return xiangqi ? xiangqiWin(c, r, d) : goResult(c, r, d);
    }

    // ---------------- 暗棋 ----------------

    /**
     * 暗棋：优先按协议 reason 判定原因；reason 为空或旧版 "banqi" 时回退到 detail 文案匹配，
     * 保证新客机兼容旧房主。
     */
    private static String banqiResult(String code, String reason, String detail) {
        if ("draw".equalsIgnoreCase(code)) {
            return detail.isEmpty() ? "和棋" : "和棋 · " + detail;
        }
        boolean redWin = "red".equalsIgnoreCase(code);
        String winner = redWin ? "红方" : "黑方";
        String loser = redWin ? "黑方" : "红方";
        if ("timeout".equalsIgnoreCase(reason)) return winner + "胜（" + loser + "超时判负）";
        if ("disconnect".equalsIgnoreCase(reason)) return winner + "胜（" + loser + "断线判负）";
        if ("resign".equalsIgnoreCase(reason)) return winner + "胜（" + loser + "认输）";
        if ("no_moves".equalsIgnoreCase(reason)) return winner + "胜（" + loser + "无子可动）";
        if (detail.contains("认输")) return winner + "胜（" + loser + "认输）";
        if (detail.contains("超时")) return winner + "胜（" + loser + "超时判负）";
        if (detail.contains("断线")) return winner + "胜（" + loser + "断线判负）";
        if (detail.contains("无子可动")) return winner + "胜（" + loser + "无子可动）";
        return detail.isEmpty() ? winner + "胜" : winner + "胜（" + detail + "）";
    }

    // ---------------- 五子棋 ----------------

    private static String gomokuResult(String code, String reason, String detail) {
        Matcher m = GO_CODE.matcher(code);
        if (!m.matches()) return detail.isEmpty() ? "对局结束" : detail;
        boolean blackWin = "B".equalsIgnoreCase(m.group(1));
        String winner = blackWin ? "黑方" : "白方";
        String loser = blackWin ? "白方" : "黑方";
        String tag = m.group(2).trim();
        if ("disconnect".equalsIgnoreCase(reason)) return winner + "胜（" + loser + "断线判负）";
        if ("R".equalsIgnoreCase(tag)) return winner + "中盘胜（" + loser + "认输）";
        if ("T".equalsIgnoreCase(tag)) return winner + "胜（" + loser + "超时判负）";
        if ("5".equals(tag)) return winner + "五连获胜";
        return winner + "胜（" + detailOr(tag, "五连") + "）";
    }

    // ---------------- 围棋 ----------------

    private static String goResult(String code, String reason, String detail) {
        Matcher m = GO_CODE.matcher(code);
        if (!m.matches()) return detail.isEmpty() ? "对局结束" : detail;
        boolean blackWin = "B".equalsIgnoreCase(m.group(1));
        String winner = blackWin ? "黑方" : "白方";
        String loser = blackWin ? "白方" : "黑方";
        String tag = m.group(2).trim();
        if ("disconnect".equalsIgnoreCase(reason)) return winner + "胜（" + loser + "断线判负）";
        if ("R".equalsIgnoreCase(tag)) return winner + "中盘胜（" + loser + "认输）";
        if ("T".equalsIgnoreCase(tag)) return winner + "胜（" + loser + "超时判负）";
        if (isNumber(tag)) return winner + "胜 " + tag + " 目";
        return winner + "胜（" + detailOr(tag, "数目") + "）";
    }

    // ---------------- 象棋 ----------------

    private static String xiangqiWin(String code, String reason, String detail) {
        boolean redWin = "R".equalsIgnoreCase(code);
        String winner = redWin ? "红方" : "黑方";
        String loser = redWin ? "黑方" : "红方";
        if ("resign".equalsIgnoreCase(reason)) return winner + "胜（" + loser + "认输）";
        if ("timeout".equalsIgnoreCase(reason)) return winner + "胜（" + loser + "超时判负）";
        if ("disconnect".equalsIgnoreCase(reason)) return winner + "胜（" + loser + "断线判负）";
        if (detail.contains("被将死")) return winner + "胜（" + loser + "被将死）";
        if (detail.contains("困毙")) return winner + "胜（" + loser + "困毙，无子可走）";
        if (detail.contains("长将")) return winner + "胜（" + loser + "长将判负）";
        return winner + "胜";
    }

    private static String xiangqiDraw(String detail) {
        if (detail.contains("六十回合") || detail.contains("60 回合")) return "和棋（六十回合无吃子）";
        if (detail.contains("重复")) return "和棋（三次重复局面）";
        if (detail.contains("协议")) return "和棋（双方协议）";
        return "和棋";
    }

    // ---------------- 工具 ----------------

    private static boolean isNumber(String s) {
        if (s.isEmpty()) return false;
        boolean dot = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '.' && !dot) {
                dot = true;
                continue;
            }
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    private static String detailOr(String s, String def) {
        return s == null || s.isEmpty() ? def : s;
    }
}
