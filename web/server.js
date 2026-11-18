import express from 'express';
import cors from 'cors';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';
import { Innertube, UniversalCache } from 'youtubei.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = process.env.PORT || 3000;

// Webhook por defecto de Google Drive (el configurado por el usuario)
const DEFAULT_DRIVE_WEBHOOK = process.env.DRIVE_WEBHOOK_URL ||
  'https://script.google.com/macros/s/AKfycbzHmTqyBNsSoTXqfm3dDpowNzsS3a0mhf02fcDFoUh6GN8839_W-xo9CfIeOhyc3tXf/exec';

app.use(cors());
app.use(express.json({ limit: '50mb' }));
app.use(express.static(path.join(__dirname, 'public')));

// Persistencia local ligera para canciones favoritas
const DATA_DIR = path.join(__dirname, 'data');
const LIKED_FILE = path.join(DATA_DIR, 'liked.json');
if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });

function getLikedSongs() {
  try {
    if (fs.existsSync(LIKED_FILE)) {
      return JSON.parse(fs.readFileSync(LIKED_FILE, 'utf-8'));
    }
  } catch (e) {
    console.error('Error leyendo liked.json:', e);
  }
  return [];
}

function saveLikedSongs(songs) {
  try {
    fs.writeFileSync(LIKED_FILE, JSON.stringify(songs, null, 2), 'utf-8');
  } catch (e) {
    console.error('Error guardando liked.json:', e);
  }
}

// Inicialización de Innertube (YouTube Music engine)
let yt = null;
async function getInnertube() {
  if (!yt) {
    yt = await Innertube.create({
      cache: new UniversalCache(false),
      generate_session_locally: true,
    });
    console.log('✅ Motor InnerTube (YouTube Music) iniciado correctamente');
  }
  return yt;
}

// 1. Endpoint: Búsqueda en YouTube Music
app.get('/api/search', async (req, res) => {
  const query = req.query.q;
  if (!query) return res.status(400).json({ error: 'Query vacía' });

  try {
    const client = await getInnertube();
    const searchResults = await client.music.search(query, { type: 'song' });

    const results = (searchResults.songs?.contents || []).map(item => ({
      id: item.id,
      title: item.title,
      artist: item.artists?.[0]?.name || 'Artista desconocido',
      album: item.album?.name || '',
      duration: item.duration?.text || '',
      durationSec: item.duration?.seconds || 0,
      thumbnail: item.thumbnails?.[0]?.url || `https://i.ytimg.com/vi/${item.id}/hqdefault.jpg`,
    }));

    res.json({ results });
  } catch (e) {
    console.error('Error en /api/search:', e);
    res.status(500).json({ error: e.message });
  }
});

// 2. Endpoint: Streaming de audio optimizado para iOS Safari (con soporte HTTP Range 206)
app.get('/api/stream/:id', async (req, res) => {
  const songId = req.params.id;
  if (!songId) return res.status(400).json({ error: 'ID de canción requerido' });

  try {
    const client = await getInnertube();
    const info = await client.music.getInfo(songId);
    const format = info.chooseFormat({ type: 'audio', quality: 'best' });

    if (!format || !format.decipher(client.session.player)) {
      return res.status(404).json({ error: 'No se encontró stream de audio' });
    }

    const streamUrl = format.decipher(client.session.player);

    // Redirigir directamente al stream de audio firmado de Google/YouTube o proxy con Range
    const rangeHeader = req.headers.range;
    const fetchHeaders = {
      'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15',
    };
    if (rangeHeader) fetchHeaders['Range'] = rangeHeader;

    const streamResponse = await fetch(streamUrl, { headers: fetchHeaders });

    res.status(streamResponse.status);
    streamResponse.headers.forEach((val, key) => {
      if (['content-range', 'content-length', 'content-type', 'accept-ranges'].includes(key.toLowerCase())) {
        res.setHeader(key, val);
      }
    });

    if (!res.getHeader('Content-Type')) {
      res.setHeader('Content-Type', format.mime_type || 'audio/mp4');
    }
    res.setHeader('Accept-Ranges', 'bytes');

    const reader = streamResponse.body.getReader();
    async function pump() {
      const { done, value } = await reader.read();
      if (done) return res.end();
      res.write(value);
      return pump();
    }
    await pump();
  } catch (e) {
    console.error(`Error en /api/stream/${songId}:`, e);
    if (!res.headersSent) res.status(500).json({ error: e.message });
  }
});

