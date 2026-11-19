/**
 * Metrolist Web Backend Server
 *
 * A web player that syncs with the Android app via:
 * - Spotify API integration
 * - Google Drive webhook sync
 * - Local file-based storage (can be upgraded to PostgreSQL)
 */

import express from 'express';
import cors from 'cors';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';
import dotenv from 'dotenv';
import yts from 'yt-search';

dotenv.config();

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = process.env.PORT || 3000;

// ─────────────────────────────────────────────────────────────────────────────
// MIDDLEWARE
// ─────────────────────────────────────────────────────────────────────────────

app.use(cors({
  origin: process.env.FRONTEND_URL || '*',
  credentials: true,
}));
app.use(express.json({ limit: '50mb' }));
app.use(express.static(path.join(__dirname, 'public')));

// ─────────────────────────────────────────────────────────────────────────────
// DATA PERSISTENCE
// ─────────────────────────────────────────────────────────────────────────────

const DATA_DIR = path.join(__dirname, 'data');
const LIBRARY_FILE = path.join(DATA_DIR, 'library.json');

// Ensure data directory exists
if (!fs.existsSync(DATA_DIR)) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
}

// Load library from file
function loadLibrary() {
  try {
    if (fs.existsSync(LIBRARY_FILE)) {
      return JSON.parse(fs.readFileSync(LIBRARY_FILE, 'utf-8'));
    }
  } catch (e) {
    console.error('Error loading library.json:', e);
  }
  return { liked: [], playlists: [], users: {} };
}

// Save library to file
function saveLibrary(data) {
  try {
    fs.writeFileSync(LIBRARY_FILE, JSON.stringify(data, null, 2), 'utf-8');
  } catch (e) {
    console.error('Error saving library.json:', e);
  }
}

// In-memory library (can be replaced with PostgreSQL)
let library = loadLibrary();

// ─────────────────────────────────────────────────────────────────────────────
// API ROUTES
// ─────────────────────────────────────────────────────────────────────────────

// Health check endpoint (for uptime monitoring)
app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    service: 'metrolist-web-backend',
    timestamp: new Date().toISOString()
  });
});

// Home feed endpoint (for welcome screen)
app.get('/api/home', async (req, res) => {
  try {
    const token = req.headers.authorization?.replace('Bearer ', '');

    // Get search results for popular tracks
    const searchRes = await yts('Top hits 2024');
    const trending = (searchRes?.videos?.slice(0, 10) || []).map(item => ({
      id: item.videoId,
      title: item.title,
      artist: item.author?.name || 'Artista desconocido',
      album: item.channel?.name || '',
      duration: item.duration?.toString() || '0:00',
      durationSec: item.timestamp?.seconds || 0,
      thumbnail: item.thumbnail || '',
      explicit: item.officialVideo || false,
    }));

    res.json({
      greeting: new Date().getHours() < 12
        ? '¡Buenos días!'
        : new Date().getHours() < 18
        ? '¡Buenas tardes!'
        : '¡Buenas noches!',
      trending,
    });
  } catch (e) {
    console.error('[API] Error loading home feed:', e.message);
    res.json({
      greeting: '¡Bienvenido a Metrolist!',
      trending: [],
    });
  }
});

// Search YouTube Music tracks
app.get('/api/search', async (req, res) => {
  const query = req.query.q;
  if (!query) return res.status(400).json({ error: 'Query vacía' });

  try {
    // Use yt-search for basic YouTube search
    const results = await yts(query);

    if (!results || results.count === 0) {
      return res.json({ query, items: [], total: 0 });
    }

    const songs = (results.videos || [])
      .slice(0, 20)
      .map(item => ({
        id: item.videoId,
        title: item.title || 'Título desconocido',
        artist: item.author?.name || 'Artista desconocido',
        album: item.channel?.name || '',
        duration: item.duration?.toString() || '0:00',
        durationSec: item.timestamp?.seconds || 0,
        thumbnail: item.thumbnail || '',
        explicit: item.officialVideo || false,
        thumbnailId: item.videoId,
      }));

    res.json({
      query,
      items: songs,
      total: songs.length,
    });
  } catch (e) {
    console.error('[API] Error searching:', e.message);
    res.status(500).json({ error: `Error en búsqueda: ${e.message}` });
  }
});

// Get user's library (liked songs)
app.get('/api/library', (req, res) => {
  const userId = req.query.userId || 'anonymous';
  const userLib = library.users[userId] || { liked: [], playlists: [] };
  res.json(userLib);
});

