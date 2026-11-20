package com.sijia.go.net;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;

/** Host 侧 TCP 服务：接受一个 Guest 连接，收发 JSON 帧。回调统一切到主线程。 */
public class LanHost {
    private static final String TAG = "LanHost";
    private static final long HELLO_TIMEOUT_MS = 8000;

    public interface Callback {
        void onMessage(JSONObject msg);

        void onGuestChanged(boolean connected);

        void onError(String message);
    }

    /** 探活应答内容由 LanHub 提供（房间码/棋种/实例号）。 */
    public interface ProbeInfo {
        JSONObject payload();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerSocket server;
    private Thread acceptThread;
    private volatile Conn guest;
    private Callback cb;
    private int port;
    private volatile ProbeInfo probeInfo;

    /** 探活应答：只读快照，不占用客机槽位。 */
    public void setProbeInfo(ProbeInfo info) {
        probeInfo = info;
    }

    /** 从 preferred 开始顺延寻找可用端口；固定端口都不可用时退回系统分配（端口 0）。 */
    public int start(int preferred, int tries, Callback cb) throws IOException {
        this.cb = cb;
        IOException last = null;
        java.util.List<Integer> candidates = new java.util.ArrayList<>();
        candidates.add(preferred);
        candidates.add(0);
        for (int i = 0; i < tries; i++) {
            int p = preferred + i;
            if (!candidates.contains(p)) candidates.add(p);
        }
        for (int p : candidates) {
            ServerSocket s = new ServerSocket();
            try {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                server = s;
                // p == 0 时由系统分配，必须回读真实端口
                port = s.getLocalPort();
                break;
            } catch (IOException e) {
                last = e;
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (server == null) throw last == null ? new IOException("没有可用端口") : last;

        acceptThread = new Thread(this::acceptLoop, "lan-host-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "监听端口 " + port);
        return port;
    }

    public int port() {
        return port;
    }

    public boolean hasGuest() {
        Conn g = guest;
        return g != null && !g.closed;
    }

    /** HELLO 校验通过后由 LanHub 调用，之后不再允许被新连接顶替。 */
    public void markHandshaked() {
        Conn g = guest;
        if (g != null) {
            g.handshaked = true;
            Log.i(TAG, "连接已完成握手");
        }
    }

    /** 主动关闭当前客机连接（握手超时/房间关闭）。 */
    public void closeGuest() {
        Conn g = guest;
        if (g != null) g.close();
    }

    public boolean send(JSONObject msg) {
        Conn g = guest;
        if (g == null) {
            Log.w(TAG, "send 失败：没有客机连接 type=" + Proto.type(msg));
            return false;
        }
        return g.send(msg);
    }

    public void stop() {
        Conn g = guest;
        guest = null;
        if (g != null) g.close();
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
        if (acceptThread != null) acceptThread.interrupt();
        Log.i(TAG, "已停止监听");
    }

    private void acceptLoop() {
        while (server != null && !server.isClosed()) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                Log.i(TAG, "接受连接 from=" + s.getRemoteSocketAddress());
                // 每条连接先单独读首帧：探活请求直接答复后关闭，绝不占用客机槽位，
                // 否则一次探活就会把正在对局的客机顶掉。
                Thread t = new Thread(() -> handleIncoming(s), "lan-host-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (server != null && !server.isClosed()) {
                    Log.e(TAG, "accept 异常", e);
                    main.post(() -> {
                        if (cb != null) cb.onError("监听失败: " + e.getMessage());
                    });
                }
                break;
            }
        }
    }

    private void handleIncoming(Socket s) {
        try {
            s.setSoTimeout(3000);
            JSONObject first = FrameIO.read(s.getInputStream());
            s.setSoTimeout(0);
            if (first != null && Proto.PROBE.equals(Proto.type(first))) {
                ProbeInfo pi = probeInfo;
                JSONObject payload = pi == null ? new JSONObject() : pi.payload();
                if (payload == null) payload = new JSONObject();
                try {
                    FrameIO.write(s.getOutputStream(),
                            Proto.msg(Proto.PROBE_ACK, payload.optString("room", ""), "host", payload));
                    Log.i(TAG, "探活应答 from=" + s.getRemoteSocketAddress()
                            + " room=" + payload.optString("room") + " state=" + payload.optString("state"));
                } finally {
                    s.close();
                }
                return;
            }
            attachGuest(s, first);
        } catch (Exception e) {
            Log.w(TAG, "连接预处理失败 from=" + s.getRemoteSocketAddress() + " : " + e.getMessage());
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private synchronized void attachGuest(Socket s, JSONObject first) throws IOException {
        Conn old = guest;
        if (old != null && !old.closed) {
            if (old.handshaked) {
                // 已有一台完成握手的客机，拒绝后来的连接
                Log.w(TAG, "已有客机在位，拒绝新连接 from=" + s.getRemoteSocketAddress());
                s.close();
                return;
            }
            // 旧连接尚未握手（多半是半开/僵尸），直接顶替
            Log.w(TAG, "顶替未握手的旧连接");
            old.close();
        }
        Conn c = new Conn(s, first);
        guest = c;
        c.start();
        main.post(() -> {
            if (cb != null) cb.onGuestChanged(true);
        });
        main.postDelayed(() -> {
            if (guest == c && !c.handshaked && !c.closed) {
                Log.e(TAG, "握手超时：accept 后 " + HELLO_TIMEOUT_MS + "ms 未收到 hello");
                if (cb != null) cb.onError("握手超时：对方已连上端口，但没有发送 hello");
                c.close();
            }
        }, HELLO_TIMEOUT_MS);
    }

    private final class Conn {
        final Socket socket;
        final DataInputStream in;
        final OutputStream out;
        volatile boolean closed;
        volatile boolean handshaked;
        /** 探活预读时已经读掉的首帧，交给读线程先处理。 */
        private final JSONObject prefetched;
        private final ArrayBlockingQueue<JSONObject> outQueue = new ArrayBlockingQueue<>(128);
        private Thread writer;

        Conn(Socket s, JSONObject first) throws IOException {
            socket = s;
            InputStream is = s.getInputStream();
            in = new DataInputStream(is);
            out = s.getOutputStream();
            prefetched = first;
        }

        void start() {
            // 写线程：所有 socket 写都在这里做，避免主线程回调里写网络触发 NetworkOnMainThreadException。
            writer = new Thread(() -> {
                while (!closed) {
                    JSONObject m;
                    try {
                        m = outQueue.take();
                    } catch (InterruptedException e) {
                        break;
                    }
                    try {
                        synchronized (out) {
                            FrameIO.write(out, m);
                        }
                        Log.i(TAG, "已发送 type=" + Proto.type(m));
                    } catch (Exception e) {
                        Log.e(TAG, "发送失败 type=" + Proto.type(m), e);
                        close();
                        break;
                    }
                }
            }, "lan-host-write");
            writer.setDaemon(true);
            writer.start();

            Thread t = new Thread(() -> {
                try {
                    if (prefetched != null) {
                        final JSONObject copy = prefetched;
                        Log.i(TAG, "处理预读帧 type=" + Proto.type(copy));
                        main.post(() -> {
                            if (cb != null && !closed) cb.onMessage(copy);
                        });
                    }
                    while (!closed) {
                        JSONObject m = FrameIO.read(in);
                        if (m == null) {
                            Log.w(TAG, "读到流结束，客机已关闭连接");
                            break;
                        }
                        Log.i(TAG, "收到 type=" + Proto.type(m));
                        final JSONObject copy = m;
                        main.post(() -> {
                            if (cb != null && !closed) cb.onMessage(copy);
                        });
                    }
                } catch (Exception e) {
                    if (!closed) Log.e(TAG, "读连接异常", e);
                } finally {
                    closed = true;
                    main.post(() -> {
                        if (guest == this) {
                            guest = null;
                            Log.i(TAG, "客机连接已释放 handshaked=" + handshaked);
                            if (cb != null) cb.onGuestChanged(false);
                        }
                    });
                }
            }, "lan-host-read");
            t.setDaemon(true);
            t.start();
        }

        boolean send(JSONObject m) {
            if (closed) {
                Log.w(TAG, "发送失败：连接已关闭 type=" + Proto.type(m));
                return false;
            }
            if (!outQueue.offer(m)) {
                Log.e(TAG, "发送失败：发送队列已满 type=" + Proto.type(m));
                return false;
            }
            return true;
        }

        void close() {
            closed = true;
            if (writer != null) writer.interrupt();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
