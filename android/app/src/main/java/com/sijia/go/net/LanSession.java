package com.sijia.go.net;

import org.json.JSONObject;

/**
 * 一局联机棋的状态容器（与棋种无关）。
 * Host 侧调用 applyAuthority 做权威裁决；Guest 侧只调用 applyAck / restore。
 * 本类不负责线程，调用方必须在主线程串行访问。
 */
public class LanSession {
    public final String room;
    /** 棋种：go / xiangqi / gomoku / banqi。 */
    public final String gameId;
    /** 本局匹配 id，用于忽略旧对局消息。 */
    public String matchId;
    public final GameAdapter rules;

    public int moveNo;
    public int consecutivePasses;
    public int size;
    public double komi = 7.5;

    public LanSession(String room, String gameId) {
        this(room, gameId, null);
    }

    public LanSession(String room, String gameId, String matchId) {
        this.room = room;
        this.gameId = GameAdapter.XIANGQI.equals(gameId) ? GameAdapter.XIANGQI
                : GameAdapter.GOMOKU.equals(gameId) ? GameAdapter.GOMOKU
                : GameAdapter.BANQI.equals(gameId) ? GameAdapter.BANQI : GameAdapter.GO;
        this.matchId = matchId == null || matchId.isEmpty()
                ? java.util.UUID.randomUUID().toString().substring(0, 8) : matchId;
        this.rules = GameAdapter.create(this.gameId);
        this.size = this.rules.boardSize();
        if (this.rules instanceof GoRules) {
            this.komi = GoRules.DEFAULT_KOMI;
            this.rules.setKomi(this.komi);
        } else {
            this.komi = 0;
        }
    }

    public void reset(double komi_) {
        this.komi = rules instanceof GoRules ? komi_ : 0;
        rules.setKomi(this.komi);
        rules.reset();
        moveNo = 0;
        consecutivePasses = 0;
        size = rules.boardSize();
    }

    public String hash() {
        return rules.hash(moveNo);
    }

    /** Host 权威落子。hashBefore 为 null 时不校验（Host 自己的手）。 */
    public GameAdapter.ApplyResult applyAuthority(int seat, JSONObject move,
                                                  int expectMoveNo, String hashBefore) {
        GameAdapter.ApplyResult r = new GameAdapter.ApplyResult();
        r.nextTurn = rules.turn();
        if (expectMoveNo > 0 && expectMoveNo != moveNo + 1) {
            r.code = "E_STALE_MOVE";
            r.message = "手数不同步（期望第 " + (moveNo + 1) + " 手）";
            return r;
        }
        if (seat != rules.turn()) {
            r.code = "E_OUT_OF_TURN";
            r.message = "还没轮到你落子";
            return r;
        }
        if (hashBefore != null && !hashBefore.isEmpty() && !hashBefore.equals(hash())) {
            r.code = "E_HASH_MISMATCH";
            r.message = "棋盘状态不同步";
            return r;
        }

        r = rules.apply(seat, move);
        if (!r.ok) return r;

        moveNo++;
        consecutivePasses = r.pass ? consecutivePasses + 1 : 0;
        r.moveNo = moveNo;
        r.hash = hash();
        r.nextTurn = rules.turn();
        return r;
    }

    /** Guest 收到 move_ack 后镜像落子，并校验 hash。 */
    public boolean applyAck(JSONObject ack, int ackMoveNo, String expectedHash) {
        if (ackMoveNo != moveNo + 1) return false;
        if (!rules.applyAck(ack, ackMoveNo, expectedHash)) return false;
        moveNo = ackMoveNo;
        consecutivePasses = Proto.bool(ack, "pass", false) ? consecutivePasses + 1 : 0;
        return expectedHash == null || expectedHash.isEmpty() || expectedHash.equals(hash());
    }

    public JSONObject snapshot() {
        JSONObject o = rules.snapshot();
        try {
            o.put("gameId", matchId);
            o.put("game", gameId);
            o.put("moveNo", moveNo);
            o.put("consecutivePasses", consecutivePasses);
            o.put("hash", hash());
        } catch (Exception ignored) {
        }
        return o;
    }

    public boolean restore(JSONObject p) {
        if (p == null) return false;
        if (!rules.restore(p)) return false;
        moveNo = p.optInt("moveNo", rules.moveCount());
        consecutivePasses = p.optInt("consecutivePasses", 0);
        size = rules.boardSize();
        String expected = p.optString("hash", "");
        return expected.isEmpty() || expected.equals(hash());
    }

    public int undo(int count) {
        int done = rules.undo(count);
        if (done > 0) moveNo = Math.max(0, moveNo - done);
        consecutivePasses = 0;
        return done;
    }

    public GameAdapter.MoveInfo lastMove() {
        return rules.lastMove();
    }
}
