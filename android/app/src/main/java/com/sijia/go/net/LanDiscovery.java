package com.sijia.go.net;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;

/**
 * mDNS/DNS-SD 发现：注册 QY-<room>-<size>，并在 TXT 里显式带上 port/addr/room。
 * 房客优先使用 TXT 中的端口与地址（IPv4），失败再回退到 SRV 解析结果。
 */
public class LanDiscovery {
    private static final String TAG = "LanDiscovery";

    /** 一条可连接的房间记录。 */
    public static class Service {
        public final String name;     // QY-XXXX-19
        public final String code;     // XXXX
        public final String size;     // 19
        public final String host;     // SRV 解析出的地址（可能是 IPv6）
        public final int port;        // SRV 解析出的端口
        public final String txtAddr;  // TXT addr，通常为 IPv4
        public final int txtPort;     // TXT port
        public final String game;     // go / xiangqi
        /** 注册方进程号（TXT pid），仅用于排查僵尸条目。 */
        public final int pid;
        /** 注册实例 ID（TXT iid）：房主每次注册生成，用于同一房间码去重。 */
        public final String iid;
        /** 注册时刻（TXT ts，Unix ms）：只用于新旧实例排序，不作为存活依据。 */
        public final long ts;
        /** 本机发现该条目的时间，用于判断是否长期未刷新。 */
        public long lastSeen = android.os.SystemClock.elapsedRealtime();
        /** 本机发现该条目的时间（首次）。 */
        public long foundAt = android.os.SystemClock.elapsedRealtime();
        /** 最近一次探活成功的时间；0 表示尚未验证。 */
        public long lastOkAt;
        /** 是否已通过房间探活。 */
        public boolean verified;

        Service(String name, String code, String size, String host, int port,
                String txtAddr, int txtPort, String game, int pid, String iid, long ts) {
            this.name = name;
            this.code = code;
            this.size = size;
            this.host = host;
            this.port = port;
            this.txtAddr = txtAddr;
            this.txtPort = txtPort;
            this.game = game == null || game.isEmpty() ? GameAdapter.GO : game;
            this.pid = pid;
            this.iid = iid == null ? "" : iid;
            this.ts = ts;
        }

        /** UDP 通告/应答发现的房间：源地址就是房主真实 IPv4，直接视为已验证。 */
        public static Service fromUdp(String code, String size, String host, int port,
                                      String game, String iid) {
            String name = "QY-" + code + "-" + size;
            Service s = new Service(name, code, size, host, port, host, port, game, 0,
                    iid == null ? "" : iid, 0L);
            s.verified = true;
            s.lastOkAt = android.os.SystemClock.elapsedRealtime();
            return s;
        }

        public String gameLabel() {
            if (GameAdapter.XIANGQI.equals(game)) return "象棋";
            if (GameAdapter.GOMOKU.equals(game)) return "五子棋";
            if (GameAdapter.BANQI.equals(game)) return "暗棋";
            return "围棋";
        }

        /** 棋盘规模的人话描述。 */
        public String boardText() {
            if (GameAdapter.XIANGQI.equals(game)) return "9 × 10";
            if (GameAdapter.BANQI.equals(game)) return "4 × 8";
            return size + " 路";
        }

        /** 首选地址：TXT 显式声明优先。 */
        public String primaryHost() {
            return txtAddr != null && !txtAddr.isEmpty() ? txtAddr : host;
        }

        /** 首选端口：TXT 显式声明优先。 */
        public int primaryPort() {
            return txtPort > 0 ? txtPort : port;
        }

        public boolean hasFallback() {
            return txtAddr != null && !txtAddr.isEmpty() && !txtAddr.equals(host);
        }
    }

    public interface Listener {
        void onFound(Service service);

        void onLost(String serviceName);

        void onError(String message);
    }

    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    /** 同时浏览统一类型和棋种专用类型：iOS 端两套广告走不同 API，多一路更稳。 */
    private final java.util.List<NsdManager.DiscoveryListener> discoveries = new java.util.ArrayList<>();
    private Listener listener;
    /** mDNS 依赖组播；不少 ROM 默认丢弃未持锁的组播包，表现为“永远搜不到房间”。 */
    private WifiManager.MulticastLock multicastLock;

    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving;

    public void register(Context ctx, String serviceName, int port,
                         String roomCode, int size, String addr, String game, String iid) {
        register(ctx, serviceName, port, roomCode, size, addr, game, iid, true);
    }

