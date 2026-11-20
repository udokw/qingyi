# 清弈 · 跨平台局域网联机（LAN Multiplayer）设计与实施方案

> 版本：v0.1（初稿）
> 适用范围：Android 端 `g:\AI\清弈`、iOS 端 `g:\AI\清弈-ios`
> 目标：Android↔Android、Android↔iOS、iOS↔iOS 三种组合在同一局域网内直接对弈，不依赖互联网服务器。

---

## 0. 结论摘要（先看这里）

1. **不要用 MultipeerConnectivity。** 它是 Apple 私有协议栈（AWDL + Bonjour + TLS），Android 无法参与；一旦引入，iOS↔iOS 与跨平台会走两套协议，维护成本翻倍。
2. **发现用 mDNS/DNS-SD（Bonjour），传输用 TCP，编码用「4 字节大端长度前缀 + UTF-8 JSON」。** Android 的 `NsdManager` 与 iOS 的 `NWBrowser/NWListener` 都是标准 mDNS/DNS-SD，天然互通；JSON 两端系统库原生支持（`org.json` / `JSONSerialization`），**零第三方依赖**。
3. **架构采用「一端做主机（Host）= 权威裁判」**。Host 持有唯一权威棋局与唯一权威时钟；Guest 只发送意图、接收裁决。不做分布式共识，避免规则分歧、时钟分歧、悔棋分歧。
4. **规则核心两端已高度一致**（坐标系、0/1/2 颜色、简单劫、禁自杀、中国规则数子、默认贴目 7.5），可以直接复用现有 `GoGame`。但存在若干**必须显式统一的差异**（详见 §1.3），其中最关键的是：
   - 让子摆点两端来源不同 → **由 Host 在开局时显式下发摆子坐标**；
   - "两停一手结束" Android 本地双人模式不会自动触发 → **由 Host 统一判定**；
   - 历史容器方向相反（栈 vs 数组）、`moveCount()` 语义不同 → **协议使用独立递增的 `moveNo`，不读内部计数**。
5. **不要用 UDP 广播/组播做发现。** iOS 14+ 对原始组播/广播收发需要 Apple 特批的 `com.apple.developer.networking.multicast` 权限，个人项目基本拿不到；而 Bonjour 不受此限制。若路由器隔离 mDNS，则退化为**手动输入 IP:端口**（Host 页面直接显示）。
6. **AI 只允许一方运行。** 两端引擎实现完全不同（Android 是独立进程 GTP，iOS 是静态链接 C++ bridge，等级映射也不同），人机联机时由 Host 侧引擎落子，然后**把结果当作普通 move 广播**，Guest 端永不计算。
7. **新增依赖：0 个。** Android 用系统 `NsdManager` + `java.net` + `org.json` + `MessageDigest`；iOS 用 `Network.framework` + `CryptoKit` + `Foundation`。

推荐分 5 个阶段落地，先做「同平台 + 手动 IP」，再做「Bonjour 发现」，最后做「制作钟/读秒/断线重连」。

---

## 1. 两端现状梳理

### 1.1 工程与构建

| 项目 | Android | iOS |
|---|---|---|
| 工程形态 | Gradle，单 `app` 模块，纯 Java | XcodeGen（`project.yml`）→ Xcode 工程，Swift 5.7 |
| 源码位置 | `app/src/main/java/com/sijia/go/` | `QingYi/{App,Core,UI,Engines}/` |
| UI 框架 | **经典 View/Activity + XML 布局**（非 Compose） | **SwiftUI**（`@StateObject`/`ObservableObject`） |
| 最低系统 | `minSdk 28`（Android 9），`targetSdk 34` | `iOS 16.0` |
| 版本 | `versionCode 4` / `1.1.2` | `1.0.0` |
| 现有权限 | 无任何权限声明 | 无本地网络/Bonjour 声明 |
| 现有网络代码 | **无**（grep `Socket/NsdManager/java.net` 无命中） | **无**（grep `URLSession/NWConnection/NetService/Multicast` 无命中） |
| 序列化 | 仅 SGF 读写（`Sgf.java`） | 仅 SGF 读写（`Sgf.swift`） |

关键文件：

- Android 棋局/规则：`app/src/main/java/com/sijia/go/GoGame.java`
- Android 对局页：`app/src/main/java/com/sijia/go/GameActivity.java`
- Android 棋盘绘制：`app/src/main/java/com/sijia/go/GoBoardView.java`
- Android 首页入口：`app/src/main/java/com/sijia/go/MainActivity.java`（`btnLocal` 在 L48–L53）
- iOS 棋局/规则：`QingYi/Core/GoGame.swift`
- iOS 对局模型：`QingYi/UI/GoGameModel.swift`
- iOS 对局页：`QingYi/UI/GoGameView.swift`
- iOS 首页入口：`QingYi/UI/MainView.swift`（`openEntry` 在 L226–L235）

### 1.2 棋局状态表示

| 维度 | Android `GoGame` | iOS `GoGame` | 结论 |
|---|---|---|---|
| 棋盘 | `int[size][size]`，`EMPTY=0/BLACK=1/WHITE=2` | `[[GoColor]]`，`.empty=0/.black=1/.white=2` | 一致 |
| 坐标 | `(0,0)` 左上，x 右，y 下，`board[y][x]` | 同 | 一致 |
| 尺寸 | 构造时 `clamp(2,25)`，`SIZE=19` 默认 | 同 | 一致（UI 都用 19） |
| 行棋方 | `turn: int` | `turn: GoColor` | 一致 |
| 劫点 | `koX, koY`（-1 表示无） | `ko: GoPoint?` | 一致 |
| 着法历史 | `Deque<Move> history`，**`push()` → 头是最后一手** | `[GoMove]`，**append → 尾是最后一手** | **顺序相反** |
| 着法内容 | `x,y,color,captured[],koX,koY` | `point,color,captured[],koPoint` | 语义一致 |
| 让子 | `placeHandicap(points)`，>0 则白先 | 同 | 一致 |
| 摆子 | `setup(black,white,empty,toMove)` | 同 | 一致 |
| 贴目 | `double komi=7.5`，`setKomi()` | `var komi=7.5` | 一致 |
| 数子 | `chineseScore()` → `float[]{黑,白}` | `chineseScore()` → `(black,white)` | 算法一致 |
| 地域图 | UI 内联复算 | `territoryMap()` | 结果一致 |
| 判定终局 | 无状态位，靠 UI 弹窗 | `resultText` 状态 | 需协议层统一 |

### 1.3 规则实现差异清单（必须处理）

| # | 差异点 | Android 现状 | iOS 现状 | 影响 | 统一建议 |
|---|---|---|---|---|---|
| D1 | 历史顺序 | `history.poll()` 取最新（栈） | `history.last` 取最新（数组） | 直接按历史序列化会颠倒 | 协议用 `moveNo` 递增序号；**禁止**按数组/栈顺序解释 |
| D2 | `moveCount` | `handicap + 盘面棋子数`（不含被提，含摆子） | `history` 中 `color != empty` 的条数（含 pass） | 数值不同 | LAN 中**不使用** `moveCount` 作为手数，改用协议 `moveNo` |
| D3 | 让子摆点来源 | 人机模式调用引擎 `fixed_handicap`，解析 GTP 返回 | 本地 `GoGameModel.handicapPoints(size:count:)` 查表 | 同尺寸/同让子数下摆点可能不同 | **Host 生成并在 `game_start` 中显式下发 `handicapPoints`**；Guest 直接 `placeHandicap`，不自行计算 |
| D4 | 两停一手自动结束 | `GameActivity` 只有 `gnu` 模式在 `lastTwoPass()` 后 `onScore()`；`local` 模式不自动结束 | `afterMove()` 所有模式两 pass → `finishByScore()` | 终局时机不同 | Host 统一判定两 pass → `game_over`；两端 UI 只显示结果 |
| D5 | 数子结果表达 | `float[]`，无和棋分支（相等时判白胜） | `Double`，`abs(diff)<0.5` 判和棋 | 极少数和棋结果不一致 | `game_over` 由 Host 计算并显式给出 `result`（含 `draw`），Guest 只展示 |
| D6 | 认输方判定 | 本地双人：`game.turn()` 的对家获胜，即当前行棋方认输 | AI 模式按 `playerColor`；本地双人按 `turn` | 语义接近但实现路径不同 | 协议显式携带 `resigner`（`B`/`W`），不靠 `turn` 推断 |
| D7 | 悔棋规则 | 人机悔 2 手，本地双人悔 1 手 | 同 | 联机不能单方面悔棋 | 改为**请求—同意**协议（`undo_request` / `undo_response`） |
| D8 | 劫规则 | 简单劫（simple ko），无超级劫 | 同 | 一致，但都不含三劫循环 | MVP 声明规则集 `chinese-simple-ko`；提交非法手由 Host 拒绝 |
| D9 | 自杀 | `hasLibertyAfterPlay` 禁止自杀 | 同 | 一致 | 无需处理 |
| D10 | 棋盘尺寸 UI | 对局固定 19（`engine.boardsize(19)`），导入棋谱可用其他尺寸 | 同 | LAN 首期只做 19 | 协议保留 `size` 字段，MVP 固定 19 |
| D11 | SGF 保存 | `history()` 是新→旧，`save()` 内 `Collections.reverse()` | 直接按数组顺序 | 各自的 SGF 都正确，但不能直接混用内部结构 | LAN 存谱走各自现有 `Sgf.save`，输入统一为「已排序着法列表」 |
| D12 | 引擎运行方式 | 独立可执行文件（`jniLibs/libgnugo.so` 等）经 `ProcessBuilder` 走 GTP 管道 | `Pachi/KataGo/Pikafish` 静态库 + `*Bridge.cpp` 进程内调用 | 结果不可复现 | 见 §5：只有 Host 跑引擎 |

> 小结：**棋局数据结构与核心合法性判定可以直接复用**，不需要重写规则引擎；需要新写的是「协议层 + 权威会话 + 网络传输」，以及少量 UI 接入。

### 1.4 AI 引擎现状

| 项目 | Android | iOS |
|---|---|---|
| 方式 | 子进程 + GTP 文本管道（`GtpEngine.java`） | 静态链接 C++（`KataGoBridge/PachiBridge/PikafishBridge`） |
| 可选引擎 | GNU Go / Pachi / KataGo | 1–3 级 `GoAI`（启发式 Swift）、4–6 级 Pachi、7–10 级 KataGo |
| 等级映射 | GNU `level L`；Pachi `-t =1000*L`；KataGo `maxVisits=50*L` | 1–3 = GoAI，4–6 = Pachi，7–10 = KataGo |
| 通信 | 行缓冲读写，`send("genmove B")` | 直接函数调用，同步返回着法 |

结论：**同一等级下两端走子不会相同**，联机时绝不能两端各自算 AI。

### 1.5 UI 与导航入口

- Android 首页三个入口（`activity_main.xml`）：`btnGnu` 人机 / `btnLocal` 本地双人 / `btnSgf` 棋谱。`MainActivity.java` L48–L53 直接 `startActivity(GameActivity, mode="local")`。
- iOS 首页三个入口（`MainView.swift` L201–L235）：索引 1「本地双人」直接 `GoSetup(mode: .local)`，通过 `.fullScreenCover(item: $goSetup)` 打开 `GoGameView`。
- 两端对局页都已有完整按钮：悔棋 / 停一手 / 形势 / 数子 / 认输 / 存谱。**联机模式复用同一套 UI**，只替换"谁有权落子/谁裁决"。

---

## 2. 方案对比与推荐

### 2.1 候选方案

| 方案 | 发现机制 | 传输 | 实现成本 | 跨平台可靠性 | 调试难度 | 第三方依赖 | 结论 |
|---|---|---|---|---|---|---|---|
| **A. Bonjour/NSD + TCP + 长度前缀 JSON** | mDNS/DNS-SD（两端系统 API） | TCP | 中 | **高**（标准协议，三组合通用） | 中（可用 `dns-sd`/日志抓包） | 0 | **推荐** |
| B. UDP 广播/组播发现 + TCP | 自定义 UDP 广播 | TCP | 中低 | 低 | 高 | 0 | 否决：iOS 原始组播需特批 entitlement；Android 需 `MulticastLock`；多数路由器/热点丢弃组播 |
| C. iOS 用 MultipeerConnectivity + Android 用 Bonjour/NSD | MC（AWDL） | MC 自带 | iOS 低、跨平台不可行 | **不可行** | — | 0 | 否决：Android 无法参与，必须两套协议 |
| D. 局域网 HTTP/WebSocket 服务 | 手动 IP 或 NSD | WS | 中高 | 中 | 中 | OkHttp / Starscream 等 | 否决：多引依赖，且对 1v1 小消息无收益 |
| E. Google Nearby Connections / 第三方联机 SDK | 各家 | 各家 | 高 | 中（多为单平台优先） | 高 | 重 | 否决：违背"尽量不引入重型框架" |
| F. 互联网中转（Firebase/Supabase/自建 WS） | — | WSS | 低 | 高 | 低 | 有 | 本期否决：用户明确要求本地联机；可作为未来"远程对战"扩展 |

