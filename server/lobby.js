/**
 * Vortex Lobby Server — zero-knowledge WebSocket relay
 *
 * Handles room management, player coordination, and encrypted data relay.
 * All game data is opaque (AES-256-GCM encrypted by clients).
 *
 * Protocol: JSON over WS  { "type": "<cmd>", "payload": { ... } }
 */

const { WebSocketServer, WebSocket } = require("ws");

const PORT = parseInt(process.env.PORT || "8080", 10);

// ── State ──────────────────────────────────────────────────────────

/** @type {Map<string, Room>} roomId → Room */
const rooms = new Map();

/** @type {Map<WebSocket, Client>} ws → Client */
const clients = new Map();

class Client {
  constructor(ws, playerId, region) {
    this.ws = ws;
    this.playerId = playerId;
    this.region = region;
    this.displayName = "Player";
    this.roomId = null;
    this.isReady = false;
  }
  send(type, payload) {
    if (this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type, payload }));
    }
  }
}

class Room {
  constructor(id, code, host) {
    this.roomId = id;
    this.roomCode = code;
    this.hostId = host.playerId;
    this.hostName = host.displayName;
    this.gameName = "";
    this.platform = "";
    this.coreName = "";
    this.maxPlayers = 2;
    this.region = host.region;
    this.passwordProtected = false;
    this.password = null;
    this.encrypted = true;
    this.isPublic = true;
    this.playability = "Waiting";
    this.createdAt = Date.now();
    /** @type {Map<string, Client>} playerId → Client */
    this.players = new Map();
    this.players.set(host.playerId, host);
  }

  toJSON() {
    return {
      room_id: this.roomId,
      room_code: this.roomCode,
      host_name: this.hostName,
      game_name: this.gameName,
      platform: this.platform,
      core_name: this.coreName,
      player_count: this.players.size,
      max_players: this.maxPlayers,
      latency_ms: 0,
      region: this.region,
      password_protected: this.passwordProtected,
      encrypted: this.encrypted,
      is_public: this.isPublic,
      playability: this.playability,
      created_at: this.createdAt,
    };
  }

  broadcast(type, payload, exclude = null) {
    for (const client of this.players.values()) {
      if (client.playerId !== exclude) client.send(type, payload);
    }
  }

  playerList() {
    let idx = 0;
    return [...this.players.values()].map((c) => ({
      player_id: c.playerId,
      display_name: c.displayName,
      player_index: idx++,
      latency_ms: 0,
      is_host: c.playerId === this.hostId,
      is_ready: c.isReady,
    }));
  }
}

// ── Helpers ─────────────────────────────────────────────────────────

let roomCounter = 0;
function generateRoomCode() {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let code = "VRTX-";
  for (let i = 0; i < 4; i++) code += chars[Math.floor(Math.random() * chars.length)];
  return code;
}

function generateRoomId() {
  return `room_${++roomCounter}_${Date.now().toString(36)}`;
}

function sendRoomList(client) {
  const publicRooms = [...rooms.values()]
    .filter((r) => r.isPublic && r.players.size < r.maxPlayers)
    .sort((a, b) => b.players.size - a.players.size)
    .slice(0, 20)
    .map((r) => r.toJSON());
  client.send("room_list", { rooms: publicRooms });
}

function sendAllRooms(client) {
  const allPublic = [...rooms.values()]
    .filter((r) => r.isPublic)
    .map((r) => r.toJSON());
  client.send("room_list", { rooms: allPublic });
}

function removePlayerFromRoom(client) {
  if (!client.roomId) return;
  const room = rooms.get(client.roomId);
  if (!room) { client.roomId = null; return; }

  room.players.delete(client.playerId);
  client.roomId = null;
  client.isReady = false;

  if (room.players.size === 0) {
    rooms.delete(room.roomId);
    console.log(`Room ${room.roomCode} deleted (empty)`);
  } else {
    // If host left, promote next player
    if (room.hostId === client.playerId) {
      const newHost = room.players.values().next().value;
      room.hostId = newHost.playerId;
      room.hostName = newHost.displayName;
    }
    room.broadcast("player_left", { player_id: client.playerId });
  }
}

// ── Message Handlers ────────────────────────────────────────────────

