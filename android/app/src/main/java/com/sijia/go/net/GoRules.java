package com.sijia.go.net;

import com.sijia.go.GoGame;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 围棋规则适配器：封装 GoGame，行为与 P0 之前的联机逻辑保持一致。 */
public class GoRules implements GameAdapter {
    public static final double DEFAULT_KOMI = 7.5;

    private GoGame game = new GoGame(19);
    private double komi = DEFAULT_KOMI;

    public GoGame game() {
        return game;
    }

    @Override
    public String id() {
        return GO;
    }

    @Override
    public String label() {
        return "围棋";
    }

    @Override
    public int boardSize() {
        return game.size();
    }

    @Override
    public void reset() {
        game = new GoGame(19);
        game.setKomi(komi);
    }

    @Override
    public void setKomi(double k) {
        komi = k;
        game.setKomi(k);
    }

    @Override
    public double komi() {
        return komi;
    }

    @Override
    public int turn() {
        return game.turn();
    }

    @Override
    public int moveCount() {
        return game.history().size();
    }

    /** 围棋的结束由双方停一手/认输/超时决定，棋规本身不会置为结束。 */
    @Override
    public boolean isOver() {
        return false;
    }

    @Override
    public String hash(int moveNo) {
        return BoardHash.hash(game, moveNo);
    }