### 2.2 推荐架构：Host 权威（Authoritative Host）

```
┌────────────────────────┐            TCP (JSON frames)          ┌────────────────────────┐
│   Android / iOS  Host  │◀─────────────────────────────────────▶│  Android / iOS  Guest  │
│  ─ GoGame (权威)        │                                        │  ─ GoGame (镜像)        │
│  ─ LanClock (权威)      │                                        │  ─ LanClock (插值显示)  │
│  ─ AI 引擎（可选）       │                                        │  ─ 不跑 AI              │
│  ─ TCP ServerSocket     │                                        │  ─ TCP Client           │
│  ─ mDNS 注册 _qingyi-go │                                        │  ─ mDNS 浏览 _qingyi-go │
└────────────────────────┘                                        └────────────────────────┘
```

为什么 Host 权威更简单可靠：

- **单一事实来源**：所有落子、悔棋、认输、时钟、终局判定都在 Host 一处完成，不需要处理"两端同时认为对方非法"的分布式冲突。
- **规则分歧可收敛**：Guest 先本地预演（可选），Host 以自己 `GoGame` 为准；不一致时 Host 下发 `state_snapshot` 强制对齐。
- **时钟只有一方计时**，网络抖动只影响显示插值，不影响判负。
- **AI 只有一方运行**，天然避免两端引擎结果不一致。
- 代价：Host 掉线整局中断。通过「断线重连宽限期 + 超时判负」处理（§4.4），对局域网场景可接受。

### 2.3 发现：Bonjour / DNS-SD

- Android：`android.net.nsd.NsdManager`（系统内置 mDNS responder/browser）。
- iOS：`Network.framework` 的 `NWBrowser` / `NWListener.service`（或 `NetService`）。
- 两者都基于标准 mDNS/DNS-SD，可互相发现。**不要**在 Android 侧手写 mDNS，也**不要**在 iOS 侧用 MultipeerConnectivity。

服务定义：

| 项 | 值 |
|---|---|
| 服务类型 | `_qingyi-go._tcp`（Android 常量需带尾点：`"_qingyi-go._tcp."`） |
| 服务实例名 | `QY-<roomCode>-<size>-<komi>-<state>-<encName>`（ASCII，便于两端解析，且避开 Android 对 TXT 属性 API 的版本差异） |
| 端口 | 优先 `41234`，被占用则依次尝试 `41235..41244`；实际端口写在服务注册信息中 |
| `roomCode` | 4 位 Crockford Base32（去掉 I/L/O/U），如 `7K2M` |
| `state` | `idle` / `playing` / `full` |
| `encName` | Host 昵称的 URL 百分号编码（UTF-8） |

> 不用 TXT 记录的原因：`NsdServiceInfo.setAttribute()` 在部分 Android 版本是 `@SystemApi` 或行为不一致，跨版本风险高；把元数据放进**实例名**是两端都稳定可读的最小方案。

### 2.4 传输与端口

- 协议：**TCP**，仅 IPv4（同网段场景足够；IPv6 后续再加）。
- 帧格式：`uint32 length (big-endian)` + `length` 字节 UTF-8 JSON。单帧上限 `64 KiB`（聊天 1 KiB）。
- 默认端口：`41234`；Host 启动时从 `41234` 起顺延寻找可用端口，并把真实端口显示在 UI 上，供手动加入。
- 不做 TLS：局域网内明文 + 4 位房间码做入房校验；如后续需要，可在 TCP 上叠加预共享密钥的 AES-GCM（本期不做）。

### 2.5 网络环境约束

| 场景 | 是否支持 | 说明 |
|---|---|---|
| 同一 Wi-Fi（同一 Subnet） | ✅ | 需关闭路由器的「AP 隔离 / 客户端隔离」 |
| 一方开热点，另一方连接（Android 热点） | ✅（推荐手动 IP） | 热点常屏蔽组播 → Bonjour 可能失败；Host 显示 `192.168.43.1:41234` 供手动输入 |
| iPhone 个人热点 | ⚠️ | 客户端可访问 iPhone（`172.20.10.1`），但组播常不通；走手动 IP |
| 不同网段 / 跨路由器 | ❌ | 无路由发现能力，必须同一广播域 |
| 开启 VPN | ⚠️ | 可能改变默认路由导致连不上；建议提示用户关闭 VPN |
| 移动数据 | 不影响 | TCP 走局域网地址 |

---

## 3. 协议设计

### 3.1 通用信封

所有消息都是 JSON 对象：

```json
{
  "v": 1,
  "t": "move",
  "sid": "9f1c2a7e",
  "ts": 1762473600123,
  "seq": 17,
  "room": "7K2M",
  "from": "guest",
  "p": { }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `v` | int | ✅ | 协议版本，本期固定 `1`；不等则拒绝并提示升级 |
| `t` | string | ✅ | 消息类型，见 §3.3 |
| `sid` | string | ✅ | 消息 ID（8–16 位十六进制），用于请求/响应关联 |
| `ts` | int64 | ✅ | 发送方时间戳（Unix ms），仅用于日志与时钟估计 |
| `seq` | int64 | ✅ | 每条连接内单调递增，用于丢序检测 |
| `room` | string | ✅ | 房间码 |
| `from` | string | ✅ | `host` / `guest` |
| `p` | object | ✅ | 负载，按类型定义；无负载用 `{}` |

### 3.2 帧编解码

Android（`FrameIO.java`）：

```java
static void writeFrame(OutputStream out, JSONObject msg) throws IOException {
    byte[] body = msg.toString().getBytes(StandardCharsets.UTF_8);
    if (body.length > 65536) throw new IOException("frame too large");
    DataOutputStream d = new DataOutputStream(out);
    d.writeInt(body.length);          // DataOutputStream 默认大端
    d.write(body);
    d.flush();
}

static JSONObject readFrame(InputStream in) throws Exception {
    DataInputStream d = new DataInputStream(in);
    int len = d.readInt();
    if (len <= 0 || len > 65536) throw new IOException("bad frame len " + len);
    byte[] body = new byte[len];
    d.readFully(body);
    return new JSONObject(new String(body, StandardCharsets.UTF_8));
}
```

iOS（`LanFrame.swift`，基于 `NWConnection` 的 `receive(minimumIncompleteLength:maximumLength:)` 累积缓冲）：

```swift
enum LanFrame {
    static func encode(_ obj: [String: Any]) throws -> Data {
        let body = try JSONSerialization.data(withJSONObject: obj, options: [])
        precondition(body.count <= 65_536)
        var len = UInt32(body.count).bigEndian
        var out = Data(bytes: &len, count: 4)
        out.append(body)
        return out
    }
}

// 接收侧：维护 buffer，凑满 4 字节读长度，再凑满 body 解析；回调在主队列投递
```

### 3.3 消息清单

| 类型 | 方向 | 作用 | MVP |
|---|---|---|---|
| `hello` | Guest→Host | 握手、房间码校验、声明能力 | ✅ |
| `hello_ack` | Host→Guest | 握手成功、分配座次 | ✅ |
| `hello_reject` | Host→Guest | 拒绝（房间码错/满员/版本不符） | ✅ |
| `lobby` | Host→Guest | 房间信息、双方昵称、准备状态 | ✅ |
| `ready` | Guest→Host | 客人准备就绪 | ✅ |
| `game_start` | Host→Guest | 开局参数与摆子（权威） | ✅ |
| `move` | 双方→Host | 落子/停一手意图 | ✅ |
| `move_ack` | Host→Guest | 裁决通过 + 权威 hash | ✅ |
| `move_reject` | Host→Guest | 裁决拒绝 + 原因 + 权威状态 | ✅ |
| `state_snapshot` | Host→Guest | 全量状态对齐（重连/分歧） | ✅ |
| `undo_request` | 双向 | 悔棋请求 | ✅ |
| `undo_response` | 双向 | 悔棋应答 | ✅ |
| `resign` | 双向 | 认输（显式 resigner） | ✅ |
| `draw_offer` | 双向 | 求和 | ✅ |
| `draw_response` | 双向 | 应答求和 | ✅ |
| `clock` | Host→Guest | 权威时钟快照 | ✅ |
| `ping` / `pong` | 双向 | 心跳与 RTT/时偏估计 | ✅ |
| `game_over` | Host→Guest | 终局结果 | ✅ |
| `chat` | 双向 | 简短聊天 | 可选 |
| `bye` | 双向 | 主动离房 | ✅ |

### 3.4 关键消息字段表

#### `hello`（Guest→Host）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `protocolVersion` | int | ✅ | 固定 1 |
| `roomCode` | string | ✅ | 用户输入/从 Bonjour 实例名解析 |
| `deviceName` | string | ✅ | 昵称，≤16 字符 |
| `platform` | string | ✅ | `android` / `ios` |
| `appVersion` | string | ✅ | 如 `1.1.2` / `1.0.0` |
| `resumeToken` | string | ❌ | 断线重连时携带，见 §4.4 |

#### `hello_ack`（Host→Guest）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `ok` | bool | ✅ | 恒 true（失败走 `hello_reject`） |
| `gameId` | string | ✅ | 本局 UUID |
| `seat` | int | ✅ | `1`（Guest 座次，固定 1） |
| `yourColor` | string | ✅ | `B` / `W` |
| `opponentName` | string | ✅ | Host 昵称 |
| `hostPlatform` | string | ✅ | `android` / `ios` |
| `ruleset` | string | ✅ | `chinese-simple-ko` |
| `serverTime` | int64 | ✅ | Host 当前时间，用于时偏估计 |
| `resumeToken` | string | ✅ | 本次会话令牌，重连用 |

#### `game_start`（Host→Guest）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `gameId` | string | ✅ | |
| `size` | int | ✅ | MVP 固定 19 |
| `komi` | double | ✅ | 默认 7.5 |
| `handicap` | int | ✅ | 让子数，0 表示不让 |
| `handicapPoints` | `[[x,y],...]` | ✅ | **权威摆子坐标**；无让子为 `[]` |
| `ruleset` | string | ✅ | `chinese-simple-ko` |
| `toMove` | string | ✅ | `B` / `W` |
| `seats` | array | ✅ | `[{"seat":0,"name":"...","color":"B","kind":"human|ai"}]` |
| `timeControl` | object | ✅ | 见下 |
| `startAtHostMs` | int64 | ✅ | 开局时刻（Host 单调时钟映射到 Unix ms） |

`timeControl`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `mode` | string | `none` / `byoyomi` |
| `mainSec` | int | 每方保留时间（秒），`none` 时忽略 |
| `byoYomiSec` | int | 读秒每段秒数 |
| `periods` | int | 读秒段数 |
| `incrementSec` | int | 每手加秒（MVP 固定 0） |

#### `move`（双方→Host）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `moveNo` | int | ✅ | 期望的下一手序号，从 1 开始递增（含 pass） |
| `color` | string | ✅ | 发送方认为的行棋方 `B`/`W` |
| `x` | int | ✅* | 落子列；`pass` 时省略或 `-1` |
| `y` | int | ✅* | 落子行；`pass` 时省略或 `-1` |
| `pass` | bool | ❌ | `true` 表示为停一手 |
| `hashBefore` | string | ✅ | 发送方在落子前的棋盘 hash（十六进制） |

#### `move_ack`（Host→Guest）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `moveNo` | int | ✅ | 已接受的手数 |
| `hash` | string | ✅ | Host 落子后的权威 hash |
| `captured` | int | ✅ | 本手提子数（用于 Guest 校验/动画） |
| `nextTurn` | string | ✅ | `B` / `W` |
| `clock` | object | ❌ | 顺带下发时钟快照，减少消息数 |

#### `move_reject`（Host→Guest）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `moveNo` | int | ✅ | 被拒的期望手数 |
| `code` | string | ✅ | 见 §3.7 |
| `message` | string | ✅ | 面向用户的中文提示 |
| `authoritativeMoveNo` | int | ✅ | Host 当前手数 |
| `hash` | string | ✅ | Host 当前 hash |
| `snapshot` | object | ❌ | 分歧较大时直接附全量状态 |

#### `state_snapshot`（Host→Guest）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `gameId` | string | ✅ | |
| `moveNo` | int | ✅ | 已走手数 |
| `size` | int | ✅ | |
| `komi` | double | ✅ | |
| `toMove` | string | ✅ | |
| `ko` | `[x,y]` / `null` | ✅ | 当前劫点 |
| `board` | string | ✅ | 行优先，`/` 分行，字符 `0/1/2`；如 19 路为 19×19 |
| `hash` | string | ✅ | 与 `board` 一致 |
| `result` | string / null | ❌ | 已终局时给出 |
| `clock` | object | ❌ | 当前时钟 |

#### `undo_request` / `undo_response`

| 字段 | 类型 | 说明 |
|---|---|---|
| `count` | int | 请求悔的手数：1 或 2 |
| `moveNo` | int | 请求方当前手数 |
| `accept` | bool | 仅响应消息使用 |
| `hash` | string | 应答后 Host 权威 hash |

#### `resign` / `draw_offer` / `draw_response`

| 字段 | 类型 | 说明 |
|---|---|---|
| `by` | string | `B` / `W`，认输/求和发起方 |
| `accept` | bool | 仅 `draw_response` |

#### `clock`（Host→Guest）

| 字段 | 类型 | 说明 |
|---|---|---|
| `turn` | string | 当前行棋方 |
| `blackRemainMs` | int64 | 黑方剩余（保留时间 + 当前读秒段） |
| `whiteRemainMs` | int64 | 白方剩余 |
| `blackPeriods` | int | 黑方剩余读秒段数 |
| `whitePeriods` | int | 白方剩余读秒段数 |
| `running` | bool | 是否在计时（断线暂停时为 false） |
| `moveNo` | int | 该时钟对应手数 |
| `serverTime` | int64 | Host 发送时刻 |

#### `game_over`（Host→Guest）

| 字段 | 类型 | 说明 |
|---|---|---|
| `result` | string | `B+R` / `W+R` / `B+score` / `W+score` / `draw` / `B+T` / `W+T` / `B+disconnect` / `W+disconnect` |
| `winner` | string | `B` / `W` / `none` |
| `reason` | string | `resign` / `score` / `timeout` / `draw` / `disconnect` |
| `blackScore` | double | 数子结果（子空皆地，不含贴目） |
| `whiteScore` | double | 含贴目后的白方总分 |
| `komi` | double | |
| `moveNo` | int | 终局时手数 |
| `detail` | string | 中文描述，可直接显示 |

#### `ping` / `pong`

| 字段 | 类型 | 说明 |
|---|---|---|
| `t0` | int64 | 发起方发送时刻（仅 `ping`） |
| `t1` | int64 | 接收方回填的收到时刻 |
| `serverTime` | int64 | 回复方当前时间 |

### 3.5 状态机

```
[Idle]
  ├─ Host: 创建房间 → [Advertising] → 等待连接
  └─ Guest: 浏览/输入 IP → [Connecting] → hello