// Like a song
app.post('/api/like', (req, res) => {
  const { song, userId = 'anonymous' } = req.body;
  if (!song?.id) return res.status(400).json({ error: 'Datos incompletos' });

  // Ensure user exists
  if (!library.users[userId]) {
    library.users[userId] = { liked: [], playlists: [] };
  }

  const userLib = library.users[userId];
  const exists = userLib.liked.some(s => s.id === song.id);

  if (!exists) {
    const newEntry = {
      ...song,
      likedAt: new Date().toISOString(),
      driveSynced: false,
    };
    userLib.liked = [newEntry, ...userLib.liked];
    saveLibrary(library);

    // Queue for Google Drive sync
    queueDriveSync(song.id, song.title, song.artist || '');
  }

  res.json({ status: 'ok', id: song.id, liked: true });
});

// Unlike a song
app.delete('/api/like', (req, res) => {
  const { id, userId = 'anonymous' } = req.body;
  if (!id) return res.status(400).json({ error: 'ID requerido' });

  const userLib = library.users[userId];
  if (userLib) {
    userLib.liked = userLib.liked.filter(s => s.id !== id);
    saveLibrary(library);
  }

  res.json({ status: 'ok', id,liked: false });
});

// Create a playlist
app.post('/api/playlists', (req, res) => {
  const { name, userId = 'anonymous' } = req.body;
  if (!name) return res.status(400).json({ error: 'Nombre requerido' });

  if (!library.users[userId]) {
    library.users[userId] = { liked: [], playlists: [] };
  }

  const userLib = library.users[userId];
  const newPl = {
    id: 'pl_' + Date.now(),
    name: name.trim(),
    createdAt: new Date().toISOString(),
    songs: [],
  };
  userLib.playlists = [...(userLib.playlists || []), newPl];
  saveLibrary(library);

  res.json(newPl);
});

// Add song to playlist
app.post('/api/playlists/:id/songs', (req, res) => {
  const { id: playlistId } = req.params;
  const { song } = req.body;
  if (!song?.id) return res.status(400).json({ error: 'Canción requerida' });

  const userLib = library.users['anonymous'];
  if (!userLib) return res.status(404).json({ error: 'Usuario no encontrado' });

  const pl = userLib.playlists.find(p => p.id === playlistId);
  if (!pl) return res.status(404).json({ error: 'Playlist no encontrada' });

  if (!pl.songs.some(s => s.id === song.id)) {
    pl.songs.push({ ...song, addedAt: new Date().toISOString() });
    saveLibrary(library);
  }

  res.json({ status: 'ok', playlist: pl });
});

// ─────────────────────────────────────────────────────────────────────────────
// AUDIO STREAMING (via ytdl-core)
// ─────────────────────────────────────────────────────────────────────────────

import pkg from '@distube/ytdl-core';
const { getInfo } = pkg;

// Stream audio URL (direct YouTube audio stream)
app.get('/api/stream/:videoId', async (req, res) => {
  const videoId = req.params.videoId;
  if (!videoId) return res.status(400).json({ error: 'videoId requerido' });

  try {
    // Use ytdl-core's direct audio stream
    const info = await getInfo(videoId);

    // Find best audio format
    let audioFormat = null;
    if (info.formats && Array.isArray(info.formats)) {
      audioFormat = info.formats
        .filter(f => f.hasAudio && !f.hasVideo)
        .sort((a, b) => (b.audioBitrate || 0) - (a.audioBitrate || 0))[0];
    }

    if (!audioFormat?.url) {
      // Fallback: try webm format
      audioFormat = info.formats?.find(f => f.format === 'webm' && f.hasAudio);
    }

    if (!audioFormat?.url) {
      return res.status(404).json({
        error: 'No se encontró stream de audio',
        videoId
      });
    }

    // Stream the audio
    const response = await fetch(audioFormat.url, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
      }
    });

    res.status(200);
    res.setHeader('Content-Type', audioFormat.mimeType || 'audio/mpeg');
    res.setHeader('Accept-Ranges', 'bytes');

    // Pipe the response
    const reader = response.body.getReader();
    async function pump() {
      const { done, value } = await reader.read();
      if (done) return res.end();
      res.write(value);
      return pump();
    }
    await pump();
  } catch (e) {
    console.error(`[Stream] Error streaming ${videoId}:`, e.message);
    if (!res.headersSent) {
      res.status(500).json({ error: e.message });
    }
  }
});

// Get track lyrics
app.get('/api/lyrics/:videoId', async (req, res) => {
  const videoId = req.params.videoId;

  try {
    const client = await getYtClient();
    const lyrics = await client.music.getLyrics(videoId);
    res.json({
      description: lyrics?.description?.text || lyrics?.text || 'Letras no disponibles.',
    });
  } catch (e) {
    res.json({ description: 'Letras no disponibles.' });
  }
});

// ─────────────────────────────────────────────────────────────────────────────
// GOOGLE DRIVE SYNC (via webhook)
// ─────────────────────────────────────────────────────────────────────────────

const DEFAULT_DRIVE_WEBHOOK = process.env.DRIVE_WEBHOOK_URL ||
  'https://script.google.com/macros/s/AKfycbzHmTqyBNsSoTXqfm3dDpowNzsS3a0mhf02fcDFoUh6GN8839_W-xo9CfIeOhyc3tXf/exec';