// 3. Endpoint: Obtener canciones favoritas
app.get('/api/liked', (req, res) => {
  res.json({ liked: getLikedSongs() });
});

// 4. Endpoint: Dar "Me gusta" (Guarda en web y sube a Google Drive automáticamente)
app.post('/api/like', async (req, res) => {
  const song = req.body;
  if (!song || !song.id) return res.status(400).json({ error: 'Datos de canción incompletos' });

  const current = getLikedSongs();
  const exists = current.some(s => s.id === song.id);

  if (!exists) {
    const updated = [
      {
        ...song,
        likedAt: new Date().toISOString(),
        driveSynced: false,
      },
      ...current,
    ];
    saveLikedSongs(updated);

    // En segundo plano en Render: Subir audio a Google Drive vía Webhook
    uploadToGoogleDriveAsync(song.id, song.title, song.artist, song.album, song.thumbnail);
  }

  res.json({ status: 'ok', id: song.id });
});

// 5. Endpoint: Quitar "Me gusta"
app.post('/api/unlike', (req, res) => {
  const { id } = req.body;
  if (!id) return res.status(400).json({ error: 'ID requerido' });

  const current = getLikedSongs();
  const filtered = current.filter(s => s.id !== id);
  saveLikedSongs(filtered);

  res.json({ status: 'ok', id });
});

// 6. Endpoint de Sincronización bidireccional con Metrolist Android
app.post('/api/sync/android', (req, res) => {
  const { songs } = req.body;
  if (!Array.isArray(songs)) return res.status(400).json({ error: 'Lista de canciones inválida' });

  const current = getLikedSongs();
  const currentMap = new Map(current.map(s => [s.id, s]));

  for (const s of songs) {
    if (!currentMap.has(s.id)) {
      currentMap.set(s.id, {
        id: s.id,
        title: s.title || s.name,
        artist: s.artist || '',
        album: s.album || '',
        thumbnail: s.thumbnail || `https://i.ytimg.com/vi/${s.id}/hqdefault.jpg`,
        likedAt: s.likedAt || new Date().toISOString(),
        driveSynced: true,
      });
    }
  }

  const merged = Array.from(currentMap.values());
  saveLikedSongs(merged);

  res.json({ status: 'ok', total: merged.length });
});

// Helper: Subida ultra rápida desde Render a Google Drive Webhook
async function uploadToGoogleDriveAsync(songId, title, artist, album, thumbnail) {
  try {
    const client = await getInnertube();
    const info = await client.music.getInfo(songId);
    const format = info.chooseFormat({ type: 'audio', quality: 'best' });
    if (!format) return;

    const streamUrl = format.decipher(client.session.player);
    const audioRes = await fetch(streamUrl);
    const audioBuffer = await audioRes.arrayBuffer();
    const base64Audio = Buffer.from(audioBuffer).toString('base64');

    const cleanTitle = (title || songId).replace(/[/\\?%*:|"<>]/g, '_');
    const cleanArtist = (artist || '').replace(/[/\\?%*:|"<>]/g, '_');
    const filename = cleanArtist ? `${cleanArtist} - ${cleanTitle}.m4a` : `${cleanTitle}.m4a`;

    const payload = {
      filename: filename,
      base64: base64Audio,
    };

    const driveRes = await fetch(DEFAULT_DRIVE_WEBHOOK, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });

    if (driveRes.ok) {
      console.log(`☁️ Subido a Google Drive con éxito: ${filename}`);
      const current = getLikedSongs();
      const item = current.find(s => s.id === songId);
      if (item) {
        item.driveSynced = true;
        saveLikedSongs(current);
      }
    }
  } catch (e) {
    console.error(`Error subiendo a Google Drive ${songId}:`, e.message);
  }
}

// Iniciar servidor
app.listen(PORT, () => {
  console.log(`🎵 Servidor Metrolist Web listo en http://localhost:${PORT}`);
  console.log(`🚀 Despliegue en Render activo en puerto ${PORT}`);
});