[Advertising] ──accept──▶ [Handshaking]
[Connecting] ──TCP ok──▶ hello ──▶ [Handshaking]

[Handshaking] ──hello_ack──▶ [Lobby]
[Handshaking] ──reject────▶ [Idle] (提示原因)

[Lobby] ──双方 ready + Host 点开始──▶ [Playing]

[Playing] ──move/move_ack──▶ [Playing]
[Playing] ──undo_request/response──▶ [Playing]
[Playing] ──resign/draw/timeout──▶ [Finished]
[Playing] ──连接断开──▶ [Suspended] ──重连成功──▶ [Playing]
                                   └─宽限期超时──▶ [Finished] (判负)

[Finished] ──双方确认/返回──▶ [Idle]
```

### 3.6 棋盘哈希（防不同步）

必须两端算法完全一致，否则每次落子都会误报分歧。

**规范化字符串（canonical string）**：

```
1|SZ=<size>|M=<moveNo>|T=<B|W>|K=<koX>,<koY>|<board>
```

- `board`：行优先（y 从 0 到 size-1），每行 x 从 0 到 size-1，字符 `0`=空、`1`=黑、`2`=白；行间用 `/` 连接。
- `ko`：无劫点写 `-1,-1`。
- `moveNo`：**已执行的手数**（含 pass），开局为 0。
- 对该字符串的 UTF-8 字节做 **SHA-256**，输出**小写十六进制**。

Android 实现（`BoardHash.java`）：

```java
static String hash(GoGame g, int moveNo) {
    StringBuilder sb = new StringBuilder();
    sb.append("1|SZ=").append(g.size())
      .append("|M=").append(moveNo)
      .append("|T=").append(g.turn() == GoGame.BLACK ? 'B' : 'W')
      .append("|K=").append(g.koX()).append(',').append(g.koY()).append('|');
    for (int y = 0; y < g.size(); y++) {
        if (y > 0) sb.append('/');
        for (int x = 0; x < g.size(); x++) sb.append((char) ('0' + g.board()[y][x]));
    }
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] d = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : d) hex.append(String.format("%02x", b));
    return hex.toString();
}
```

> Android 现有 `GoGame` 没有公开 `koX/koY`，需新增两个只读访问器（`public int koX()` / `public int koY()`），不改动现有逻辑。

iOS 实现（`BoardHash.swift`）：

```swift
import CryptoKit

