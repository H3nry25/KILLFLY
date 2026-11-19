import express from 'express';
import cors from 'cors';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';
import dotenv from 'dotenv';
import { Innertube, UniversalCache, Platform } from 'youtubei.js';

dotenv.config();

// Configurar el evaluador JS para que Innertube descifre streams de audio en Node 22+
Platform.shim.eval = async (data, env) => {
  const fn = new Function(...Object.keys(env), data.output);
  return fn(...Object.values(env));
};

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = process.env.PORT || 3000;

const DEFAULT_DRIVE_WEBHOOK = process.env.DRIVE_WEBHOOK_URL ||
  'https://script.google.com/macros/s/AKfycbzHmTqyBNsSoTXqfm3dDpowNzsS3a0mhf02fcDFoUh6GN8839_W-xo9CfIeOhyc3tXf/exec';

// Middleware
app.use(cors({ origin: process.env.FRONTEND_URL || '*', credentials: true }));
app.use(express.json({ limit: '50mb' }));
app.use(express.static(path.join(__dirname, 'public')));

// Persistencia de biblioteca (Canciones favoritas y playlists)
const DATA_DIR = path.join(__dirname, 'data');
const LIBRARY_FILE = path.join(DATA_DIR, 'library.json');
if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });

function loadLibrary() {
  try {
    if (fs.existsSync(LIBRARY_FILE)) {
      const raw = fs.readFileSync(LIBRARY_FILE, 'utf-8').trim();
      if (raw) return JSON.parse(raw);
    }
  } catch (e) {
    console.error('Error leyendo library.json:', e.message);
  }
  return { liked: [], playlists: [] };
}

function saveLibrary(data) {
  try {
    fs.writeFileSync(LIBRARY_FILE, JSON.stringify(data, null, 2), 'utf-8');
  } catch (e) {
    console.error('Error guardando library.json:', e.message);
  }
}

let library = loadLibrary();

// Inicialización del motor InnerTube
let ytInstance = null;
async function getInnertube() {
  if (!ytInstance) {
    ytInstance = await Innertube.create({
      cache: new UniversalCache(false),
      generate_session_locally: true,
    });
    console.log('✅ Motor de YouTube Music e InnerTube listo');
  }
  return ytInstance;
}

function formatSongItem(item) {
  const id = item.id || item.videoId;
  const rawThumb = item.thumbnails?.[0]?.url || item.thumbnail || `https://i.ytimg.com/vi/${id}/hqdefault.jpg`;
  const highResThumb = rawThumb.replace(/=w\d+-h\d+.*$/, '=w544-h544-l90-rj');

  return {
    id: id,
    title: item.title || 'Canción',
    artist: item.artists?.[0]?.name || item.author?.name || 'Artista desconocido',
    album: item.album?.name || '',
    duration: item.duration?.text || item.duration?.toString() || '3:30',
    durationSec: item.duration?.seconds || 210,
    thumbnail: highResThumb,
  };
}

// Cache en memoria para pantalla de inicio
let homeCache = null;
let homeCacheTime = 0;

// 1. ENDPOINT: Inicio estilo Spotify (Tendencias, Latino, Electrónica)
app.get('/api/home', async (req, res) => {
  const now = Date.now();
  if (homeCache && (now - homeCacheTime < 10 * 60 * 1000)) {
    return res.json(homeCache);
  }

  try {
    const yt = await getInnertube();
    const [trendRes, latinRes, edmRes] = await Promise.all([
      yt.music.search('Top Global Hits 2026', { type: 'song' }).catch(() => null),
      yt.music.search('Éxitos Reggaeton Urbano', { type: 'song' }).catch(() => null),
      yt.music.search('Trending Electronic Dance Music', { type: 'song' }).catch(() => null),
    ]);

    const trending = (trendRes?.songs?.contents || []).slice(0, 12).map(formatSongItem);
    const latino = (latinRes?.songs?.contents || []).slice(0, 10).map(formatSongItem);
    const electronic = (edmRes?.songs?.contents || []).slice(0, 10).map(formatSongItem);

    const greeting = new Date().getHours() < 12 ? '¡Buenos días!' : (new Date().getHours() < 19 ? '¡Buenas tardes!' : '¡Buenas noches!');

    homeCache = { greeting, trending, latino, electronic };
    homeCacheTime = now;

    res.json(homeCache);
  } catch (e) {
    console.error('Error en /api/home:', e.message);
    res.json({
      greeting: '¡Buenas noches!',
      trending: [],
      latino: [],
      electronic: [],
    });
  }
});