    @Override
    public JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            o.put("gameId", id());
            o.put("size", game.size());
            o.put("komi", komi);
            o.put("toMove", colorChar(game.turn()));
            JSONArray ko = new JSONArray();
            ko.put(game.koX());
            ko.put(game.koY());
            o.put("ko", ko);
            o.put("board", boardString());
            JSONArray arr = new JSONArray();
            List<GoGame.Move> moves = new ArrayList<>(game.history());
            Collections.reverse(moves);
            for (GoGame.Move m : moves) {
                JSONArray one = new JSONArray();
                one.put(colorChar(m.color));
                one.put(m.x);
                one.put(m.y);
                arr.put(one);
            }
            o.put("moves", arr);
        } catch (Exception ignored) {
        }
        return o;
    }

    @Override
    public boolean restore(JSONObject p) {
        try {
            if (p == null) return false;
            int size = p.optInt("size", 19);
            komi = p.optDouble("komi", DEFAULT_KOMI);
            game = new GoGame(size);
            game.setKomi(komi);
            JSONArray arr = p.optJSONArray("moves");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray one = arr.getJSONArray(i);
                    int x = one.getInt(1);
                    int y = one.getInt(2);
                    GoGame.Move m = x < 0 ? game.pass() : game.play(x, y);
                    if (m == null) return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ApplyResult apply(int seat, JSONObject move) {
        ApplyResult r = new ApplyResult();
        r.nextTurn = game.turn();
        if (seat != game.turn()) {
            r.code = "E_OUT_OF_TURN";
            r.message = "还没轮到这一方";
            return r;
        }
        boolean pass = Proto.bool(move, "pass", false);
        if (pass) {
            game.pass();
            r.pass = true;
            r.nextTurn = game.turn();
            r.ok = true;
            return r;
        }
        int x = move.optInt("x", -1);
        int y = move.optInt("y", -1);
        if (x < 0 || y < 0 || x >= game.size() || y >= game.size()) {
            r.code = "E_OCCUPIED";
            r.message = "落点超出棋盘";
            return r;
        }
        if (game.board()[y][x] != 0) {
            r.code = "E_OCCUPIED";
            r.message = "这里已经有子了";
            return r;
        }
        GoGame.Move m = game.play(x, y);
        if (m == null) {
            boolean isKo = x == game.koX() && y == game.koY();
            r.code = isKo ? "E_KO" : "E_SUICIDE";
            r.message = isKo ? "打劫，暂时不能立即提回" : "禁入点";
            return r;
        }
        r.ok = true;
        r.x = x;
        r.y = y;
        r.captured = m.captured.size();
        r.nextTurn = game.turn();
        return r;
    }

    @Override
    public boolean applyAck(JSONObject ack, int moveNo, String expectedHash) {
        boolean pass = Proto.bool(ack, "pass", false);
        if (pass) {
            game.pass();
            return true;
        }
        return game.play(ack.optInt("x", -1), ack.optInt("y", -1)) != null;
    }

    @Override
    public int undo(int count) {
        int done = 0;
        for (int i = 0; i < count && game.canUndo(); i++) {
            game.undo();
            done++;
        }
        return done;
    }

    @Override
    public JSONObject movePayload(int seat, JSONObject move, String hashBefore) {
        JSONObject p = new JSONObject();
        try {
            p.put("pass", Proto.bool(move, "pass", false));
            p.put("x", move.optInt("x", -1));
            p.put("y", move.optInt("y", -1));
            if (hashBefore != null && !hashBefore.isEmpty()) p.put("hashBefore", hashBefore);
        } catch (Exception ignored) {
        }
        return p;
    }

    @Override
    public MoveInfo lastMove() {
        List<GoGame.Move> h = game.history();
        if (h.isEmpty()) return null;
        GoGame.Move m = h.get(0);
        MoveInfo i = new MoveInfo();
        i.seat = m.color;
        i.pass = m.x < 0;
        i.fx = m.x;
        i.fy = m.y;
        i.captured = m.captured == null ? 0 : m.captured.size();
        i.text = i.pass ? "停一手" : game.toGtp(m.x, m.y);
        return i;
    }

    @Override
    public JSONObject result() {
        return null;
    }

    @Override
    public JSONObject resultByResign(int resignSeat) {
        int winner = 3 - resignSeat;
        return result(colorChar(winner) + "+R",
                colorName(resignSeat) + "方认输，" + colorName(winner) + "方中盘胜", "resign", -1, -1);
    }

    @Override
    public JSONObject resultByTimeout(int timeoutSeat) {
        int winner = 3 - timeoutSeat;
        return result(colorChar(winner) + "+T",
                colorName(timeoutSeat) + "方超时，" + colorName(winner) + "方胜", "timeout", -1, -1);
    }

    @Override
    public JSONObject resultByDisconnect(int lostSeat) {
        int winner = 3 - lostSeat;
        return result(colorChar(winner) + "+R",
                colorName(lostSeat) + "方断线，" + colorName(winner) + "方中盘胜", "disconnect", -1, -1);
    }

    @Override
    public JSONObject scoreResult() {
        float[] s = game.chineseScore();
        double w = s[1] + komi;
        double diff = Math.abs(s[0] - w);
        String code;
        String detail;
        if (diff < 0.5) {
            code = "draw";
            detail = String.format("和棋\n黑 %.0f : 白 %.0f（含贴目 %.1f）", s[0], w, komi);
        } else if (s[0] > w) {
            code = String.format("B+%.1f", diff);
            detail = String.format("黑胜 %.1f 目\n黑 %.0f : 白 %.0f（含贴目 %.1f）", diff, s[0], w, komi);
        } else {
            code = String.format("W+%.1f", diff);
            detail = String.format("白胜 %.1f 目\n黑 %.0f : 白 %.0f（含贴目 %.1f）", diff, s[0], w, komi);
        }
        return result(code, detail, "score", s[0], w);
    }

    private static JSONObject result(String code, String detail, String reason,
                                     double blackScore, double whiteScore) {
        JSONObject o = new JSONObject();
        try {
            o.put("result", code);
            o.put("detail", detail);
            o.put("reason", reason);
            o.put("blackScore", blackScore);
            o.put("whiteScore", whiteScore);
        } catch (Exception ignored) {
        }
        return o;
    }

    @Override
    public String colorChar(int seat) {
        return seat == GoGame.BLACK ? "B" : "W";
    }

    @Override
    public int seatFromChar(String s) {
        return "B".equalsIgnoreCase(s) ? GoGame.BLACK : GoGame.WHITE;
    }

    @Override
    public String colorName(int seat) {
        return seat == GoGame.BLACK ? "黑" : "白";
    }

    @Override
    public boolean supportsPass() {
        return true;
    }

    @Override
    public boolean supportsScore() {
        return true;
    }

    private String boardString() {
        StringBuilder sb = new StringBuilder(game.size() * game.size() + game.size());
        int[][] b = game.board();
        for (int y = 0; y < game.size(); y++) {
            if (y > 0) sb.append('/');
            for (int x = 0; x < game.size(); x++) sb.append((char) ('0' + b[y][x]));
        }
        return sb.toString();
    }
}