enum BoardHash {
    static func hash(_ game: GoGame, moveNo: Int) -> String {
        var s = "1|SZ=\(game.size)|M=\(moveNo)"
        s += "|T=\(game.turn == .black ? "B" : "W")"
        s += "|K=\(game.ko?.x ?? -1),\(game.ko?.y ?? -1)|"
        for y in 0..<game.size {
            if y > 0 { s += "/" }
            for x in 0..<game.size { s += String(game.colorAt(x, y).rawValue) }
        }
        let digest = SHA256.hash(data: Data(s.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
```

**标准测试向量（两端单测必须相等）**：

| 场景 | canonical string | 期望 SHA-256 |
|---|---|---|
| 19 路空盘，黑先，无劫，moveNo=0 | `1\|SZ=19\|M=0\|T=B\|K=-1,-1\|` + 19 行 `0000000000000000000`（`/` 连接） | `8894e497dba2729d54090c287f8fb65cf5694a03e4bc0019868ff570c068c764` |
| 19 路，黑在 (3,3) 落一子，白先，moveNo=1 | 同上，第 4 行变为 `0001000000000000000`，`T=W` | `cc306d27d03fd4e869c39b23f7e20ed8de33bb581d34f31981dae012dd9f1c5b` |

用途：

- 每条 `move` 携带 `hashBefore`，Host 比对不上 → `E_HASH_MISMATCH` + 下发快照。
- 每次 `move_ack` 携带权威 `hash`，Guest 落子后自算，不一致立即请求快照（说明两端规则实现已分歧，属于必须修的红线 bug）。
- **哈希只用于同步校验，不作为防作弊手段**（Guest 可以构造任意 hash）；防作弊靠 Host 权威裁决。

### 3.7 错误码

| 码 | 场景 | 处理 |
|---|---|---|
| `E_PROTO_VERSION` | `v` 不符 | 断开并提示升级 |
| `E_BAD_FRAME` | 长度/JSON 非法 | 断开该连接 |
| `E_ROOM_CODE` | 房间码不匹配 | `hello_reject`，允许重试 |
| `E_ROOM_FULL` | 已有 Guest | 拒绝 |
| `E_ROOM_STATE` | 房间不在可加入状态 | 拒绝 |
| `E_NOT_STARTED` | 未开局就发 move | 忽略/提示 |
| `E_GAME_OVER` | 终局后操作 | 忽略 |
| `E_OUT_OF_TURN` | `color` 不是当前行棋方 | 拒绝 + 快照 |
| `E_STALE_MOVE` | `moveNo` 不等于期望值 | 拒绝 + 权威手数 |
| `E_OCCUPIED` | 该点已有子 | 拒绝 |
| `E_KO` | 劫点禁入 | 拒绝 |
| `E_SUICIDE` | 自杀手 | 拒绝 |
| `E_HASH_MISMATCH` | `hashBefore` 不符 | 拒绝 + `state_snapshot` |
| `E_UNDO_NOT_ALLOWED` | 悔棋请求不合法 | 拒绝 |
| `E_TIMEOUT` | 时钟用尽（内部判定） | `game_over` |

### 3.8 两端规则分歧导致的非法落子分歧：处理策略

1. **Host 是唯一裁判**。Guest 本地 `game.play()` 返回非空只是"乐观预演"，不等于合法。
2. Guest 发送 `move` 时附带 `hashBefore`；Host 先比 hash：
   - 相等 → 在自己 `GoGame` 上 `play()`，成功则广播 `game_start` 之后的 `move_ack`，失败则 `move_reject`（带具体原因码）。
   - 不相等 → 先下发 `state_snapshot`，再让 Guest 重新发起该手。
3. Guest 收到 `move_ack`：
   - 若本地已乐观落子：用 `captured` 与 `hash` 校验；不一致则以 Host 为准重建（`state_snapshot` 或本地 undo + 重放）。
   - 若本地未落子：直接应用。
4. Guest 收到 `move_reject`：
   - 回滚到 `authoritativeMoveNo`（本地 undo 或直接应用快照）。
   - 用 `message` 提示用户（如"此处打劫，暂不能落子"）。
5. **红线**：如果 `hash` 在合法手后频繁不一致，说明两端规则实现有差异（例如超时/提子顺序/劫判定），必须视为阻断发布的 bug，而不是用快照"掩盖"。

### 3.9 示例会话（Android Host ↔ iOS Guest）

```json
{"v":1,"t":"hello","sid":"a1","ts":1762473600000,"seq":1,"room":"7K2M","from":"guest",
 "p":{"protocolVersion":1,"roomCode":"7K2M","deviceName":"iPad","platform":"ios","appVersion":"1.0.0"}}

{"v":1,"t":"hello_ack","sid":"b1","ts":1762473600030,"seq":1,"room":"7K2M","from":"host",
 "p":{"ok":true,"gameId":"g-9f3","seat":1,"yourColor":"W","opponentName":"Pixel","hostPlatform":"android",
      "ruleset":"chinese-simple-ko","serverTime":1762473600030,"resumeToken":"rt-77"}}

{"v":1,"t":"game_start","sid":"b2","ts":1762473605000,"seq":2,"room":"7K2M","from":"host",
 "p":{"gameId":"g-9f3","size":19,"komi":7.5,"handicap":0,"handicapPoints":[],
      "ruleset":"chinese-simple-ko","toMove":"B",
      "seats":[{"seat":0,"name":"Pixel","color":"B","kind":"human"},
               {"seat":1,"name":"iPad","color":"W","kind":"human"}],
      "timeControl":{"mode":"byoyomi","mainSec":600,"byoYomiSec":30,"periods":3,"incrementSec":0},
      "startAtHostMs":1762473605000}}

{"v":1,"t":"move","sid":"a2","ts":1762473609000,"seq":2,"room":"7K2M","from":"guest",
 "p":{"moveNo":1,"color":"B","x":3,"y":15,"hashBefore":"8894e497dba2729d54090c287f8fb65cf5694a03e4bc0019868ff570c068c764"}}

{"v":1,"t":"move_ack","sid":"b3","ts":1762473609020,"seq":3,"room":"7K2M","from":"host",
 "p":{"moveNo":1,"hash":"cc306d27d03fd4e869c39b23f7e20ed8de33bb581d34f31981dae012dd9f1c5b",
      "captured":0,"nextTurn":"W",
      "clock":{"turn":"W","blackRemainMs":599500,"whiteRemainMs":600000,
               "blackPeriods":3,"whitePeriods":3,"running":true,"moveNo":1,"serverTime":1762473609020}}}
```

---

## 4. 时钟 / 读秒 / 断线

### 4.1 权威计时方

- **只有 Host 计时**，Guest 只做显示插值与超时预警（不能判负）。
- Host 使用**单调时钟**（Android `SystemClock.elapsedRealtime()`，iOS `DispatchTime.now().uptimeNanoseconds`）计费，避免系统时间被修改。
- 消息里的 `ts`/`serverTime` 用 Unix ms，仅用于时偏估计与日志。

### 4.2 计时算法（Host）

```
onGameStart:  lastTurnStart = now; chargeColor = toMove
onMoveAccepted:
    elapsed = now - lastTurnStart
    charge(currentPlayer, elapsed)
    switchTurn()
    lastTurnStart = now
    broadcastClock()
onCharge(color, elapsed):
    if mainMs > 0:
        if elapsed < mainMs: mainMs -= elapsed; return
        elapsed -= mainMs; mainMs = 0; periodsLeft = periods
    # 进入读秒
    while elapsed >= byoYomiMs and periodsLeft > 1:
        elapsed -= byoYomiMs; periodsLeft -= 1
        # 每超一段消耗一段；最后一段超时即判负
    if elapsed >= byoYomiMs:  # 最后一段也用尽
        gameOver(color == B ? "W+T" : "B+T", reason="timeout")
    else:
        remainInPeriod = byoYomiMs - elapsed
```

简化建议（MVP）：`mode=byoyomi` 时，**每手落子后读秒段自动重置**（保留 `periods` 段结构，但只有"整段超时"才扣段）；先实现"保留时间 + 单段读秒"，多段读秒作为 Phase 4。

### 4.3 网络抖动处理

- Guest 收到 `clock` 后记录 `(serverTime, remainMs)`，本地以单调时钟递减显示。
- Guest 每 5s 发 `ping`，Host 回 `pong {t0,t1,serverTime}`；Guest 计算：

```
rtt   = now - t0
offset = ((t1 - t0) + (t1' - now)) / 2     // 近似单向时偏
displayRemain = remainMs - (nowLocal + offset - serverTime)
```

- 收到新 `clock` 时**直接覆盖**本地显示（以权威为准），不做平滑，避免累计漂移；小于 300ms 的抖动可忽略（不重绘）。
- Host 在 `clock` 中下发 `running=false` 时，Guest 停止本地递减并显示"已暂停"。

### 4.4 断线重连与超时判负

| 项 | 规则 |
|---|---|
| 心跳 | 双方每 5s `ping`；连续 3 次（15s）无 `pong` 视为断开 |
| Host 侧处理 | 标记 Guest `Suspended`，**暂停双方时钟**（不计费），启动 `reconnectGraceSec`（默认 120s）倒计时 |
| Guest 侧处理 | 自动重连：用 `resumeToken` + `roomCode` 重新连接，收到 `hello_ack(resumed=true)` 后请求 `state_snapshot` |
| Guest 重连成功 | Host 恢复时钟（`lastTurnStart = now`），广播 `clock`，回到 `Playing` |
| Host 掉线 | Guest 进入 `Suspended`，每 3s 尝试重连（Host 端口不变）；超过宽限期显示"连接中断"，允许保存当前棋谱后退出 |
| 宽限期到 | Host 判 `W+disconnect` / `B+disconnect` 并广播（若 Guest 已不可达则仅本地落库） |
| 主动离开 | 发送 `bye` 后关闭；对局中主动离开等同认输（需二次确认） |

> 设计取舍：断线**暂停时钟**而不是继续走表，避免"网络抖动导致被判超时"的投诉；代价是双方可以借断线"拖时间"，局域网熟人场景可接受。若要更严格，可配置 `pauseOnDisconnect=false`。

### 4.5 超时判负的唯一权威

- 只有 Host 能产出 `game_over { reason:"timeout" }`。
- Guest 本地显示到 0 时只提示"读秒用尽，等待裁决"，不自行结束。

---

## 5. AI 参与模式

### 5.1 三种组合

| 模式 | Host | Guest | 说明 |
|---|---|---|---|
| 人人 | 人类 | 人类 | MVP 主场景 |
| 人机（Host 侧 AI） | AI | 人类 | Host 选引擎/等级，AI 执一色 |
| 人机（Guest 侧 AI） | 人类 | AI | Guest 选引擎/等级，Guest 本地算，结果仍经 Host 裁决 |

### 5.2 职责划分（硬规则）

1. **一局只有一个 AI 执行者**，在 `game_start.seats[].kind = "ai"` 中显式标注。
2. AI 走子流程（以 Host 侧 AI 为例）：
   ```
   Host 轮到 AI → 本地引擎 genmove（后台线程）→ 得到 (x,y)/PASS
                 → 走正常 move 流程（Host 自己作为权威直接 play）
                 → 广播 move / move_ack 给 Guest
   ```
   也就是说，**AI 的输出对协议而言就是一手普通棋**，Guest 完全不知道对面是 AI（除 UI 标识外）。
3. Guest 侧 AI：
   ```
   Guest 轮到 AI → 本地引擎 genmove → 发送 move（带 hashBefore）
                 → Host 裁决 → move_ack / move_reject
   ```
   如果被拒绝（理论上不该发生），Guest 回退并重新 genmove（最多重试 2 次，仍失败则 PASS）。
4. **禁止**两端同时对同一手 genmove；`seats[].kind` 不一致直接拒绝开局。
5. 等级/引擎参数只在"AI 所在端"生效，协议只携带 `kind` 与展示用 `engineLabel`，不携带引擎参数（避免两端映射差异）。

### 5.3 具体接入点

- Android：`GameActivity.startEngineGame()` / `engineMove()` 仅在 `mode="lan" && mySeatIsAI` 时启用；Host 的 `LanSession` 在 `applyMove` 成功后触发 `maybeRunAI()`。
- iOS：`GoGameModel.startAI()` 仅在 `mode == .lan && localSeatIsAI` 时启用；Guest 的 AI 结果通过 `lan.sendMove()` 而不是直接 `game.play()`。

---

## 6. Android 端改造清单

### 6.1 新增文件

```
app/src/main/java/com/sijia/go/net/
├── Proto.java          # 消息类型常量、错误码、envelope 构造、JSON 读写辅助
├── FrameIO.java        # 4 字节长度前缀帧编解码
├── BoardHash.java      # 规范化 board string + SHA-256
├── LanClock.java       # 单调时钟计时、读秒、快照
├── LanSession.java     # 权威棋局：GoGame + moveNo + 座次 + 时钟 + 裁决
├── LanHost.java        # ServerSocket + accept 线程 + 每连接 Conn + 广播/断线宽限
├── LanClient.java      # TCP 连接 + 握手 + 收发 + 重连
├── LanDiscovery.java   # NsdManager 注册/浏览 + 解析实例名
├── LanHub.java         # 单例：持有 Host/Client/Session，跨 Activity 生命周期
└── NetUtil.java        # 本机 IPv4 枚举（WifiManager/NetworkInterface）
```

新增布局：

```
app/src/main/res/layout/activity_lan_lobby.xml     # 建房/加入/房间列表/手动 IP
app/src/main/res/layout/dialog_lan_host.xml        # 棋盘尺寸、贴目、让子、计时、执黑执白
```

### 6.2 关键类职责与签名

```java
// LanSession.java —— 唯一权威
public final class LanSession {
    public interface Listener {
        void onStateChanged();                       // 棋局/手数变化，UI 刷新
        void onMoveAccepted(int moveNo, GoGame.Move m);
        void onRejected(String code, String message);
        void onGameOver(String result, String detail);
        void onClock(LanClock.Snapshot s);
    }
    GoGame game(); int moveNo(); int mySeat(); int turnSeat();
    boolean applyLocalMove(int seat, int x, int y);  // Host 自己/受 Guest 请求调用
    boolean applyLocalPass(int seat);
    boolean applyRemoteMove(JSONObject p, Listener cb); // Guest→Host 的 move
    JSONObject snapshot();                           // state_snapshot 负载
    boolean undo(int count);
    void resign(int seat);
}
```

```java
// LanHost.java
public final class LanHost {
    void start(int preferredPort, String roomCode, String hostName, LanSession.Listener l);
    int port();
    String roomCode();
    void broadcast(JSONObject msg);
    void sendToGuest(JSONObject msg);
    void stop();
}
```

```java
// LanClient.java
public final class LanClient {
    interface Listener {
        void onConnected(JSONObject ack);
        void onMessage(JSONObject msg);
        void onDisconnected(String reason);
    }
    void connect(String host, int port, String roomCode, String name, String resumeToken, Listener l);
    void send(JSONObject msg);
    void close();
    int rttMs(); long serverOffsetMs();
}
```

### 6.3 现有文件改动点

| 文件 | 位置 | 改动 |
|---|---|---|
| `app/src/main/AndroidManifest.xml` | `<manifest>` 下 | 新增 `INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`（可选 `CHANGE_WIFI_MULTICAST_STATE` 仅在保留 UDP 兜底时加） |
| 同上 | `<application>` 内 | 注册 `.LanLobbyActivity`（`portrait`、`exported=false`） |
| `MainActivity.java` | L48–L53 `btnLocal` | 改为 `showPlayModeDialog()`：`本地双人` → 原逻辑；`局域网联机` → `LanLobbyActivity` |
| `MainActivity.java` | L170 `applyGameSection` | 入口 2 描述改为「同机双人 · 局域网联机」 |
| `GameActivity.java` | L22–L27 | 新增 `EXTRA_LAN_ROLE`(`host`/`guest`)、`EXTRA_LAN_ROOM`、`EXTRA_LAN_SEAT` |
| `GameActivity.java` | L51 `onCreate` | `mode="lan"` 分支：从 `LanHub` 取会话、注册监听、设置 `boardView`、禁用无权限按钮 |
| `GameActivity.java` | L139–L168 `onTap` | 新增 `lan` 分支：`if (game.turn() != myColor) return;` → `hub.requestMove(x,y)`（不直接 `game.play`） |
| `GameActivity.java` | L207–L223 `onUndo` | `lan`：`undo_request`，等待对方同意 |
| `GameActivity.java` | L225–L241 `onPass` | `lan`：`hub.requestPass()` |
| `GameActivity.java` | L447–L458 `onResign` | `lan`：二次确认后 `resign`（显式 `by=myColor`） |
| `GameActivity.java` | L436–L445 `onScore` | `lan`：请求 Host 结算 / 仅显示 Host 下发的 `game_over` |
| `GameActivity.java` | L460–L473 `onSave` | `lan`：黑白名用双方昵称；着法列表按 `moveNo` 排序后交给 `Sgf.save` |
| `GameActivity.java` | L573–L587 `statusText` / L567–L571 `leftText` | 新增 `lan`：显示「我方执黑 · 对手昵称 · 剩余时间」 |
| `GameActivity.java` | L640 `onDestroy` | `lan` 对局中退出需确认；不要无条件 `LanHub.stop()`（避免切后台/重建丢连接） |
| `GoGame.java` | 字段区 | 新增 `public int koX()` / `public int koY()` 只读访问器（BoardHash 需要） |

### 6.4 线程模型

- `LanHost`：1 个 accept 线程 + 每连接 1 个读线程；所有消息投递到**单线程 `ExecutorService`（authority executor）**串行处理，确保 `GoGame` 不被并发修改。
- 裁决结果通过 `Handler(Looper.getMainLooper())` 回主线程刷新 UI。
- `GameActivity` 现有 `worker`（单线程）可继续用于引擎；网络不用它。

### 6.5 权限与配置

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<!-- 仅当保留 UDP 兜底发现时 -->
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
```

说明：

- `INTERNET` 是 TCP Socket 的硬性要求（含局域网）。
- `NsdManager` 不额外索取位置/附近设备权限；Android 13+ 的 `NEARBY_WIFI_DEVICES` 针对 Wi-Fi Aware/P2P，本方案不需要。
- 对局中建议 `getWindow().addFlags(FLAG_KEEP_SCREEN_ON)`；可选 `WifiLock`（`WIFI_MODE_FULL_LOW_LATENCY`，API 29+，需自行判版本）。
- 现有 `release` 使用 debug 签名，不影响权限。

---

## 7. iOS 端改造清单

### 7.1 新增文件

```
QingYi/Net/
├── LanProto.swift       # Codable 信封 + 各负载结构 + 错误码
├── LanFrame.swift       # NWConnection/NWListener 上的长度前缀帧
├── BoardHash.swift      # CryptoKit SHA-256
├── LanClock.swift       # 单调时钟、读秒、快照
├── LanSession.swift     # 权威棋局（Host 侧）
├── LanHost.swift        # NWListener + 多连接管理
├── LanClient.swift      # NWConnection + 握手 + 重连
├── LanDiscovery.swift   # NWBrowser + NWListener.service 注册/解析
├── LanRuntime.swift     # 单例状态机（Idle/Lobby/Playing/Suspended/Finished）
└── LanNetInfo.swift     # getifaddrs 取本机 IPv4
```

新增 UI：

```
QingYi/UI/LanLobbyView.swift    # 建房 / 加入 / 发现列表 / 手动 IP / 房间码
QingYi/UI/LanSetupDialog.swift  # 尺寸、贴目、让子、计时、执黑执白、AI 开关
```

### 7.2 关键类型

```swift
enum LanRole { case host, guest }

enum LanMessageType: String, Codable {
    case hello, helloAck = "hello_ack", helloReject = "hello_reject"
    case lobby, ready, gameStart = "game_start"
    case move, moveAck = "move_ack", moveReject = "move_reject", snapshot = "state_snapshot"
    case undoRequest = "undo_request", undoResponse = "undo_response"
    case resign, drawOffer = "draw_offer", drawResponse = "draw_response"
    case clock, ping, pong, gameOver = "game_over", chat, bye
}

struct LanEnvelope: Codable {
    var v: Int = 1
    var t: LanMessageType
    var sid: String
    var ts: Int64
    var seq: Int64
    var room: String
    var from: String            // "host" / "guest"
    var p: [String: LanJSON]    // 或按类型拆成具体 struct
}
```

> 建议：信封用手写 `[String: Any]` + `JSONSerialization`（字段可选值多，Codable 的 `Any` 不便）；负载用 `Codable` struct，编码时转字典。这样与 Android 的 `JSONObject` 行为最接近，避免 `null`/缺字段差异。

```swift
final class LanHost {
    private var listener: NWListener?
    private var conns: [ObjectIdentifier: NWConnection] = [:]
    private let queue = DispatchQueue(label: "lan.host")
    func start(preferredPort: UInt16, roomCode: String, hostName: String) throws
    func broadcast(_ msg: [String: Any])
    func stop()
}

final class LanClient {
    private var conn: NWConnection?
    var onMessage: (([String: Any]) -> Void)?
    var onState: ((NWConnection.State) -> Void)?
    func connect(host: String, port: UInt16, roomCode: String, name: String, resumeToken: String?)
    func send(_ msg: [String: Any])
    func close()
}
```

### 7.3 现有文件改动点

| 文件 | 位置 | 改动 |
|---|---|---|
| `project.yml` | `targets.QingYi.settings.base` | 见 §7.4：改为显式 `Info.plist`（含 `NSBonjourServices`） |
| `QingYi/Core/Setup.swift` | `enum PlayMode` L3–L7 | 新增 `case lan` |
| 同上 | `struct GoSetup` L9–L17 | 新增 `var lan: LanSetup?`（`LanSetup` 含 role/roomCode/host/port/seat/aiEnabled 等） |
| `QingYi/UI/MainView.swift` | L226–L235 `openEntry` | 索引 1 改为弹出「本地双人 / 局域网联机」选择 |
| `QingYi/UI/MainView.swift` | L94–L97 `fullScreenCover` | 复用 `goSetup`；`.lan` 时进入 `GoGameView`（内部走 `GoGameModel` 的 lan 分支） |
| `QingYi/UI/MainView.swift` | L214–L224 `entryDesc` | 索引 1 描述改为「同机双人 · 局域网联机」 |
| `QingYi/UI/GoGameModel.swift` | L42–L78 `init` | `mode == .lan`：不预加载引擎；建立/加入会话；`playerColor` 来自 ack |
| 同上 | L147–L163 `tap/pass` | `lan`：本地预演 + `lan.sendMove()`；非己方回合直接返回 |
| 同上 | L249–L268 `undo` | `lan`：发 `undo_request`，等待 `undo_response` |
| 同上 | L300–L311 `resign` | `lan`：`resign { by: myColor }` |
| 同上 | L284–L298 `countScore` | `lan`：只读显示 Host 结果 |
| 同上 | L183 `startAI` | 仅在 `mode == .lan && mySeatIsAI` 时允许；Guest 的 AI 结果必须经 `sendMove` |
| 同上 | 新增方法 | `applyRemoteMove(_:)`、`applySnapshot(_:)`、`applyRemoteUndo(_:)`、`finishRemote(_:)` |
| `QingYi/UI/GoGameView.swift` | L153–L167 `haraStatusLine`、L184–L224 `actionGrid` | 显示连接状态/对手/剩余时间；悔棋按钮文案改为「请求悔棋」 |
| `QingYi/App/AppState.swift` | L8–L22 | 持久化昵称 `ui.playerName`、上次房间配置 |
| `.github/workflows/build-ipa.yml` | 无需改动 | `Network`/`CryptoKit` 系统框架自动链接 |

### 7.4 Info.plist 与 project.yml

iOS 14+ 的硬性要求：

- `NSLocalNetworkUsageDescription`：首次访问局域网会弹权限框，缺少会直接失败。
- `NSBonjourServices`：必须列出要浏览/注册的服务类型，否则 `NWBrowser` 无结果（且无明确报错）。

`project.yml` 建议改为显式 plist（`GENERATE_INFOPLIST_FILE` 无法表达数组型 `NSBonjourServices`）：

```yaml
  QingYi:
    type: application
    platform: iOS
    info:
      path: QingYi/Info.plist
      properties:
        CFBundleDisplayName: 清弈
        CFBundleShortVersionString: "1.0.0"
        CFBundleVersion: "1"
        UILaunchScreen: {}
        UIApplicationSceneManifest:
          UIApplicationSupportsMultipleScenes: false
        UISupportedInterfaceOrientations:
          - UIInterfaceOrientationPortrait
        UISupportedInterfaceOrientations~ipad:
          - UIInterfaceOrientationPortrait
          - UIInterfaceOrientationPortraitUpsideDown
        LSSupportsOpeningDocumentsInPlace: true
        UIFileSharingEnabled: true
        UIApplicationSupportsIndirectInputEvents: true
        NSLocalNetworkUsageDescription: 用于在同一局域网内发现并连接对手，进行本地对弈。所有数据仅在局域网内传输。
        NSBonjourServices:
          - _qingyi-go._tcp
    settings:
      base:
        # 删除 GENERATE_INFOPLIST_FILE 与全部 INFOPLIST_KEY_*（改由上面的 info 段生成）
        SWIFT_OBJC_BRIDGING_HEADER: QingYi/Engines/QingYi-Bridging-Header.h
        # 其余编译设置保持不变
```

生成后 `QingYi/Info.plist` 由 XcodeGen 写入，可提交版本库，也可加入 `.gitignore`（取决于团队习惯；建议提交，避免 CI 缺文件）。

> 注意：`_qingyi-go._tcp` 是"服务类型"，不要带 `.local`；Android 侧常量要写 `"_qingyi-go._tcp."`（末尾有点）。

### 7.5 iOS 侧权限与限制

- 无特殊 entitlement；`NSBonjourServices` + `NSLocalNetworkUsageDescription` 即可。
- 用户拒绝本地网络权限后，所有局域网连接（含手动 IP）都会失败；UI 必须提供「去设置 > 隐私与安全性 > 本地网络」的引导文案。
- App 进入后台后连接会被系统挂起；本方案要求**对局期间保持前台**（可在 UI 上提示）。
- 未签名/自签 IPA 通过侧载安装时，本地网络权限弹窗通常仍可正常工作；若出现不弹窗，优先检查 `Info.plist` 是否真的包含两项键（`plutil -p` 检查构建产物）。

### 7.6 iOS 线程模型

- `NWListener`/`NWConnection` 回调在自定义串行 `DispatchQueue`；解析出的消息统一 `DispatchQueue.main.async` 投递给 `GoGameModel`。
- 权威棋局只在 Host 的串行队列上修改；UI 只读。
- `LanRuntime.shared` 持有 Host/Client，避免 SwiftUI 视图重建导致连接丢失。

---

## 8. 两端「联机模式」UI 最小改动

### Android

`LanLobbyActivity` 两个页签/区块：

1. **创建房间**：昵称、棋盘 19（暂定）、贴目 7.5、让子 0–9、计时（无 / 10min+3×30s）、我执黑/白；点「创建」后显示：房间码（大字）、`IP:端口`、发现状态。
2. **加入房间**：自动发现列表（`Qingyi 房间 7K2M · 19路 · 等待中`）+ 手动 IP/端口 + 房间码；点条目 → 握手 → `GameActivity(mode="lan", role="guest")`。

主题沿用 `UiTheme`（`HARA`/`MONUMENT`），按钮复用 `UiTheme.buttonDrawable()`，不引入 Material 组件。

### iOS

`LanLobbyView`：

1. 顶部 `本地双人 / 局域网联机` 分段（与现有 `segmented` 风格一致）。
2. 联机页两栏：`创建房间`（表单 + 房间码展示）、`加入房间`（`List` 发现结果 + 手动 IP `TextField`）。
3. 配色/字体全部走 `Theme`，复用 `DialogScaffold`、`ThemedActionButton`、`Hairline` 等现有组件。

---

## 9. 分阶段实施步骤

### Phase 0：协议与哈希对齐（1 个提交，先做）

1. Android 新增 `Proto.java` / `FrameIO.java` / `BoardHash.java`；iOS 新增 `LanProto.swift` / `LanFrame.swift` / `BoardHash.swift`。
2. 两端各写一个纯逻辑测试（Android 用 `main`/JUnit，iOS 用 XCTest 或临时 `#Preview`）验证 §3.6 两组测试向量。
3. 冻结协议版本 `v=1` 与消息字段表（本文件 §3）。

**验收**：两端对同一盘面算出的 hash 完全一致。

### Phase 1：TCP 主机/客户端 + 手动 IP（同平台自测）

1. Android：`LanHost`/`LanClient`/`LanSession`/`LanHub` + `LanLobbyActivity`（仅手动 IP）+ `GameActivity` lan 分支。
2. 两台 Android 真机：创建/加入、落子、pass、认输、终局。
3. iOS：`LanHost`/`LanClient`/`LanSession`/`LanRuntime` + `LanLobbyView`（仅手动 IP）+ `GoGameModel` lan 分支。
4. 两台 iOS 真机同上。

**验收**：同平台两端能完整下完一局，且每一步 hash 一致。

### Phase 2：Bonjour 自动发现

1. Android `LanDiscovery`：`NsdManager.registerService` / `discoverServices("_qingyi-go._tcp.")` / `resolveService`（失败重试 2 次）。
2. iOS `LanDiscovery`：`NWListener.service` 注册 + `NWBrowser(.bonjour)` 浏览。
3. 实例名解析：`QY-<roomCode>-<size>-<komi>-<state>-<encName>`（按 `-` split limit 5）。
4. 保留手动 IP 兜底入口。

**验收**：Android 建房 → iOS 列表可见并可加入；反向亦然。

### Phase 3：跨平台互通矩阵

| Host \ Guest | Android | iOS |
|---|---|---|
| Android | ✅ 必测 | ✅ 必测 |
| iOS | ✅ 必测 | ✅ 必测 |

每格至少跑：19 路无让子、让 2 子、停一手两次终局、认输、悔棋请求（同意/拒绝）、中途杀进程重连。

### Phase 4：时钟 / 读秒 / 断线

1. `LanClock`（两端）+ `clock` 消息 + Guest 插值显示。
2. 断线宽限 120s、`resumeToken` 重连、`state_snapshot` 对齐。
3. 超时判负只在 Host。

### Phase 5：打磨

- 存谱（两端各自 `Sgf.save`，黑白名用双方昵称，结果写 `RE`）。
- 聊天（可选，1 KiB 上限，UI 用底部输入条）。
- 热点场景手动 IP 引导（Android 热点显示 `192.168.43.1`）。
- 对局中保持屏幕常亮、Wi-Fi Lock（Android）。

---

## 10. 风险与决策点

### 10.1 风险清单

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | 两端规则实现细节分歧（劫/提子顺序/终局） | 频繁 `hash` 不一致、非法手误判 | Phase 0 用测试向量锁死 hash；把 hash 不一致当阻断 bug |
| R2 | iOS 用户拒绝本地网络权限 | 完全无法联机，且无明显报错 | 首启引导 + 设置页跳转文案；手动 IP 也需该权限，必须提示 |
| R3 | 路由器开启 AP 隔离 / 访客网络 | 发现失败、TCP 连不上 | UI 明确提示"同一 Wi-Fi、关闭 AP 隔离"；提供手动 IP；热点直连文档 |
| R4 | 热点下 mDNS 组播被屏蔽 | 自动发现不到 | 手动 IP 兜底（必做，不是可选） |
| R5 | Android `NsdManager` 个别机型 resolve 失败/慢（3–10s） | 列表加载慢或为空 | resolve 重试 2 次 + 8s 超时；同时展示"手动 IP" |
| R6 | 时钟抖动导致误判 | 玩家投诉 | Host 权威 + 断线暂停 + `ping` 估时偏；Guest 只显示不判负 |
| R7 | Host 掉线 | 整局中断 | 宽限期 + Guest 自动重连 + 允许存谱退出 |
| R8 | 无签名 IPA 侧载后权限行为差异 | 本地网络不可用 | 构建产物 `plutil -p` 校验 plist；真机验证 |
| R9 | 两端 `moveCount`/历史顺序差异被误用 | 序号错乱、悔棋错位 | 协议统一 `moveNo`；禁止读内部计数（写入代码注释） |
| R10 | 让子摆点不一致 | 开局即不同步 | Host 下发 `handicapPoints`，Guest 不自行计算 |
| R11 | 后台挂起（iOS） | 连接中断 | UI 提示保持前台；重连机制兜底 |
| R12 | 明文传输被同网段嗅探 | 棋谱/聊天泄露 | 局域网熟人场景可接受；后续可加 AES-GCM（预共享房间码派生密钥） |

### 10.2 需要用户确认的决策点

1. **首期棋盘尺寸**：建议只做 19 路（两端 UI 现状就是 19），9/13 路放 Phase 5。是否同意？
2. **是否支持热点直连**：建议"支持但只走手动 IP"（不做热点专用发现）。是否同意？
3. **是否引入第三方库**：本方案 **0 依赖**。如果后续要 WebSocket/TLS，再评估（不建议现在引入）。
4. **断线是否暂停时钟**：建议默认暂停（宽松、少投诉）。是否改为严格继续走表？
5. **是否做聊天**：建议先留协议字段、UI 后置。
6. **是否要"观战/多人房"**：本期不做，协议预留 `seat` 字段（0/1），未来可扩。
7. **AI 在联机中的等级如何展示**：建议只显示"AI · 等级 N"，不承诺与单机完全同棋力（因为只有一方引擎在跑）。

---

## 11. 附录

### 11.1 常量速查

| 常量 | 值 |
|---|---|
| 协议版本 | `1` |
| 服务类型（Android） | `_qingyi-go._tcp.` |
| 服务类型（iOS） | `_qingyi-go._tcp` |
| 默认 TCP 端口 | `41234`（顺延至 `41244`） |
| 单帧上限 | `65536` 字节 |
| 心跳间隔 / 超时 | `5s` / `15s`（3 次） |
| 断线宽限 | `120s` |
| 时钟广播 | 每手 + 每秒（仅 `running=true` 时） |
| 默认贴目 | `7.5` |
| 房间码长度 | `4`（Crockford Base32：`0-9 A-H J-K M-N P-T V-Z`） |

### 11.2 测试矩阵

| 用例 | Android Host | iOS Host |
|---|---|---|
| 19 路空盘开局，黑白交替 30 手 | | |
| 让 2 子（Host 下发摆点） | | |
| 打劫：提一子后立即回提被拒 | | |
| 自杀手被拒（`E_SUICIDE`） | | |
| 停一手 ×2 → `game_over` | | |
| 认输（黑认输 → `W+R`） | | |
| 悔棋请求：同意 1 手 / 拒绝 | | |
| 求和：同意 → `draw` | | |
| 对局中 Guest 杀进程 → 重连恢复 | | |
| 断线 120s → 判负 | | |
| 读秒用尽 → `B+T`/`W+T` | | |
| 手动 IP 加入（关闭 Bonjour） | | |
| 热点场景（Android 热点） | | |

### 11.3 与现有代码的对应关系（便于改代码时定位）

| 功能 | Android | iOS |
|---|---|---|
| 棋局规则 | `GoGame.java`（L89–L152 合法性/落子/悔棋；L157 数子） | `GoGame.swift`（L107–L174 合法性/落子/悔棋；L176 数子） |
| 对局 UI 状态 | `GameActivity.java`（L573–L617） | `GoGameModel.swift`（L82–L143） |
| 棋盘绘制 | `GoBoardView.java` | `GoBoardView.swift` |
| 首页入口 | `MainActivity.java`（L44–L57） | `MainView.swift`（L201–L235） |
| SGF | `Sgf.java` | `Sgf.swift` |
| 引擎 | `GtpEngine.java` | `KataGoEngine/PachiEngine/GoAI.swift` |

---

## 12. 一句话落地顺序

> 先写两端 `BoardHash` 并用测试向量对齐 → 再写 Android 的 Host/Client + 手动 IP 打通两台安卓 → 移植到 iOS → 接 Bonjour → 最后加时钟/重连/悔棋/求和。

按此顺序，任何一步失败都能明确定位在"协议/传输/规则"的哪一层，不会出现"连上了但不知道谁错了"的黑盒调试。

---

## 13. iOS 端局域网联机完整实现方案（对齐 Android 1.1.2）

> 本章是实现手册，按章节顺序照抄即可跑通。§7 与 §11.1 中较早的数值（120s 宽限期、仅 `_qingyi-go._tcp` 单类型、无 pid 的 TXT）已被本章取代，**以本章为准**。

### 13.1 互通基线（8 条硬约束）

| 项 | Android 现状（1.1.2） | iOS 必须一致 |
|---|---|---|
| 传输 | TCP，无 TLS | `NWConnection`/`NWListener`，`NWParameters.tcp` |
| 监听端口 | `41234` 起，最多顺延 11 个（41234–41244） | 同一顺延策略，实际端口写进 TXT |
| 帧格式 | 4 字节**大端**无符号长度 + UTF-8 JSON | `UInt32.bigEndian` + `JSONSerialization` |
| 单帧上限 | 65536 字节 | 同 |
| 协议版本 | `v = 1`，握手时 `protocolVersion` 必须相等 | 同，值不等直接拒绝（中文提示） |
| 角色 | 房主权威裁决，客机只发意图、收到 ack 才落子 | 同 |
| 握手超时 | 房主 accept 后 8s 未收到 hello → 关闭 | 同 |
| 棋盘校验 | SHA-256（围棋 canonical 串 / 象棋 `positionKey+M`），小写 hex | 用 CryptoKit 生成同样字符串 |

### 13.2 服务类型：`_qingyi-go._tcp` 与象棋如何区分

Android 现在**只广播一个通用类型** `_qingyi._tcp.`，棋种放在 TXT 的 `game` 字段。为了让 iOS 能按棋种过滤，同时不破坏与现有 Android 的互通，采用「双类型 + 兼容类型」：

| 场景 | 服务类型（DNS-SD） | 说明 |
|---|---|---|
| 围棋房间（新） | `_qingyi-go._tcp` | iOS/新 Android 按棋种精确浏览 |
| 象棋房间（新） | `_qingyi-xiangqi._tcp` | 同上 |
| 兼容（必须） | `_qingyi._tcp` | 旧 Android 只认这个；iOS 必须同时浏览 |

注册规则（房主）：

- 只按本房棋种注册一条专用类型 + 一条 `_qingyi._tcp` 兼容类型（两条 TXT 完全相同）。
- 服务名保持一致：`QY-<房间码>-<size>`，便于按名称去重。

浏览规则（客机）：

- 进入「围棋」入口：浏览 `_qingyi-go._tcp` + `_qingyi._tcp`；
- 进入「象棋」入口：浏览 `_qingyi-xiangqi._tcp` + `_qingyi._tcp`；
- 三条结果的 TXT 都有 `room`，**以 `room` 去重**（同一房间会同时以专用/兼容两条出现）。

> 只做到这一步就够 iOS↔Android 互通；Android 侧后续版本可按 §13.2 增加专用类型，协议本身不变。

### 13.3 Bonjour 服务名与 TXT 字段（逐字段对齐）

服务名：`QY-<room>-<size>`，例如 `QY-3GJA-19`。

| TXT 键 | 类型 | 必填 | 含义 | Android 写入点 |
|---|---|---|---|---|
| `room` | string | 是 | 4 位房间码（大写，去掉 I/L/O/U） | `LanDiscovery.register` |
| `size` | int | 是 | 围棋 9/13/19；象棋 10（行数） | 同上 |
| `game` | string | 是 | `go` / `xiangqi` | 同上 |
| `port` | int | 是 | 实际 TCP 端口（端口顺延后） | 同上 |
| `addr` | string | 是 | 房主当前 IPv4，如 `192.168.5.72` | `NetUtil.localIpv4` |
| `pid` | int | 是 | 房主进程 pid，用于识别僵尸条目（诊断用） | 同上 |
| `ttl` | int | 否 | 建议 60（秒），仅提示用途 | 预留 |

TXT 的 DNS 线格式是「1 字节长度 + `key=value`」重复，**不是** JSON，也不是 `k=v&k=v`。iOS 写 TXT 必须自己拼（见 §13.5）。

### 13.4 Info.plist / 工程配置

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>用于发现同一 Wi-Fi 下的清弈对战房间，并与对手手机直接连接，不会上传任何数据。</string>

<key>NSBonjourServices</key>
<array>
    <string>_qingyi-go._tcp</string>
    <string>_qingyi-xiangqi._tcp</string>
    <string>_qingyi._tcp</string>
</array>
```

要点：

1. `NSBonjourServices` 的值**不带结尾的点**，且必须把「注册」和「浏览」用到的类型全部列出，漏一个就会 `NWBrowser` 一直 `.waiting`。
2. 首次访问局域网会弹系统授权框；**被拒后不会再弹**，需要引导用户去「设置 → 清弈 → 本地网络」打开。建议在联机页顶部检测 `NWBrowser` 长时间无结果时提示这句话。
3. 模拟器没有真正的本地网络权限弹窗，但**模拟器之间不能互相发现 Bonjour**（见 §13.15），所以这条只能在真机验证。

### 13.5 TXT 编解码（Swift）

```swift
import Network

enum LanTXT {
    /// ["room": "3GJA", ...] -> DNS TXT wire data
    static func data(_ pairs: [String: String]) -> Data {
        var out = Data()
        for (k, v) in pairs.sorted(by: { $0.key < $1.key }) {
            let entry = "\(k)=\(v)"
            guard let bytes = entry.data(using: .utf8), bytes.count <= 255 else { continue }
            out.append(UInt8(bytes.count))
            out.append(bytes)
        }
        return out
    }

    /// NWTXTRecord 不支持遍历，按固定键读取即可（与 Android 字段一一对应）
    static func read(_ r: NWTXTRecord) -> [String: String] {
        var out: [String: String] = [:]
        for key in ["room", "size", "game", "port", "addr", "pid", "ttl"] {
            if let v = r[key] { out[key] = v }
        }
        return out
    }
}
```

### 13.6 帧编解码与连接封装

```swift
import Foundation
import Network

enum LanError: Error { case frameTooLarge, badJSON }

/// 4 字节大端长度 + UTF-8 JSON。只在所属串行队列上调用，无需加锁。
final class FrameCoder {
    static let maxFrame = 65_536
    private var buffer = Data()

    func encode(_ obj: [String: Any]) throws -> Data {
        let json = try JSONSerialization.data(withJSONObject: obj)
        guard json.count <= Self.maxFrame else { throw LanError.frameTooLarge }
        var out = Data(capacity: json.count + 4)
        out.append(UInt8((json.count >> 24) & 0xff))
        out.append(UInt8((json.count >> 16) & 0xff))
        out.append(UInt8((json.count >> 8) & 0xff))
        out.append(UInt8(json.count & 0xff))
        out.append(json)
        return out
    }

    /// 追加数据并吐出所有完整帧；不足一帧时返回空数组。
    func feed(_ data: Data) throws -> [[String: Any]] {
        buffer.append(data)
        var frames: [[String: Any]] = []
        while buffer.count >= 4 {
            let b = [UInt8](buffer.prefix(4))
            let len = (Int(b[0]) << 24) | (Int(b[1]) << 16) | (Int(b[2]) << 8) | Int(b[3])
            guard len > 0, len <= Self.maxFrame else { throw LanError.badJSON }
            guard buffer.count >= 4 + len else { break }
            let body = buffer.subdata(in: 4..<(4 + len))
            buffer.removeFirst(4 + len)
            guard let obj = try JSONSerialization.jsonObject(with: body) as? [String: Any] else {
                throw LanError.badJSON
            }
            frames.append(obj)
        }
        return frames
    }
}

/// 一条已建立的 TCP 连接（房主/客机通用）。
final class FramedPeer {
    private let conn: NWConnection
    private let queue = DispatchQueue(label: "qingyi.lan.peer")
    private let coder = FrameCoder()
    private(set) var handshaked = false

    /// 回调都在 queue 上；调用方必须自己切回主线程。
    var onMessage: (([String: Any]) -> Void)?
    var onClosed: ((String) -> Void)?
    var onReady: (() -> Void)?

    init(_ conn: NWConnection) { self.conn = conn }

    func start() {
        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready: self.onReady?(); self.receiveLoop()
            case .failed(let e): self.onClosed?("连接失败：\(e.localizedDescription)")
            case .cancelled: self.onClosed?("连接已断开")
            default: break
            }
        }
        conn.start(queue: queue)
    }

    func send(_ obj: [String: Any]) {
        guard let data = try? coder.encode(obj) else { return }
        conn.send(content: data, completion: .contentProcessed { _ in })
    }

    func close() { conn.cancel() }

    private func receiveLoop() {
        conn.receive(minimumIncompleteLength: 1, maximumLength: FrameCoder.maxFrame) {
            [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                do {
                    for msg in try self.coder.feed(data) { self.onMessage?(msg) }
                } catch {
                    self.onClosed?("帧解析失败"); return
                }
            }
            if let error { self.onClosed?("连接中断：\(error.localizedDescription)"); return }
            if isComplete { self.onClosed?("对方已关闭连接"); return }
            self.receiveLoop()
        }
    }
}
```

### 13.7 信封与各消息字段

信封（所有消息相同）：

```json
{ "v":1, "t":"hello", "sid":"a1b2c3d4e5f6", "ts":1762400000000,
  "seq":0, "room":"3GJA", "from":"host|guest", "p":{ ... } }
```

| 消息 `t` | 方向 | `p` 字段 | 语义 |
|---|---|---|---|
| `hello` | 客→主 | `protocolVersion, roomCode, deviceName, platform, appVersion, clientId, game?, resume?, gameId?, moveNo?` | 首次加入/重连 |
| `hello_ack` | 主→客 | 首次：`ok, game, gameId, size, komi, seat=1, yourColor, opponentName, hostPlatform, ruleset, serverTime`；重连：`ok, resumed=true, game, gameId, size, komi, moveNo, yourColor, opponentName, snapshot, clock?` | 握手成功 |
| `hello_reject` | 主→客 | `code, message` | `E_ROOM_CODE`/`E_VERSION`/`E_GAME_MISMATCH`/`E_ALREADY_PLAYING`/`E_NO_GAME` |
| `game_start` | 主→客 | `game, gameId, size, komi, handicap, handicapPoints, ruleset, toMove, seats[4], timeControl{mode,mainSec,byoYomiSec,periods,incrementSec}, startAtHostMs` | 开局快照 |
| `move` | 客→主 | 围棋：`pass,x,y,hashBefore,moveNo,color`；象棋：`fx,fy,tx,ty,uci,hashBefore,moveNo,color` | 落子意图 |
| `move_ack` | 主→客 | `moveNo, pass, x, y, fx, fy, tx, ty, captured, nextTurn, hash, clock?` | 权威确认 |
| `move_reject` | 主→客 | `code, message` | 非法/过期手 |
| `clock` | 主→客 | `turn(B/W), blackRemainMs, whiteRemainMs, blackPeriods, whitePeriods, blackByoYomi, whiteByoYomi, running, paused, moveNo, serverTime` | 每 1s 推一次 |
| `resign` | 双→双 | 客→主带 `color`；主→客为广播 | 认输 |
| `undo_request` | 双→双 | `count, moveNo` | 悔棋请求 |
| `undo_response` | 双→双 | `accept` | 悔棋答复 |
| `undo_applied` | 主→客 | `moveNo, hash, snapshot` | 已回退 |
| `draw_offer` / `draw_response` | 双→双 | `color` / `accept` | 求和 |
| `game_over` | 主→客 | `result, detail, reason, game, moveNo, komi` | 结局（`result` 保持机器可读码） |
| `ping` / `pong` | 双→双 | 空 | 5s 心跳 |
| `bye` | 双→双 | `reason` | 主动离开 |

`result` 取值（**协议字段，不要中文化**）：`B+R`、`W+R`、`B+T`、`W+T`、`B+7.5`、`W+3`、`draw`；象棋为 `red_win`/`black_win`/`draw`（`reason` = `resign|timeout|disconnect|checkmate|stalemate|repetition|sixty`，`detail` 为中文描述）。

### 13.8 房主：`NWListener`（端口顺延 + 单客机 + 握手超时）

```swift
@MainActor
final class LanHost {
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "qingyi.lan.host")
    private(set) var port: UInt16 = 0
    private var peer: FramedPeer?
    private var handshakeTimer: DispatchWorkItem?

    var room = ""
    var size = 19
    var game = "go"

    func start(preferred: UInt16 = 41234, triesLeft: Int = 11,
               onPeer: @escaping (FramedPeer) -> Void) {
        guard triesLeft > 0, let p = NWEndpoint.Port(rawValue: preferred) else { return }
        do {
            let l = try NWListener(using: .tcp, on: p)
            l.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    Task { @MainActor in
                        self.port = preferred
                        // 端口确定后重设 service，让 TXT 里的 port 与实际一致
                        self.publishService(on: l, port: preferred)
                    }
                case .failed:
                    l.cancel()
                    Task { @MainActor in
                        self.start(preferred: preferred + 1, triesLeft: triesLeft - 1, onPeer: onPeer)
                    }
                default: break
                }
            }
            l.newConnectionHandler = { [weak self] conn in
                guard let self else { conn.cancel(); return }
                Task { @MainActor in
                    // 已有一台完成握手的客机，拒绝新连接
                    if let old = self.peer {
                        if old.handshaked { conn.cancel(); return }
                        old.close()   // 未握手的半开连接直接顶替
                    }
                    let fp = FramedPeer(conn)
                    self.peer = fp
                    fp.start()
                    onPeer(fp)
                    // 8s 内没有 hello 就关掉
                    let timer = DispatchWorkItem { [weak fp] in
                        if fp?.handshaked == false { fp?.close() }
                    }
                    self.handshakeTimer = timer
                    DispatchQueue.main.asyncAfter(deadline: .now() + 8, execute: timer)
                }
            }
            listener = l
            l.start(queue: queue)
        } catch {
            start(preferred: preferred + 1, triesLeft: triesLeft - 1, onPeer: onPeer)
        }
    }

    private func publishService(on l: NWListener, port: UInt16) {
        let name = "QY-\(room)-\(size)"
        let txt = LanTXT.data(["room": room, "size": "\(size)", "game": game,
                               "port": "\(port)", "addr": LanNet.localIPv4() ?? "",
                               "pid": "\(ProcessInfo.processInfo.processIdentifier)"])
        // 两条服务：棋种专用 + 兼容旧 Android
        l.service = NWListener.Service(name: name, type: ServiceType.of(game).rawValue,
                                       domain: nil, txtRecord: txt)
        // 兼容类型用第二个 listener 注册同一端口（SO_REUSEADDR + NWListener 不支持同端口双绑，
        // 因此兼容类型改为「只登广告不接连接」的 NetService，见 §13.9 说明）
        legacyAdvertiser = NetService(domain: "local.", type: "_qingyi._tcp.",
                                      name: name, port: Int32(port))
        legacyAdvertiser?.setTXTRecord(NetService.data(fromTXTRecord: [
            "room": Data(room.utf8), "size": Data("\(size)".utf8),
            "game": Data(game.utf8), "port": Data("\(port)".utf8),
            "addr": Data((LanNet.localIPv4() ?? "").utf8),
            "pid": Data("\(ProcessInfo.processInfo.processIdentifier)".utf8)
        ]))
        legacyAdvertiser?.publish()
    }

    private var legacyAdvertiser: NetService?
}
```

> 说明：Network.framework 的 `NWListener` 一个实例只能发布一条 Bonjour 服务；同端口注册第二条服务用 `NetService` 只做「广告」不接连接即可（TCP 连接仍由同一个 `NWListener` 处理），这样 Android 仍能通过 `_qingyi._tcp` 发现房间。

### 13.9 客机：`NWBrowser` 发现 + `NWConnection` 加入 + 僵尸房间探活

```swift
enum ServiceType: String {
    case go = "_qingyi-go._tcp"
    case xiangqi = "_qingyi-xiangqi._tcp"
    case legacy = "_qingyi._tcp"
    static func of(_ game: String) -> ServiceType { game == "xiangqi" ? .xiangqi : .go }
}

struct RoomInfo: Identifiable {
    var id: String { code }
    let code: String
    let game: String
    let size: Int
    let host: String
    let port: UInt16
    let pid: Int
    var endpoint: NWEndpoint
    var lastSeen = Date()
}

@MainActor
final class LanBrowser {
    private var browsers: [NWBrowser] = []
    private(set) var rooms: [RoomInfo] = []
    var onChange: (() -> Void)?

