const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const multer = require('multer');
const path = require('path');
const fs = require('fs');

// --- Configuration ---
const API_PORT = 3000;
const SIGNALING_PORT = 8080;
const UPLOADS_DIR = path.join(__dirname, 'uploads');

// Ensure uploads directory exists
if (!fs.existsSync(UPLOADS_DIR)) {
    fs.mkdirSync(UPLOADS_DIR);
}

// --- 1. Gallery API (Express) ---
const app = express();

// Configure Multer for image uploads
const storage = multer.diskStorage({
    destination: (req, file, cb) => cb(null, UPLOADS_DIR),
    filename: (req, file, cb) => {
        const uniqueSuffix = Date.now() + '-' + Math.round(Math.random() * 1E9);
        cb(null, 'photo-' + uniqueSuffix + path.extname(file.originalname));
    }
});
const upload = multer({ storage: storage });

// Static folder for serving images
app.use('/uploads', express.static(UPLOADS_DIR));

// GET /api/images - List all uploaded images
app.get('/api/images', (req, res) => {
    fs.readdir(UPLOADS_DIR, (err, files) => {
        if (err) return res.status(500).json({ error: 'Failed to read directory' });
        // Filter for images and sort by name (which includes timestamp)
        const images = files.filter(file => /\.(jpg|jpeg|png|webp)$/i.test(file)).sort();
        res.json(images);
    });
});

// POST /api/upload - Handle image upload
app.post('/api/upload', upload.single('image'), (req, res) => {
    if (!req.file) return res.status(400).send('No file uploaded.');
    console.log(`[Gallery] Uploaded: ${req.file.filename}`);
    res.status(200).send('Upload successful');
});

app.listen(API_PORT, '0.0.0.0', () => {
    console.log(`[Gallery] API and Static server running on port ${API_PORT}`);
});


// --- 2. Signaling Server (WebSockets) ---
const wss = new WebSocket.Server({ port: SIGNALING_PORT });

wss.on('connection', (ws) => {
    console.log('[Signaling] New client connected');

    ws.on('message', (data) => {
        // Broadcast every message to all OTHER connected clients
        wss.clients.forEach((client) => {
            if (client !== ws && client.readyState === WebSocket.OPEN) {
                client.send(data.toString());
            }
        });
    });

    ws.on('close', () => {
        console.log('[Signaling] Client disconnected');
    });
});

console.log(`[Signaling] WebSocket server running on port ${SIGNALING_PORT}`);