// 2. ENDPOINT: Búsqueda de alta precisión
app.get('/api/search', async (req, res) => {
  const query = req.query.q;
  if (!query) return res.status(400).json({ error: 'Query vacía' });

  try {
    const yt = await getInnertube();
    const searchRes = await yt.music.search(query, { type: 'song' });
    const songs = (searchRes?.songs?.contents || []).map(formatSongItem);

    res.json({
      query,
      songs,
      items: songs,
      topResult: songs[0] || null,
      total: songs.length,
    });
  } catch (e) {
    console.error('Error en /api/search:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// 3. ENDPOINT: Streaming de Audio de Alta Fidelidad (Con soporte HTTP Range 206 para iOS)
app.get(['/api/stream/:id', '/api/stream/:videoId'], async (req, res) => {
  const songId = req.params.id || req.params.videoId;
  if (!songId) return res.status(400).json({ error: 'ID de canción requerido' });

  try {
    const yt = await getInnertube();
    const info = await yt.music.getInfo(songId);
    const format = info.chooseFormat({ type: 'audio', quality: 'best' });

    if (!format) {
      return res.status(404).json({ error: 'No se encontró stream de audio' });
    }

    const streamUrl = await format.decipher(yt.session.player);
    if (!streamUrl) {
      return res.status(404).json({ error: 'No se pudo descifrar URL' });
    }

    const totalLength = format.content_length ? parseInt(format.content_length, 10) : 0;
    const rangeHeader = req.headers.range;

    // Google Video CDN requiere rangos acotados (máximo 1 MB por petición)
    const CHUNK_SIZE = 1024 * 1024; // 1 MB chunk

    let start = 0;
    let end = (totalLength > 0) ? Math.min(start + CHUNK_SIZE - 1, totalLength - 1) : CHUNK_SIZE - 1;

    if (rangeHeader) {
      const parts = rangeHeader.replace(/bytes=/, '').split('-');
      start = parseInt(parts[0], 10);
      if (parts[1] && parseInt(parts[1], 10)) {
        end = Math.min(parseInt(parts[1], 10), start + CHUNK_SIZE - 1);
      } else if (totalLength > 0) {
        end = Math.min(start + CHUNK_SIZE - 1, totalLength - 1);
      } else {
        end = start + CHUNK_SIZE - 1;
      }
    }

    const fetchHeaders = {
      'Range': `bytes=${start}-${end}`,
    };

    const streamResponse = await fetch(streamUrl, { headers: fetchHeaders });

    if (!streamResponse.ok && streamResponse.status !== 206) {
      console.error(`Google Video CDN retornó estado ${streamResponse.status}`);
      return res.status(streamResponse.status).end();
    }

    const contentLength = end - start + 1;
    res.status(206);
    res.setHeader('Content-Type', format.mime_type || 'audio/mp4');
    res.setHeader('Content-Range', `bytes ${start}-${end}/${totalLength || '*'}`);
    res.setHeader('Content-Length', contentLength);
    res.setHeader('Accept-Ranges', 'bytes');
    res.setHeader('Cache-Control', 'public, max-age=3600');

    const reader = streamResponse.body.getReader();
    async function pump() {
      const { done, value } = await reader.read();
      if (done) return res.end();
      res.write(value);
      return pump();
    }
    await pump();
  } catch (e) {
    console.error(`[Stream Error] ${songId}:`, e.message);
    if (!res.headersSent) res.status(500).json({ error: e.message });
  }
});

// 4. ENDPOINT: Letras de canciones
app.get(['/api/lyrics/:id', '/api/lyrics/:videoId'], async (req, res) => {
  const songId = req.params.id || req.params.videoId;
  try {
    const yt = await getInnertube();
    const lyrics = await yt.music.getLyrics(songId);
    res.json({
      description: lyrics?.description?.text || lyrics?.text || 'No hay letras disponibles para esta canción.',
    });
  } catch (e) {
    res.json({ description: 'Letras no disponibles.' });
  }
});

// 5. ENDPOINTS: Biblioteca y Favoritas
app.get('/api/library', (req, res) => {
  library = loadLibrary();
  res.json(library);
});

app.post('/api/like', async (req, res) => {
  const song = req.body.song || req.body;
  if (!song || !song.id) return res.status(400).json({ error: 'Datos incompletos' });

  library = loadLibrary();
  const exists = library.liked.some(s => s.id === song.id);

  if (!exists) {
    const entry = {
      ...song,
      likedAt: new Date().toISOString(),
      driveSynced: false,
    };
    library.liked = [entry, ...library.liked];
    saveLibrary(library);

    // Respaldo en segundo plano en Google Drive
    uploadToGoogleDriveAsync(song.id, song.title, song.artist);
  }

  res.json({ status: 'ok', id: song.id, liked: true });
});

app.post('/api/unlike', (req, res) => {
  const id = req.body.id || req.body.song?.id;
  if (!id) return res.status(400).json({ error: 'ID requerido' });

  library = loadLibrary();
  library.liked = library.liked.filter(s => s.id !== id);
  saveLibrary(library);

  res.json({ status: 'ok', id });
});

app.delete('/api/like', (req, res) => {
  const id = req.body.id || req.body.song?.id;
  if (!id) return res.status(400).json({ error: 'ID requerido' });

  library = loadLibrary();
  library.liked = library.liked.filter(s => s.id !== id);
  saveLibrary(library);

  res.json({ status: 'ok', id });
});

// 6. ENDPOINT: Crear Playlists
app.post('/api/playlists', (req, res) => {
  const { name } = req.body;
  if (!name) return res.status(400).json({ error: 'Nombre requerido' });

  library = loadLibrary();
  const newPl = {
    id: 'pl_' + Date.now(),
    name: name.trim(),
    createdAt: new Date().toISOString(),
    songs: [],
  };
  library.playlists = [...(library.playlists || []), newPl];
  saveLibrary(library);

  res.json(newPl);
});

app.post('/api/playlists/:id/songs', (req, res) => {
  const plId = req.params.id;
  const song = req.body.song || req.body;
  if (!song || !song.id) return res.status(400).json({ error: 'Canción requerida' });

  library = loadLibrary();
  const pl = (library.playlists || []).find(p => p.id === plId);
  if (!pl) return res.status(404).json({ error: 'Playlist no encontrada' });

  if (!pl.songs.some(s => s.id === song.id)) {
    pl.songs.push(song);
    saveLibrary(library);
  }

  res.json({ status: 'ok', playlist: pl });
});

// Helper: Respaldo asíncrono a Google Drive vía Webhook
async function uploadToGoogleDriveAsync(songId, title, artist) {
  try {
    const yt = await getInnertube();
    const info = await yt.music.getInfo(songId);
    const format = info.chooseFormat({ type: 'audio', quality: 'best' });
    if (!format) return;

    const streamUrl = await format.decipher(yt.session.player);
    if (!streamUrl) return;

    const audioRes = await fetch(streamUrl);
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
      console.log(`☁️ Canción respaldada en Google Drive: ${filename}`);
      library = loadLibrary();
      const song = library.liked.find(s => s.id === songId);
      if (song) {
        song.driveSynced = true;
        saveLibrary(library);
      }
    }
  } catch (e) {
    console.error(`Error respaldando en Google Drive ${songId}:`, e.message);
  }
}

// SPA Fallback
app.get('*', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.listen(PORT, () => {
  console.log(`\n======================================================`);
  console.log(`⚡ KILLFLY Web Player activo en http://localhost:${PORT}`);
  console.log(`======================================================\n`);
});