    func start(game: String) {
        stop()
        let types: [ServiceType] = [ServiceType.of(game), .legacy]
        for type in types {
            let params = NWParameters.tcp
            params.includePeerToPeer = true
            let b = NWBrowser(for: .bonjour(type: type.rawValue, domain: nil), using: params)
            b.browseResultsChangedHandler = { [weak self] results, _ in
                Task { @MainActor in self?.merge(results, game: game) }
            }
            b.start(queue: .global(qos: .userInitiated))
            browsers.append(b)
        }
    }

    func stop() { browsers.forEach { $0.cancel() }; browsers = [] }

    private func merge(_ results: Set<NWBrowser.Result>, game: String) {
        var found: [String: RoomInfo] = [:]
        for r in results {
            guard case let .bonjour(meta) = r.metadata else { continue }
            let t = LanTXT.read(meta.txtRecord)
            guard let code = t["room"], let g = t["game"], g == game,
                  let host = t["addr"], let portStr = t["port"], let port = UInt16(portStr),
                  let sizeStr = t["size"], let size = Int(sizeStr) else { continue }
            found[code] = RoomInfo(code: code, game: g, size: size, host: host,
                                   port: port, pid: Int(t["pid"] ?? "0") ?? 0,
                                   endpoint: r.endpoint)
        }
        rooms = found.values.sorted { $0.code < $1.code }
        onChange?()
    }

