package com.sijia.go.net;

import com.sijia.go.GomokuGame;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 五子棋规则适配器：封装 GomokuGame，接入现有 LAN 协议。
 * 仅新增 game=gomoku 取值，发现 / 握手 / 重连 / 房主权威全部复用。
 */
public class GomokuRules implements GameAdapter {
    private GomokuGame game = new GomokuGame(19);
    /** 黑棋禁手（长连/四四/三三），由房主开局设置并在快照里同步. */
    private boolean renju = false;

    public GomokuGame game() {
        return game;
    }

    public boolean isRenju() { return renju; }

    /** 必须在开局前调用；对局中切换会导致两端规则不一致. */
    public void setRenju(boolean on) {
        renju = on;
        game.setRenju(on);
    }

    @Override
    public String id() {
        return GOMOKU;
    }

    @Override
    public String label() {
        return "五子棋";
    }

    @Override
    public int boardSize() {
        return game.size();
    }

    @Override
    public void reset() {
        game = new GomokuGame(19);
        game.setRenju(renju);
    }

    @Override
    public int turn() {
        return game.turn();
    }

    @Override
    public int moveCount() {
        return game.moveCount();
    }

    @Override
    public boolean isOver() {
        return game.isOver();
    }

    @Override
    public String hash(int moveNo) {
        StringBuilder sb = new StringBuilder(420);
        sb.append("gomoku|1|SZ=").append(game.size())
                .append("|R=").append(renju ? 1 : 0)
                .append("|M=").append(moveNo)
                .append("|T=").append(game.turn() == GomokuGame.BLACK ? 'B' : 'W')
                .append("|W=").append(game.winner())
                .append('|');
        int[][] b = game.board();
        for (int y = 0; y < game.size(); y++) {
            if (y > 0) sb.append('/');
            for (int x = 0; x < game.size(); x++) sb.append((char) ('0' + b[y][x]));
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

    @Override
    public JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            o.put("gameId", id());
            o.put("size", game.size());
            o.put("renju", renju);
            o.put("toMove", colorChar(game.turn()));
            o.put("winner", game.winner());
            int[] wl = game.winLine();
            if (wl != null) {
                JSONArray line = new JSONArray();
                for (int v : wl) line.put(v);
                o.put("winLine", line);
            }
            JSONArray arr = new JSONArray();
            List<int[]> moves = game.history();
            for (int[] m : moves) {
                JSONArray one = new JSONArray();
                one.put(colorChar(m[0]));
                one.put(m[1]);
                one.put(m[2]);
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
            renju = p.optBoolean("renju", false);
            game = new GomokuGame(size);
            game.setRenju(renju);
            JSONArray arr = p.optJSONArray("moves");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray one = arr.getJSONArray(i);
                    if (!game.play(one.getInt(1), one.getInt(2))) return false;
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
        int x = move.optInt("x", -1);
        int y = move.optInt("y", -1);
        if (x < 0 || y < 0 || x >= game.size() || y >= game.size()) {
            r.code = "E_OCCUPIED";
            r.message = "落点超出棋盘";
            return r;
        }
        if (game.get(x, y) != GomokuGame.EMPTY) {
            r.code = "E_OCCUPIED";
            r.message = "这里已经有子了";
            return r;
        }
        int res = game.tryPlay(x, y);
        if (res == GomokuGame.PLAY_FORBIDDEN) {
            r.code = "E_ILLEGAL";
            r.message = game.forbiddenText(x, y);
            return r;
        }
        if (res != GomokuGame.PLAY_OK) {
            r.code = "E_ILLEGAL";
            r.message = "这一手不能下";
            return r;
        }
        r.ok = true;
        r.x = x;
        r.y = y;
        r.nextTurn = game.turn();
        return r;
    }

    @Override
    public boolean applyAck(JSONObject ack, int moveNo, String expectedHash) {
        return game.play(ack.optInt("x", -1), ack.optInt("y", -1));
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
            p.put("pass", false);
            p.put("x", move.optInt("x", -1));
            p.put("y", move.optInt("y", -1));
            if (hashBefore != null && !hashBefore.isEmpty()) p.put("hashBefore", hashBefore);
        } catch (Exception ignored) {
        }
        return p;
    }

    @Override
    public MoveInfo lastMove() {
        List<int[]> h = game.history();
        if (h.isEmpty()) return null;
        int[] m = h.get(h.size() - 1);
        MoveInfo i = new MoveInfo();
        i.seat = m[0];
        i.fx = m[1];
        i.fy = m[2];
        i.text = coordinate(m[1], m[2]);
        return i;
    }

    @Override
    public JSONObject result() {
        if (!game.isOver()) return null;
        String code = game.winner() == GomokuGame.BLACK ? "B+5" : "W+5";
        return result(code, colorName(game.winner()) + "方五连获胜", "rule");
    }

    @Override
    public JSONObject resultByResign(int resignSeat) {
        int winner = 3 - resignSeat;
        return result(colorChar(winner) + "+R",
                colorName(resignSeat) + "方认输，" + colorName(winner) + "方中盘胜", "resign");
    }

    @Override
    public JSONObject resultByTimeout(int timeoutSeat) {
        int winner = 3 - timeoutSeat;
        return result(colorChar(winner) + "+T",
                colorName(timeoutSeat) + "方超时，" + colorName(winner) + "方胜", "timeout");
    }

    @Override
    public JSONObject resultByDisconnect(int lostSeat) {
        int winner = 3 - lostSeat;
        return result(colorChar(winner) + "+R",
                colorName(lostSeat) + "方断线，" + colorName(winner) + "方中盘胜", "disconnect");
    }

    @Override
    public JSONObject scoreResult() {
        return null;
    }

    private static JSONObject result(String code, String detail, String reason) {
        JSONObject o = new JSONObject();
        try {
            o.put("result", code);
            o.put("detail", detail);
            o.put("reason", reason);
        } catch (Exception ignored) {
        }
        return o;
    }

    @Override
    public String colorChar(int seat) {
        return seat == GomokuGame.BLACK ? "B" : "W";
    }

    @Override
    public int seatFromChar(String s) {
        return "B".equalsIgnoreCase(s) ? GomokuGame.BLACK : GomokuGame.WHITE;
    }

    @Override
    public String colorName(int seat) {
        return seat == GomokuGame.BLACK ? "黑" : "白";
    }

    @Override
    public boolean supportsPass() {
        return false;
    }

    @Override
    public boolean supportsScore() {
        return false;
    }

    private String coordinate(int x, int y) {
        return String.valueOf((char) ('A' + x)) + (game.size() - y);
    }
}
