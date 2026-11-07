# 鲸鱼娘公网中继（Relay）

校园网常有 **AP 隔离**，手机拿不到公网 IP，伴侣端无法直连。
中继解决方式：手机端与伴侣端都**主动连出去**，由中继按 `pairCode` 转发。

```
手机（鲸鱼娘）  ──wss──▶  中继服务器  ◀──wss──  遥控端（鲸鱼娘·遥控）
      │                     │
      └──── 同一 pairCode ───┘        中继只转发，不落盘、不解析业务
```

## 1. 启动

需要 Node.js 18+。

```bash
cd relay
npm install
RELAY_TOKEN='换成至少32位的随机串' PORT=8787 node server.js
```

健康检查：

```bash
curl http://127.0.0.1:8787/health
# {"ok":true,"rooms":0,"uptime":1.23}
```

> `RELAY_TOKEN` 不设置也能启动，但任何知道地址的人都能连。**生产环境必须设置**，
> 并在手机端/伴侣端填同一个令牌。

## 2. 生产部署（wss）

中继本身只说 `ws://`，TLS 交给反向代理。以 Nginx 为例：

```nginx
server {
    listen 443 ssl http2;
    server_name relay.example.com;

    ssl_certificate     /etc/letsencrypt/live/relay.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/relay.example.com/privkey.pem;

    location /ws {
        proxy_pass http://127.0.0.1:8787;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
    }
}
```

Docker（可选）：

```dockerfile
FROM node:20-alpine
WORKDIR /app
COPY package.json server.js ./
RUN npm install --omit=dev
EXPOSE 8787
CMD ["node", "server.js"]
```

## 3. 客户端配置

**手机端（鲸鱼娘）**：设置页 ⑥ → 打开「公网中继」→ 填：

| 字段 | 示例 |
|---|---|
| 中继地址 | `wss://relay.example.com/ws` |
| 中继令牌 | 与服务端 `RELAY_TOKEN` 一致 |
| 中继配对码 | 页面自动生成，6 位（如 `K7R8U8`） |

**遥控端（鲸鱼娘·遥控）**：连接弹窗填：

| 字段 | 示例 |
|---|---|
| 地址 | `wss://relay.example.com/ws` |
| 令牌 | 同一 `RELAY_TOKEN` |
| 中继配对码 | 手机端显示的 6 位码 |

直连模式仍然保留：地址填 `192.168.1.5:8787`、配对码留空即可（同一 Wi-Fi）。

## 4. 协议

### 注册

```json
C→S {"type":"register","role":"phone|companion","deviceId":"...","pairCode":"K7R8U8","token":"...","protocol":2}
S→C {"type":"registered","ok":true,"role":"phone","pairCode":"K7R8U8"}
```

### 指令（遥控端 → 手机）

```json
C→S {"type":"toPhone","payload":{"type":"COMMAND","text":"去QQ找张三说晚安","id":"uuid"}}
S→手机 {"type":"fromCompanion","payload":{"type":"COMMAND","text":"去QQ找张三说晚安"}}
```

手机端收到 `COMMAND` 后**直接当任务执行**（不再区分聊天/任务模式）。

### 状态（手机 → 遥控端）

```json
{"type":"state","status":"THINKING|EXECUTING_ACTION|WAITING|IDLE","step":3,"maxSteps":0,"detail":"第 3 步：点击 发送"}
{"type":"step","record":{"stepIndex":3,"result":"fail","errorCategory":"NODE_NOT_FOUND","errorDetail":"没有找到“发送”按钮"}}
{"type":"task_end","ok":false,"summary":"……"}
```

### 其他

- `{"type":"ping"}` / `{"type":"pong"}`：双方每 25–30 秒心跳；
- `{"type":"error","code":"NO_PHONE","message":"手机端不在线"}`；
- 单帧上限 8MB（附件 base64）。

## 5. 安全与限制

- 中继**只转发**，不保存消息；但它是明文 WebSocket 的终点，必须由反向代理加 TLS；
- `RELAY_TOKEN` 是共享密钥，泄露等于任何人可向你的配对码发指令；
- 配对码 6 位，仅用于房间路由，**不能替代令牌**；
- 中继不做账号体系、不做离线消息：手机不在线时指令直接报 `NO_PHONE`；
- 高并发/多租户需要自行加限流、鉴权与可观测性，这个极简版不包含。