    public void register(Context ctx, String serviceName, int port,
                         String roomCode, int size, String addr, String game, String iid,
                         boolean hints) {
        stopRegister();
        nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            Log.e(TAG, "register: NsdManager 不可用");
            return;
        }
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName);
        info.setServiceType(Proto.SERVICE_TYPE);
        info.setPort(port);
        try {
            // 显式 TXT：即使 SRV 端口/地址解析异常，房客也能拿到真实端口
            info.setAttribute("port", String.valueOf(port));
            info.setAttribute("pid", String.valueOf(android.os.Process.myPid()));
            info.setAttribute("room", roomCode);
            info.setAttribute("size", String.valueOf(size));
            info.setAttribute("game", game == null || game.isEmpty() ? GameAdapter.GO : game);
            // 房间规则：提示开关随房间下发
            info.setAttribute("hints", hints ? "1" : "0");
            // 注册实例 + 时间戳：供客机按 instanceId 去重、按 ts 排序新旧实例
            info.setAttribute("iid", iid == null ? "" : iid);
            info.setAttribute("ts", String.valueOf(System.currentTimeMillis()));
            if (addr != null && !addr.isEmpty()) info.setAttribute("addr", addr);
        } catch (Exception e) {
            Log.w(TAG, "register: 写入 TXT 失败 " + e.getMessage());
        }
        registration = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo i) {
                // 部分 ROM 的注册回调里 getPort() 为 0，这里打印我们实际声明的端口，避免误导排查
                Log.i(TAG, "注册成功 name=" + i.getServiceName()
                        + " 声明端口=" + port + " 回调端口=" + i.getPort());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo i, int errorCode) {
                Log.e(TAG, "注册失败 name=" + (i == null ? "?" : i.getServiceName()) + " code=" + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo i) {
                Log.i(TAG, "已注销 name=" + (i == null ? "?" : i.getServiceName()));
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo i, int errorCode) {
                Log.w(TAG, "注销失败 code=" + errorCode);
            }
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (Exception e) {
            Log.e(TAG, "registerService 异常", e);
        }
    }

    public void browse(Context ctx, Listener l) {
        stopBrowse();
        listener = l;
        acquireMulticastLock(ctx);
        if (nsd == null) nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            l.onError("系统不支持局域网发现，请使用手动 IP");
            return;
        }
        for (final String type : Proto.BROWSE_TYPES) {
            NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String serviceType) {
                    Log.i(TAG, "开始搜索 " + serviceType);
                }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    String name = info.getServiceName();
                    Log.i(TAG, "发现服务 name=" + name + " type=" + type);
                    if (name == null || !name.startsWith("QY-")) return;
                    for (NsdServiceInfo queued : resolveQueue) {
                        if (name.equals(queued.getServiceName())) return;
                    }
                    resolveQueue.addLast(info);
                    pump();
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {
                    Log.i(TAG, "服务消失 name=" + (info == null ? "?" : info.getServiceName()));
                    if (listener != null && info != null && info.getServiceName() != null) {
                        listener.onLost(info.getServiceName());
                    }
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                    Log.i(TAG, "停止搜索 " + serviceType);
                }

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    Log.e(TAG, "搜索失败 type=" + serviceType + " code=" + errorCode);
                    if (listener != null && Proto.SERVICE_TYPE.equals(serviceType)) {
                        listener.onError("搜索失败（" + errorCode + "），可改用下方手动地址加入");
                    }
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                    Log.w(TAG, "停止搜索失败 type=" + serviceType + " code=" + errorCode);
                }
            };
            discoveries.add(dl);
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, dl);
            } catch (Exception e) {
                Log.e(TAG, "discoverServices 异常 type=" + type, e);
            }
        }
        if (discoveries.isEmpty()) l.onError("搜索失败，请使用手动 IP");
    }

    private void pump() {
        if (resolving || nsd == null) return;
        NsdServiceInfo info = resolveQueue.pollFirst();
        if (info == null) return;
        resolving = true;
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo i, int errorCode) {
                    Log.w(TAG, "解析失败 name=" + (i == null ? "?" : i.getServiceName()) + " code=" + errorCode);
                    resolving = false;
                    pump();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo i) {
                    resolving = false;
                    try {
                        if (i.getHost() == null) {
                            Log.w(TAG, "解析结果无地址 name=" + i.getServiceName());
                            pump();
                            return;
                        }
                        Map<String, byte[]> attrs = i.getAttributes();
                        String txtAddr = attr(attrs, "addr");
                        int txtPort = parsePort(attr(attrs, "port"));
                        String name = i.getServiceName();
                        String[] parts = name.split("-");
                        String code = parts.length >= 2 ? parts[1].trim() : "";
                        String size = parts.length >= 3 ? leadingDigits(parts[2], "19") : "19";
                        String game = attr(attrs, "game");
                        int pid = parseInt(attr(attrs, "pid"));
                        String iid = attr(attrs, "iid");
                        long ts = parseLong(attr(attrs, "ts"));
                        String host = i.getHost().getHostAddress();
                        int port = i.getPort();
                        Log.i(TAG, "解析成功 name=" + name + " srv=" + host + ":" + port
                                + " txt=" + txtAddr + ":" + txtPort + " code=" + code
                                + " size=" + size + " game=" + game + " pid=" + pid
                                + " iid=" + iid + " ts=" + ts);
                        if (listener != null) {
                            listener.onFound(new Service(name, code, size, host, port,
                                    txtAddr, txtPort, game, pid, iid, ts));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "处理解析结果异常", e);
                    }
                    pump();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "resolveService 异常", e);
            resolving = false;
        }
    }

    private static String attr(Map<String, byte[]> attrs, String key) {
        if (attrs == null) return "";
        byte[] v = attrs.get(key);
        if (v == null) return "";
        return new String(v, StandardCharsets.UTF_8).trim();
    }

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            return p > 0 && p < 65536 ? p : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 探活结果：房间仍在且房间码一致。 */
    public static final int ALIVE = 1;
    /** 连不上：房主已退出/被防火墙阻断。 */
    public static final int DEAD = 0;
    /** 端口有人监听，但响应的是另一个房间（典型：同端口的新房间，旧 mDNS 记录残留）。 */
    public static final int STALE = -1;
    /** 端口能连上但对方不响应探活（旧版本房主），按存活处理。 */
    public static final int UNKNOWN = 2;

    /**
     * 房间级探活：连接后发 probe，房主回 probe_ack 并带上自己的房间码。
     * 只做 TCP 连接判断不够——房主重启后新旧房间常复用同一端口，
     * 旧 mDNS 条目会因此“看起来还活着”，点进去才报房间码不对。
     */
    public static int probe(String host, int port, String roomCode, int timeoutMs) {
        if (host == null || host.trim().isEmpty() || port <= 0) return DEAD;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host.trim(), port), Math.max(300, timeoutMs));
            s.setSoTimeout(Math.max(400, timeoutMs));
            org.json.JSONObject q = Proto.msg(Proto.PROBE, roomCode == null ? "" : roomCode,
                    "guest", new org.json.JSONObject());
            FrameIO.write(s.getOutputStream(), q);
            org.json.JSONObject a = FrameIO.read(s.getInputStream());
            if (a == null || !Proto.PROBE_ACK.equals(Proto.type(a))) return UNKNOWN;
            String actual = Proto.p(a).optString("room", "");
            if (actual.isEmpty()) return UNKNOWN;
            return actual.equalsIgnoreCase(roomCode) ? ALIVE : STALE;
        } catch (java.net.SocketTimeoutException e) {
            return UNKNOWN;
        } catch (Exception e) {
            return DEAD;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * TCP 探测房间是否仍然可连。杀进程/异常退出后 mDNS 记录会在 TTL 内残留，
     * 用一次轻量连接确认房主仍在监听，避免点进僵尸房间。
     */
    public static boolean reachable(String host, int port, int timeoutMs) {
        if (host == null || host.trim().isEmpty() || port <= 0) return false;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host.trim(), port), Math.max(120, timeoutMs));
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String leadingDigits(String s, String def) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            sb.append(c);
        }
        return sb.length() == 0 ? def : sb.toString();
    }

    public void stopRegister() {
        if (nsd != null && registration != null) {
            try {
                nsd.unregisterService(registration);
            } catch (Exception e) {
                Log.w(TAG, "unregisterService 异常 " + e.getMessage());
            }
        }
        registration = null;
    }

    public void stopBrowse() {
        if (nsd != null) {
            for (NsdManager.DiscoveryListener dl : discoveries) {
                try {
                    nsd.stopServiceDiscovery(dl);
                } catch (Exception e) {
                    Log.w(TAG, "stopServiceDiscovery 异常 " + e.getMessage());
                }
            }
        }
        discoveries.clear();
        resolveQueue.clear();
        resolving = false;
        releaseMulticastLock();
    }

    private void acquireMulticastLock(Context ctx) {
        if (multicastLock != null && multicastLock.isHeld()) return;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            multicastLock = wm.createMulticastLock("qingyi-mdns");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            Log.i(TAG, "已获取 Wi-Fi 组播锁");
        } catch (Exception e) {
            Log.w(TAG, "获取组播锁失败: " + e.getMessage());
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock == null) return;
        try {
            if (multicastLock.isHeld()) multicastLock.release();
        } catch (Exception e) {
            Log.w(TAG, "释放组播锁异常: " + e.getMessage());
        }
        multicastLock = null;
    }

    public void stop() {
        stopBrowse();
        stopRegister();
        listener = null;
        nsd = null;
    }
}