    /// 定期探活：每 12s 扫一次，连不上即从列表剔除
    func sweep() async {
        var alive: [RoomInfo] = []
        for r in rooms {
            let ok = await LanProbe.reachable(host: r.host, port: r.port, timeout: 1.2)
            if ok { alive.append(r) }
        }
        if alive.count != rooms.count {
            rooms = alive
            onChange?()
        }
    }
}

enum LanProbe {
    /// 两次尝试，避免 Wi-Fi 抖动误杀健康房间
    static func reachable(host: String, port: UInt16, timeout: TimeInterval) async -> Bool {
        await once(host: host, port: port, timeout: timeout)
            || await once(host: host, port: port, timeout: timeout + 0.5)
    }

    private static func once(host: String, port: UInt16, timeout: TimeInterval) async -> Bool {
        guard let p = NWEndpoint.Port(rawValue: port) else { return false }
        let conn = NWConnection(host: NWEndpoint.Host(host), port: p, using: .tcp)
        return await withCheckedContinuation { cont in
            let lock = NSLock()
            var finished = false
            func finish(_ ok: Bool) {
                lock.lock()
                defer { lock.unlock() }
                guard !finished else { return }
                finished = true
                conn.cancel()
                cont.resume(returning: ok)
            }
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready: finish(true)
                case .failed, .waiting, .cancelled: finish(false)
                default: break
                }
            }
            conn.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { finish(false) }
        }
    }
}
```

点击房间时（与 Android 一致）：**先探活再连接**；探活失败 → 从列表移除 + Toast「房间 XXXX 已失效（房主可能已退出），请选择其他房间」。

```swift
func join(_ room: RoomInfo) async {
    guard await LanProbe.reachable(host: room.host, port: room.port, timeout: 1.2) else {
        rooms.removeAll { $0.code == room.code }
        onChange?()
        toast = "房间 \(room.code) 已失效（房主可能已退出），请选择其他房间"
        return
    }
    let conn = NWConnection(host: .init(room.host), port: .init(rawValue: room.port)!, using: .tcp)
    // 之后走 §13.10 的握手流程
}
```

### 13.10 连接状态机与 `@MainActor` 更新

```swift
enum LanPhase: Equatable {
    case idle, hosting, connecting, lobby, playing, finished
    case failed(String)
}