function handleMessage(client, raw) {
  let msg;
  try {
    msg = JSON.parse(raw);
  } catch {
    client.send("error", { message: "Invalid JSON" });
    return;
  }

  const { type, payload } = msg;

  switch (type) {
    case "create_room": {
      if (client.roomId) removePlayerFromRoom(client);

      const p = payload || {};
      client.displayName = String(p.host_name || client.displayName).slice(0, 32);

      const room = new Room(generateRoomId(), generateRoomCode(), client);
      room.gameName = String(p.game_name || "").slice(0, 100);
      room.platform = String(p.platform || "").slice(0, 32);
      room.coreName = String(p.core_name || "").slice(0, 64);
      room.maxPlayers = Math.min(Math.max(parseInt(p.max_players) || 2, 2), 8);
      room.region = String(p.region || client.region).slice(0, 16);
      room.encrypted = p.encrypted !== false;
      room.isPublic = p.is_public !== false;
      if (p.password_protected && p.password) {
        room.passwordProtected = true;
        room.password = String(p.password);
      }

      rooms.set(room.roomId, room);
      client.roomId = room.roomId;
      client.isReady = false;

      console.log(`Room created: ${room.roomCode} by ${client.displayName} (${room.gameName})`);

      client.send("room_joined", {
        room: room.toJSON(),
        players: room.playerList(),
      });
      break;
    }

    case "join_room": {
      const p = payload || {};
      const room = rooms.get(p.room_id);
      if (!room) { client.send("error", { message: "Room not found" }); break; }
      if (room.players.size >= room.maxPlayers) { client.send("error", { message: "Room is full" }); break; }
      if (room.passwordProtected && room.password !== p.password) {
        client.send("error", { message: "Wrong password" }); break;
      }

      if (client.roomId) removePlayerFromRoom(client);
      client.displayName = String(p.player_name || client.displayName).slice(0, 32);
      client.roomId = room.roomId;
      client.isReady = false;
      room.players.set(client.playerId, client);

      room.broadcast("player_joined", {
        player_id: client.playerId,
        display_name: client.displayName,
        player_index: room.players.size - 1,
        latency_ms: 0,
        is_host: false,
        is_ready: false,
      }, client.playerId);

      client.send("room_joined", {
        room: room.toJSON(),
        players: room.playerList(),
      });
      break;
    }

    case "join_by_code": {
      const p = payload || {};
      const code = String(p.room_code || "").toUpperCase();
      const room = [...rooms.values()].find((r) => r.roomCode === code);
      if (!room) { client.send("error", { message: "Room not found with that code" }); break; }
      if (room.players.size >= room.maxPlayers) { client.send("error", { message: "Room is full" }); break; }
      if (room.passwordProtected && room.password !== p.password) {
        client.send("error", { message: "Wrong password" }); break;
      }

      if (client.roomId) removePlayerFromRoom(client);
      client.displayName = String(p.player_name || client.displayName).slice(0, 32);
      client.roomId = room.roomId;
      client.isReady = false;
      room.players.set(client.playerId, client);

      room.broadcast("player_joined", {
        player_id: client.playerId,
        display_name: client.displayName,
        player_index: room.players.size - 1,
        latency_ms: 0,
        is_host: false,
        is_ready: false,
      }, client.playerId);

      client.send("room_joined", {
        room: room.toJSON(),
        players: room.playerList(),
      });
      break;
    }

    case "leave_room": {
      removePlayerFromRoom(client);
      sendRoomList(client);
      break;
    }

    case "set_ready": {
      const p = payload || {};
      client.isReady = !!p.ready;
      const room = rooms.get(client.roomId);
      if (room) {
        room.broadcast("player_ready", {
          player_id: client.playerId,
          ready: client.isReady,
        });
      }
      break;
    }

    case "list_rooms": {
      sendRoomList(client);
      break;
    }

    case "list_all_rooms": {
      sendAllRooms(client);
      break;
    }

    case "relay": {
      const p = payload || {};
      const room = rooms.get(client.roomId);
      if (!room) break;

      if (p.target) {
        const target = room.players.get(p.target);
        if (target) target.send("relay", { from: client.playerId, data: p.data });
      } else {
        room.broadcast("relay", { from: client.playerId, data: p.data }, client.playerId);
      }
      break;
    }

    case "chat": {
      const p = payload || {};
      const msg = String(p.message || "").slice(0, 500);
      const room = rooms.get(client.roomId);
      if (room && msg) {
        room.broadcast("chat", { from: client.displayName, message: msg });
      }
      break;
    }

    case "start_game": {
      const room = rooms.get(client.roomId);
      if (!room || room.hostId !== client.playerId) {
        client.send("error", { message: "Only the host can start the game" }); break;
      }
      room.playability = "In-Game";
      const sessionKey = Math.random().toString(36).slice(2, 14);
      room.broadcast("game_starting", { session_key: sessionKey });
      console.log(`Game started in ${room.roomCode}: ${room.gameName}`);
      break;
    }

    default:
      client.send("error", { message: `Unknown command: ${type}` });
  }
}

// ── Server (HTTP + WebSocket upgrade for Render/cloud compatibility) ──

const http = require("http");

const server = http.createServer((req, res) => {
  if (req.url === "/health" || req.url === "/") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      status: "ok",
      rooms: rooms.size,
      clients: clients.size,
      uptime: Math.floor(process.uptime())
    }));
  } else {
    res.writeHead(404);
    res.end("Not found");
  }
});

const wss = new WebSocketServer({ server, path: "/ws" });

wss.on("connection", (ws, req) => {
  const playerId = req.headers["x-vortex-player"] ||
    `anon_${Math.random().toString(36).slice(2, 10)}`;
  const region = req.headers["x-vortex-region"] || "";

  const client = new Client(ws, playerId, region);
  clients.set(ws, client);

  console.log(`Client connected: ${playerId} (${req.socket.remoteAddress})`);

  ws.on("message", (data) => handleMessage(client, data.toString()));

  ws.on("close", () => {
    removePlayerFromRoom(client);
    clients.delete(ws);
    console.log(`Client disconnected: ${playerId}`);
  });

  ws.on("error", (err) => {
    console.error(`WS error for ${playerId}: ${err.message}`);
  });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`Vortex Lobby Server running on port ${PORT}`);
  console.log(`  HTTP health: http://0.0.0.0:${PORT}/health`);
  console.log(`  WebSocket:   ws://0.0.0.0:${PORT}/ws`);
});

// Clean up stale empty rooms every 5 minutes
setInterval(() => {
  const now = Date.now();
  for (const [id, room] of rooms) {
    if (room.players.size === 0 || now - room.createdAt > 6 * 3600 * 1000) {
      rooms.delete(id);
    }
  }
}, 5 * 60 * 1000);

// Periodic stats
setInterval(() => {
  console.log(`  Rooms: ${rooms.size} | Clients: ${clients.size}`);
}, 30_000);