async function queueDriveSync(songId, title, artist) {
  try {
    const info = await getInfo(songId);
    const format = chooseFormat(info.formats, {
      filter: { hasAudio: true, hasVideo: false }
    });
    if (!format || !format.url) return;

    const audioRes = await fetch(format.url);
    const audioBuffer = await audioRes.arrayBuffer();
    const base64Audio = Buffer.from(audioBuffer).toString('base64');

    const cleanTitle = (title || songId).replace(/[/\\?%*:|"<>]/g, '_');
    const cleanArtist = (artist || '').replace(/[/\\?%*:|"<>]/g, '_');
    const filename = cleanArtist ? `${cleanArtist} - ${cleanTitle}.m4a` : `${cleanTitle}.m4a`;

    const driveRes = await fetch(DEFAULT_DRIVE_WEBHOOK, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ filename, base64: base64Audio }),
    });

    if (driveRes.ok) {
      console.log(`[Drive] Song backed up: ${filename}`);
      // Update like status
      const userLib = library.users['anonymous'];
      if (userLib) {
        const song = userLib.liked.find(s => s.id === songId);
        if (song) song.driveSynced = true;
        saveLibrary(library);
      }
    }
  } catch (e) {
    console.error(`[Drive] Error syncing ${songId}:`, e.message);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// AUTHENTICATION (Google OAuth placeholder)
// ─────────────────────────────────────────────────────────────────────────────

app.post('/api/auth/google', (req, res) => {
  // Google Sign-In is handled on the frontend
  // The frontend will receive an ID token and verify it server-side
  res.json({
    message: 'Use Google Sign-In on frontend',
    docs: 'https://developers.google.com/identity/sign-in/web/server-side-auth'
  });
});

// Verify Google ID token
app.post('/api/auth/verify', async (req, res) => {
  const { idToken } = req.body;
  if (!idToken) return res.status(400).json({ error: 'idToken requerido' });

  // TODO: Verify ID token with Google OAuth2 API
  // See: https://developers.google.com/identity/sign-in/web/backend-auth
  res.json({
    verified: true,
    message: 'ID token received - implement verification with Google API'
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// SPOTIFY API INTEGRATION (REST API only for now)
// ─────────────────────────────────────────────────────────────────────────────

// These endpoints will work when SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET are set
// They allow full Spotify integration (search, library, playlists, etc.)

const SPOTIFY_BASE_URL = 'https://api.spotify.com/v1';

async function getSpotifyToken() {
  if (!process.env.SPOTIFY_CLIENT_ID || !process.env.SPOTIFY_CLIENT_SECRET) {
    return null;
  }

  const response = await fetch('https://accounts.spotify.com/api/token', {
    method: 'POST',
    headers: {
      'Authorization': 'Basic ' + Buffer.from(
        `${process.env.SPOTIFY_CLIENT_ID}:${process.env.SPOTIFY_CLIENT_SECRET}`
      ).toString('base64'),
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: new URLSearchParams({ grant_type: 'client_credentials' }),
  });

  if (!response.ok) return null;
  const data = await response.json();
  return data.access_token;
}

// Spotify search (REST API)
app.get('/api/spotify/search', async (req, res) => {
  const query = req.query.q;
  if (!query) return res.status(400).json({ error: 'Query vacía' });

  const token = await getSpotifyToken();
  if (!token) {
    return res.status(503).json({
      error: 'Spotify API no configurado',
      setup: 'Set SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET environment variables'
    });
  }

  try {
    const params = new URLSearchParams({
      q: query,
      type: 'track,artist,album,playlist',
      limit: '20',
    });

    const response = await fetch(`${SPOTIFY_BASE_URL}/search?${params}`, {
      headers: { 'Authorization': `Bearer ${token}` }
    });

    if (!response.ok) throw new Error('API error');
    res.json(await response.json());
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ─────────────────────────────────────────────────────────────────────────────
// CATCH-ALL (SPA fallback)
// ─────────────────────────────────────────────────────────────────────────────

app.get('*', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// ─────────────────────────────────────────────────────────────────────────────
// START SERVER
// ─────────────────────────────────────────────────────────────────────────────

app.listen(PORT, () => {
  console.log(`
╔══════════════════════════════════════════════════════════════╗
║  Metrolist Web Backend                                        ║
║  http://localhost:${PORT}                                   ║
╠══════════════════════════════════════════════════════════════╣
║  Environment Variables:                                      ║
║    - SPOTIFY_CLIENT_ID      (optional - for Spotify API)     ║
║    - SPOTIFY_CLIENT_SECRET  (optional - for Spotify API)     ║
║    - DRIVE_WEBHOOK_URL      (optional - Google Drive sync)   ║
║    - FRONTEND_URL           (optional - CORS origin)         ║
╚══════════════════════════════════════════════════════════════╝
`);
});