@MainActor
final class LanRuntime: ObservableObject {
    static let shared = LanRuntime()

    @Published private(set) var phase: LanPhase = .idle
    @Published private(set) var linkDown = false
    @Published private(set) var mySeat = 1          // 1=先手(围棋黑/象棋红), 2=后手
    @Published private(set) var opponentName = "对手"
    @Published private(set) var resultText = ""
    @Published private(set) var blackClock = "20:00"
    @Published private(set) var whiteClock = "20:00"
    @Published var notice: String?

    private var peer: FramedPeer?
    private var clock = LanClock(mainSec: 1200, byoYomiSec: 30, periods: 3)
    private var lastRecvAt: TimeInterval = 0
    private var lastPingAt: TimeInterval = 0
    private var graceTask: Task<Void, Never>?
    private var watchTask: Task<Void, Never>?
    private var epoch = 0
    private let clientId = UUID().uuidString

    func bind(_ p: FramedPeer) {
        peer = p
        p.onMessage = { [weak self] msg in
            Task { @MainActor in self?.handle(msg) }
        }
        p.onClosed = { [weak self] reason in
            Task { @MainActor in self?.onClosed(reason) }
        }
    }
}
```

主线程约束：

- 所有 `NWConnection`/`NWListener` 回调都在自己的 queue 上，**一律 `Task { @MainActor in ... }` 回到主线程**再改 `@Published`；
- 不要在回调里直接碰 SwiftUI 状态，否则会出现「Publishing changes from background threads」；
- 客机 `pendingMove` 这类状态只在主线程读写。

### 13.11 断线检测、重连与宽限期（与 Android 常量完全一致）

| 常量 | 值 | 作用 |
|---|---|---|
| `LINK_PING_MS` | 5000 | 房主每 5s 发 `ping` |
| `LINK_IDLE_MS` | 13000 | 房主 13s 没收到任何消息 → 判定断线 |
| 客机空闲阈值 | 15000 | 客机 15s 没收到任何消息 → 判定断线 |
| `RECONNECT_GRACE_MS` | 60000 | 断线后暂停计时，60s 内可恢复 |
| 重连间隔 | 1500ms | 客机每 1.5s 重试，最多 60s |
| 握手超时 | 8000ms | 房主等待 hello |

房主断线处理（关键顺序）：

```swift
private func onHostLinkLost(_ reason: String) {
    guard phase == .playing, !linkDown else { return }
    linkDown = true
    lastPingAt = 0
    clock.pauseAt(lastRecvAt)          // 只结算到最后一次收到消息
    pushClock()                        // 力争把"已暂停"的时钟发出去
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
        self?.peer?.close()            // 释放半开连接，重连才进得来
    }
    notice = "对手长时间无响应，计时已暂停，等待重连（60 秒）"
    graceTask = Task { [weak self] in
        try? await Task.sleep(nanoseconds: 60_000_000_000)
        guard let self, !Task.isCancelled, self.phase == .playing, self.linkDown else { return }
        self.finish(self.rules.resultByDisconnect(lostSeat: 3 - self.mySeat))
    }
}
```

客机重连：

```swift
private func startReconnect(epoch e: Int) {
    Task { [weak self] in
        guard let self else { return }
        let deadline = Date().addingTimeInterval(60)
        while Date() < deadline, await MainActor.run(body: { self.epoch == e && self.linkDown }) {
            do {
                let conn = NWConnection(host: .init(self.hostIp),
                                        port: .init(rawValue: self.hostPort)!, using: .tcp)
                let p = FramedPeer(conn)
                self.bind(p)
                p.start()
                self.sendHello(resume: true, gameId: self.matchId, moveNo: self.moveNo)
                return   // 收到 hello_ack(resumed=true) 后才算成功
            }
            try? await Task.sleep(nanoseconds: 1_500_000_000)
        }
    }
}
```

客机收到 `hello_ack` 且 `resumed == true`：

1. `apply(snapshot)` 用房主快照覆盖本地棋盘（这是唯一权威来源）；
2. `clock.resume()`；`linkDown = false`；`phase = .playing`；
3. 清空本地 `pendingMove`，恢复输入；
4. 显示「已恢复连接，计时继续」。

宽限期到期的结局文案：房主 `game_over{result:"red_win"|"B+R"…, reason:"disconnect"}`；客机本地结束显示「与房主的连接未能恢复（重连宽限期已过），对局结束」，且顶部连接状态保持「连接中断」（不要显示「已连接」）。

### 13.12 结果中文化（Swift 版映射表）

协议字段不动，只在展示层映射。与 Android `ResultText.java` 一一对应：

```swift
enum ResultText {
    static func of(game: String, code: String, reason: String, detail: String) -> String {
        let c = code.uppercased()
        // 1) 断线/超时/认输这类 reason 优先
        if reason == "disconnect" {
            if game == "go" {
                if c.hasPrefix("B+") { return "黑方胜（白方断线判负）" }
                if c.hasPrefix("W+") { return "白方胜（黑方断线判负）" }
            } else {
                if c.contains("red") { return "红方胜（黑方断线判负）" }
                if c.contains("black") { return "黑方胜（红方断线判负）" }
            }
        }
        if reason == "timeout" {
            if c == "B+T" { return "白方胜（黑方超时判负）" }
            if c == "W+T" { return "黑方胜（白方超时判负）" }
            if c.contains("red") { return "黑方胜（红方超时判负）" }
            if c.contains("black") { return "红方胜（黑方超时判负）" }
        }
        if reason == "resign" {
            if c == "W+R" { return "黑方中盘胜（白方认输）" }
            if c == "B+R" { return "白方中盘胜（黑方认输）" }
            if c.contains("red") { return "黑方胜（红方认输）" }
            if c.contains("black") { return "红方胜（黑方认输）" }
        }
        // 2) 围棋数目/和棋
        if c == "DRAW" { return "和棋" }
        if let r = c.range(of: #"^([BW])\+(.+)$"#, options: .regularExpression) {
            let side = c.hasPrefix("B") ? "黑方" : "白方"
            let diff = String(c[r].dropFirst(2))
            return "\(side)胜 \(diff) 目"
        }
        // 3) 象棋将死/困毙
        if detail.contains("被将死") { return c.contains("red") ? "红方胜（黑方被将死）" : "黑方胜（红方被将死）" }
        if detail.contains("困毙")   { return c.contains("red") ? "红方胜（黑方被困毙）" : "黑方胜（红方被困毙）" }
        if detail.contains("长将")   { return "和棋（长将）" }
        if detail.contains("六十回合") || detail.contains("重复") { return "和棋" }
        return detail.isEmpty ? "对局结束" : detail
    }
}
```

大厅、对局页、结果弹窗、通知统一调用 `ResultText.of(...)`，不要再拼接原始码。

### 13.13 按坐席翻转棋盘（SwiftUI）

**推荐做法：翻转落在坐标换算里，而不是视图旋转**（与 Android `px/py + pointOf` 完全一致）。这样棋子文字、坐标标注天然保持正向，点击命中也不会错。

```swift
struct BoardGeometry {
    let cols: Int, rows: Int
    let margin: CGFloat, cell: CGFloat
    let flipped: Bool          // 后手(象棋黑/围棋白)为 true

