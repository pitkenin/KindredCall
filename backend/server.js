const express = require('express');
const WebSocket = require('ws');
const multer = require('multer');
const crypto = require('crypto');
const path = require('path');
const fs = require('fs');

// --- Configuration ---
// Both services bind to loopback only. A TLS-terminating reverse proxy
// (nginx) is expected in front, so that signaling, the gallery API and
// the TURN relay all share port 443 behind a single hostname.
const API_PORT = 3000;
const SIGNALING_PORT = 8080;
const UPLOADS_DIR = path.join(__dirname, 'uploads');

const TOKEN = process.env.KINDRED_TOKEN;
const NTFY_TOPIC = process.env.NTFY_TOPIC || '';

if (!TOKEN) {
    console.error('FATAL: KINDRED_TOKEN environment variable is not set.');
    console.error('See ecosystem.config.example.js');
    process.exit(1);
}

if (!fs.existsSync(UPLOADS_DIR)) fs.mkdirSync(UPLOADS_DIR);

// --- Auth ---
// A single shared bearer token, deliberately minimal. This is a two-device
// family deployment; the token's purpose is to keep internet background
// noise (scanners, stray connections) from reaching the phones, not to
// resist a determined attacker who holds the APK.
function bearerFrom(req) {
    const auth = (req.headers && req.headers['authorization']) || '';
    return auth.startsWith('Bearer ') ? auth.slice(7) : '';
}

function tokenOk(candidate) {
    const a = Buffer.from(String(candidate || ''));
    const b = Buffer.from(TOKEN);
    if (a.length !== b.length) return false;
    return crypto.timingSafeEqual(a, b);
}

// --- Alerting ---
function notify(text) {
    console.log(`[Alert] ${text}`);
    if (!NTFY_TOPIC) return;
    fetch(`https://ntfy.sh/${NTFY_TOPIC}`, { method: 'POST', body: text })
        .catch((e) => console.error('[Alert] send failed:', e.message));
}

// --- Gallery API ---
const app = express();

// Auth middleware must precede every route, including static uploads.
app.use((req, res, next) => {
    if (!tokenOk(bearerFrom(req))) return res.status(401).send('Unauthorized');
    next();
});

app.get('/health', (req, res) => res.status(200).send('ok'));

app.use('/uploads', express.static(UPLOADS_DIR));

const storage = multer.diskStorage({
    destination: (req, file, cb) => cb(null, UPLOADS_DIR),
    filename: (req, file, cb) => {
        const suffix = Date.now() + '-' + Math.round(Math.random() * 1e9);
        // Normalise the extension: the listing endpoint filters on it, so an
        // upload with a missing or odd extension would succeed but never appear.
        const ext = (path.extname(file.originalname) || '.jpg').toLowerCase();
        const safeExt = /^\.(jpg|jpeg|png|webp)$/.test(ext) ? ext : '.jpg';
        cb(null, 'photo-' + suffix + safeExt);
    },
});

const upload = multer({
    storage: storage,
    limits: { fileSize: 20 * 1024 * 1024, files: 1 },
    // The Android client sends a generic "image/*" content type, so match on
    // the type prefix rather than an exact subtype.
    fileFilter: (req, file, cb) => cb(null, /^image\//.test(file.mimetype)),
});

app.get('/api/images', (req, res) => {
    fs.readdir(UPLOADS_DIR, (err, files) => {
        if (err) return res.status(500).json({ error: 'Failed to read directory' });
        res.json(files.filter((f) => /\.(jpg|jpeg|png|webp)$/i.test(f)).sort());
    });
});

app.post('/api/upload', upload.single('image'), (req, res) => {
    if (!req.file) return res.status(400).send('No file uploaded.');
    console.log(`[Gallery] Uploaded: ${req.file.filename}`);
    res.status(200).send('Upload successful');
});

app.listen(API_PORT, '127.0.0.1', () => {
    console.log(`[Gallery] API listening on 127.0.0.1:${API_PORT}`);
});

// --- Signaling ---
const wss = new WebSocket.Server({
    host: '127.0.0.1',
    port: SIGNALING_PORT,
    verifyClient: (info, cb) => {
        if (tokenOk(bearerFrom(info.req))) return cb(true);
        console.warn('[Signaling] Rejected connection with bad/missing token');
        cb(false, 401, 'Unauthorized');
    },
});

// Per-role presence, used for offline alerting. Clients identify themselves
// with a ?role= query parameter on the WebSocket URL.
const lastSeen = {};
const alerted = {};

wss.on('connection', (ws, req) => {
    let role = 'UNKNOWN';
    try {
        role = new URL(req.url, 'http://localhost').searchParams.get('role') || 'UNKNOWN';
    } catch (e) { /* keep default */ }

    ws.role = role;
    ws.isAlive = true;
    lastSeen[role] = Date.now();
    alerted[role] = false;
    console.log(`[Signaling] ${role} connected`);

    ws.on('pong', () => {
        ws.isAlive = true;
        lastSeen[role] = Date.now();
    });

    ws.on('message', (data) => {
        lastSeen[role] = Date.now();
        wss.clients.forEach((client) => {
            if (client !== ws && client.readyState === WebSocket.OPEN) {
                client.send(data.toString());
            }
        });
    });

    ws.on('close', () => console.log(`[Signaling] ${role} disconnected`));
    ws.on('error', (e) => console.error(`[Signaling] ${role} socket error:`, e.message));
});

// Reap half-open sockets. Without this the server keeps broadcasting into
// connections that died silently, and offers are lost with no error.
const HEARTBEAT_MS = 30_000;
const heartbeat = setInterval(() => {
    wss.clients.forEach((ws) => {
        if (ws.isAlive === false) {
            console.log(`[Signaling] Terminating unresponsive ${ws.role}`);
            return ws.terminate();
        }
        ws.isAlive = false;
        ws.ping();
    });
}, HEARTBEAT_MS);

wss.on('close', () => clearInterval(heartbeat));

// Alert when a device has been unreachable for too long. For an unattended
// deployment, learning that something broke is most of the problem.
const OFFLINE_ALERT_MS = 15 * 60 * 1000;
const WATCHED_ROLES = ['GRANDMA', 'YULIA'];

setInterval(() => {
    const now = Date.now();
    for (const role of WATCHED_ROLES) {
        const online = [...wss.clients].some(
            (c) => c.role === role && c.readyState === WebSocket.OPEN
        );
        if (online) {
            if (alerted[role]) notify(`KindredCall: ${role} is back online`);
            alerted[role] = false;
            continue;
        }
        const gap = now - (lastSeen[role] || 0);
        if (lastSeen[role] && gap > OFFLINE_ALERT_MS && !alerted[role]) {
            alerted[role] = true;
            notify(`KindredCall: ${role} offline for ${Math.round(gap / 60000)} min`);
        }
    }
}, 60_000);

console.log(`[Signaling] WebSocket server listening on 127.0.0.1:${SIGNALING_PORT}`);
