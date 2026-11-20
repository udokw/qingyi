package com.sijia.go.net;

import android.os.SystemClock;

import org.json.JSONObject;

/**
 * 主时钟 + 读秒。只在 Host 侧作为权威运行；Guest 只接收快照做显示插值。
 * 颜色下标：0=黑，1=白。
 */
public class LanClock {
    private final int mainSec;
    private final int byoYomiSec;
    private final int periods;

    private final long[] mainMs = new long[2];
    private final int[] periodsLeft = new int[2];
    private final long[] periodRemain = new long[2];

    private int turn;
    private long turnStart;
    private boolean running;
    /** 断线等待期间计时冻结。 */
    private boolean paused;
    private int timeoutColor = -1;

    public LanClock(int mainSec, int byoYomiSec, int periods) {
        this.mainSec = Math.max(0, mainSec);
        this.byoYomiSec = Math.max(0, byoYomiSec);
        this.periods = Math.max(0, periods);
        for (int i = 0; i < 2; i++) {
            mainMs[i] = this.mainSec * 1000L;
            periodsLeft[i] = this.periods;
            periodRemain[i] = this.byoYomiSec * 1000L;
        }
    }

    public boolean active() {
        return mainSec > 0;
    }

    public void start(int firstColor) {
        turn = firstColor;
        turnStart = SystemClock.elapsedRealtime();
        running = active();
    }

    public void stop() {
        running = false;
    }

    /** 断线：先结算已用时间，再冻结计时，宽限期内不计入胜负。 */
    public void pause() {
        pauseAt(SystemClock.elapsedRealtime());
    }

    /**
     * 断线：只结算到最后一次收到对方消息的时刻。
     * 否则检测断线本身要耗十几秒，会把这十几秒也算给行棋方，
     * 导致双方冻结后的读数不一致。
     */
    public void pauseAt(long lastContactAt) {
        if (!running) return;
        charge(Math.max(turnStart, lastContactAt));
        running = false;
        paused = true;
    }

    /** 重连成功：从当前剩余时间继续走。 */
    public void resume() {
        if (!active() || timeoutColor >= 0) return;
        turnStart = SystemClock.elapsedRealtime();
        running = true;
        paused = false;
    }

    public boolean paused() {
        return paused;
    }

    public int turn() {
        return turn;
    }

    public boolean running() {
        return running;
    }

    public int timeoutColor() {
        return timeoutColor;
    }

    /** 每次落子后调用：结算本方用时、重置读秒段、切换行棋方。 */
    public void onMove(int color) {
        long now = SystemClock.elapsedRealtime();
        charge(now);
        if (!running) return;
        turn = 1 - turn;
        turnStart = now;
    }

    public void resetTurnClock() {
        turnStart = SystemClock.elapsedRealtime();
    }

    /** 悔棋后把行棋方同步回权威棋局。 */
    public void setTurn(int colorIndex) {
        if (colorIndex < 0 || colorIndex > 1) return;
        turn = colorIndex;
        turnStart = SystemClock.elapsedRealtime();
    }

    /** 供 Host 定时器调用，检测超时。 */
    public void tick() {
        if (!running) return;
        charge(SystemClock.elapsedRealtime());
    }

    private void charge(long now) {
        if (!running || turn < 0 || turn > 1) return;
        long elapsed = now - turnStart;
        if (elapsed <= 0) return;
        turnStart = now;

        if (mainMs[turn] > 0) {
            if (elapsed < mainMs[turn]) {
                mainMs[turn] -= elapsed;
                return;
            }
            elapsed -= mainMs[turn];
            mainMs[turn] = 0;
            periodsLeft[turn] = periods;
            periodRemain[turn] = byoYomiSec * 1000L;
        }

        if (periodRemain[turn] <= 0) {
            running = false;
            timeoutColor = turn;
            return;
        }
        while (elapsed > 0) {
            if (elapsed < periodRemain[turn]) {
                periodRemain[turn] -= elapsed;
                return;
            }
            elapsed -= periodRemain[turn];
            periodsLeft[turn]--;
            periodRemain[turn] = byoYomiSec * 1000L;
            if (periodsLeft[turn] <= 0) {
                running = false;
                timeoutColor = turn;
                return;
            }
        }
    }

    public JSONObject snapshot(int moveNo) {
        JSONObject o = new JSONObject();
        try {
            o.put("turn", turn == 0 ? "B" : "W");
            o.put("blackRemainMs", remain(0));
            o.put("whiteRemainMs", remain(1));
            o.put("blackPeriods", periodsLeft[0]);
            o.put("whitePeriods", periodsLeft[1]);
            o.put("blackByoYomi", inByoYomi(0));
            o.put("whiteByoYomi", inByoYomi(1));
            o.put("running", running);
            o.put("paused", paused);
            o.put("moveNo", moveNo);
            o.put("serverTime", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        return o;
    }

    public long remain(int color) {
        return mainMs[color] > 0 ? mainMs[color] : Math.max(0, periodRemain[color]);
    }

    public int periodsLeft(int color) {
        return periodsLeft[color];
    }

    public boolean inByoYomi(int color) {
        return mainMs[color] <= 0;
    }

    public int periods() {
        return periods;
    }

    public int byoYomiSec() {
        return byoYomiSec;
    }
}