    func screen(_ x: Int, _ y: Int) -> CGPoint {
        let cx = flipped ? cols - 1 - x : x
        let cy = flipped ? rows - 1 - y : y
        return CGPoint(x: margin + CGFloat(cx) * cell, y: margin + CGFloat(cy) * cell)
    }

    /// 屏幕点 -> 逻辑坐标；返回 nil 表示点在棋盘外
    func logical(_ p: CGPoint) -> (x: Int, y: Int)? {
        let sx = Int(((p.x - margin) / cell).rounded())
        let sy = Int(((p.y - margin) / cell).rounded())
        guard (0..<cols).contains(sx), (0..<rows).contains(sy) else { return nil }
        return (flipped ? cols - 1 - sx : sx, flipped ? rows - 1 - sy : sy)
    }
}
```

绘制顺序照旧（河界、九宫、兵炮位、星位、最后一步、可走点、选中高亮都调用 `screen(x,y)`），所以整盘会一起翻，不会只翻棋子。

SwiftUI 手势：

```swift
Canvas { ctx, size in drawBoard(ctx, geo) }
    .contentShape(Rectangle())
    .gesture(SpatialTapGesture().onEnded { e in
        if let (x, y) = geo.logical(e.location) { onTap(x, y) }
    })
```

如果坚持用 `.rotationEffect(.degrees(180))`：**只对棋盘底图旋转**，棋子/文字需要再 `.rotationEffect(.degrees(flipped ? 180 : 0))` 抵消；手势坐标必须用「视图未旋转前的本地坐标」做 `logical()` 反算，否则会出现"点上面走下面"。代码量和出错面都更大，不推荐。

象棋需要一起翻转的清单（与 Android `XiangqiBoardView` 对齐）：棋盘线、九宫斜线、楚河汉界文字位置、炮兵位十字标记、最后一步标记、可走点圆点、选中高亮、坐标标注；**棋子文字不翻**。

围棋没有坐标标注和方向性落子提示（星位对称），不需要翻转；如果后续加了坐标标注，按同一套 `screen()` 处理即可。

### 13.14 Android ↔ iOS 协议逐字段对照表

| 字段 | Android 写法 | iOS 写法 | 备注 |
|---|---|---|---|
| 帧长度 | `DataOutputStream.writeInt(len)`（大端） | 4 字节手写大端 | 必须一致 |
| JSON 库 | `org.json.JSONObject` | `JSONSerialization` | 数值都会回落成 `NSNumber`，读取时用 `Int(...)`/`Double(...)` |
| `v` | `Proto.VERSION = 1` | 常量 1 | 不等直接拒绝 |
| `t` | `Proto.msg(type,...)` | `msg(type:...)` | 字符串完全一致 |
| `p` | 嵌套对象 | 嵌套 `[String: Any]` | 不要序列化成字符串 |
| 房间码 | `newRoomCode()`，字符集 `0123456789ABCDEFGHJKMNPQRSTVWXYZ` | 同一字符集 | 大小写不敏感比较 |
| 颜色字符 | `B`/`W`（围棋）；象棋 `R`/`B` | 同 | 用 `colorChar()` |
| 座位 | `seat` 0=房主、1=客机（`seats[]`）；`myColor` 1=先手、2=后手 | 同 | 两套编号都在用，别混 |
| 围棋落子 | `pass,x,y,hashBefore,moveNo,color` | 同 | `pass=true` 时 `x=y=-1` |
| 象棋落子 | `fx,fy,tx,ty,uci,hashBefore,moveNo,color` | 同 | `uci` 形如 `h2e2` |
| 棋盘哈希 | 围棋 `BoardHash` canonical：`1?SZ=19?M=..?T=B?K=x,y?行/行/...`（分隔符实为竖线） | CryptoKit SHA-256 | 小写 hex |
| 象棋哈希 | `positionKey() + "竖线M=" + moveNo`（分隔符实为竖线） | 同 | 小写 hex |
| 时钟快照 | `turn,blackRemainMs,whiteRemainMs,blackPeriods,whitePeriods,blackByoYomi,whiteByoYomi,running,paused,moveNo,serverTime` | 同 | 毫秒 |
| 断线 | `linkDown` + `pauseAt(lastRecvAt)` | 同 | 重连后 `resume()` |
| 结果码 | `B+R/W+R/B+T/W+T/B+7.5/draw/red_win/black_win` | 同 | 展示层走 `ResultText` |

### 13.15 iOS ↔ Android 联调条件

| 条件 | 说明 |
|---|---|
| 同一局域网 | 两台设备连同一个 Wi-Fi，且 AP **未开启客户端隔离**（酒店/公司 Wi-Fi 常见，会直接导致互 ping 不通） |
| 真机 | Bonjour 发现 + 本地网络权限只在真机可靠；iOS 模拟器与 Android 模拟器之间**无法互相发现 mDNS** |
| iOS 版本 | 建议 iOS 15+（`NWBrowser`/`NWListener` 稳定，`async/await` 可用） |
| 权限 | 首次启动点「允许」本地网络；拒绝后需手动去设置打开 |
| 端口 | 41234 起，若被占用会自动顺延；以 TXT `port` 为准，不要写死 |
| 手动 IP | iOS 侧必须保留「手动输入 IP:端口」兜底，Android 已有；mDNS 失败时用它联调 |
| 抓包 | `Console.app` 过滤 `qingyi.lan`；Android 侧 `adb logcat -s LanHub LanHost LanClient` |

**联调顺序建议**：Android 房主 + iOS 客机 → 跑通 hello/game_start/move_ack；再反向 iOS 房主 + Android 客机；最后测断线重连（飞行模式 10s 再关）。

### 13.16 iOS 验收清单

- [ ] 发现：同一 Wi-Fi 下 3s 内出现房间，棋种过滤正确（`_qingyi-go`/`_qingyi-xiangqi`）
- [ ] 僵尸房间：房主杀进程后 ≤12s 从列表消失；点击失效房间给出中文提示
- [ ] 对局：落子、悔棋、认输、求和、时钟同步
- [ ] 坐席视角：后手玩家整盘旋转 180°，棋子文字正向，点击不串位
- [ ] 结果：所有结局均为中文（无 `B+R`/`W+7.5` 泄漏）
- [ ] 断线：飞行模式 10s 恢复；超过 60s 判负并显示中文
- [ ] 手动 IP 加入可用
