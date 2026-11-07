#!/usr/bin/env node
/**
 * 鲸鱼娘公网中继（极简版）
 *
 * 作用：手机端（被操控端）与伴侣端（遥控端）都主动连到这里，
 * 按 pairCode 放进同一个房间，中继只做转发，不解析业务内容、不落盘。
 *
 * 启动：
 *   RELAY_TOKEN=换成你的长随机串 PORT=8787 node server.js
 *
 * 生产环境请放在 Nginx/Caddy 后面走 wss://（见 README.md）。
 */

'use strict';

const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');

const PORT = Number(process.env.PORT || 8787);
const TOKEN = process.env.RELAY_TOKEN || '';
const MAX_PAYLOAD = 8 * 1024 * 1024;
const PING_INTERVAL_MS = 25_000;

if (!TOKEN) {
  console.warn('[relay] 警告：未设置 RELAY_TOKEN，任何知道地址的人都能连接。生产环境务必设置。');
}

/** pairCode -> { phones:Set, companions:Set } */
const rooms = new Map();

function roomOf(code) {
  let room = rooms.get(code);
  if (!room) {
    room = { phones: new Set(), companions: new Set() };
    rooms.set(code, room);
  }
  return room;
}

function safeSend(socket, data) {
  if (socket.readyState === WebSocket.OPEN) {
    socket.send(typeof data === 'string' ? data : JSON.stringify(data));
  }
}

function sendError(socket, code, message) {
  safeSend(socket, { type: 'error', code, message, ts: Date.now() });
}

function broadcast(set, payload) {
  const text = JSON.stringify(payload);
  for (const client of set) safeSend(client, text);
}

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, rooms: rooms.size, uptime: process.uptime() }));
    return;
  }
  res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
  res.end('whale-chan relay\n');
});

const wss = new WebSocketServer({ server, path: '/ws', maxPayload: MAX_PAYLOAD });

wss.on('connection', (socket) => {
  socket.isAlive = true;
  socket.meta = null;

  socket.on('pong', () => { socket.isAlive = true; });

  socket.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      sendError(socket, 'BAD_JSON', '消息不是合法 JSON');
      return;
    }

    const type = msg.type;

    // 1) 注册
    if (type === 'register') {
      if (TOKEN && msg.token !== TOKEN) {
        sendError(socket, 'BAD_TOKEN', '中继令牌错误');
        socket.close(4001, 'bad token');
        return;
      }
      const role = msg.role;
      const pairCode = String(msg.pairCode || '').toUpperCase().trim();
      if (role !== 'phone' && role !== 'companion') {
        sendError(socket, 'BAD_ROLE', 'role 必须是 phone 或 companion');
        return;
      }
      if (!/^[A-Z0-9]{4,12}$/.test(pairCode)) {
        sendError(socket, 'BAD_PAIR_CODE', '配对码必须是 4-12 位字母数字');
        return;
      }

      // 同一连接重复注册：先从旧房间移除
      if (socket.meta) removeFromRoom(socket);

      socket.meta = { role, pairCode, deviceId: String(msg.deviceId || '') };
      const room = roomOf(pairCode);
      if (role === 'phone') room.phones.add(socket); else room.companions.add(socket);

      safeSend(socket, { type: 'registered', ok: true, role, pairCode });
      console.log(`[relay] ${role} 加入 ${pairCode}（手机 ${room.phones.size} / 伴侣 ${room.companions.size}）`);

      // 伴侣端加入时，如果手机在线，通知手机有新遥控端
      if (role === 'companion') {
        broadcast(room.phones, { type: 'notice', text: '有新的遥控端接入', ts: Date.now() });
      }
      return;
    }

    if (!socket.meta) {
      sendError(socket, 'NOT_REGISTERED', '先发送 register');
      return;
    }

    const { role, pairCode } = socket.meta;
    const room = rooms.get(pairCode);
    if (!room) return;

    // 2) 业务转发：只认这两种信封
    if (type === 'toPhone') {
      if (role !== 'companion') {
        sendError(socket, 'BAD_ROLE', '只有伴侣端能 toPhone');
        return;
      }
      if (room.phones.size === 0) {
        sendError(socket, 'NO_PHONE', '手机端不在线');
        return;
      }
      broadcast(room.phones, { type: 'fromCompanion', payload: msg.payload, ts: Date.now() });
      return;
    }

    if (type === 'toCompanion') {
      if (role !== 'phone') {
        sendError(socket, 'BAD_ROLE', '只有手机端能 toCompanion');
        return;
      }
      broadcast(room.companions, { type: 'fromPhone', payload: msg.payload, ts: Date.now() });
      return;
    }

    if (type === 'ping') {
      safeSend(socket, { type: 'pong', ts: Date.now() });
      return;
    }

    sendError(socket, 'BAD_TYPE', `不支持的消息类型：${type}`);
  });

  socket.on('close', () => {
    removeFromRoom(socket);
  });

  socket.on('error', (err) => {
    console.warn('[relay] socket error:', err.message);
  });
});

function removeFromRoom(socket) {
  const meta = socket.meta;
  if (!meta) return;
  const room = rooms.get(meta.pairCode);
  if (room) {
    room.phones.delete(socket);
    room.companions.delete(socket);
    if (room.phones.size === 0 && room.companions.size === 0) rooms.delete(meta.pairCode);
    if (meta.role === 'phone' && room.companions.size > 0) {
      broadcast(room.companions, { type: 'notice', text: '手机端已离线，稍后重试', ts: Date.now() });
    }
  }
  socket.meta = null;
}

// 心跳：清理半死连接
setInterval(() => {
  for (const socket of wss.clients) {
    if (socket.isAlive === false) {
      socket.terminate();
      continue;
    }
    socket.isAlive = false;
    try { socket.ping(); } catch { /* ignore */ }
  }
}, PING_INTERVAL_MS);

server.listen(PORT, () => {
  console.log(`[relay] listening on :${PORT} (path /ws, token=${TOKEN ? 'on' : 'off'})`);
});

process.on('SIGTERM', () => {
  console.log('[relay] shutting down');
  wss.close(() => server.close(() => process.exit(0)));
});
