// Metrolist Web Player Client Logic (iOS & MediaSession Optimized)

let currentSong = null;
let queue = [];
let queueIndex = -1;
let likedSongs = [];

const audio = document.getElementById('audioEl');
const playPauseBtn = document.getElementById('playPauseBtn');
const prevBtn = document.getElementById('prevBtn');
const nextBtn = document.getElementById('nextBtn');
const likeCurrentBtn = document.getElementById('likeCurrentBtn');
const seekSlider = document.getElementById('seekSlider');
const currTime = document.getElementById('currTime');
const totalTime = document.getElementById('totalTime');
const playerThumb = document.getElementById('playerThumb');
const playerTitle = document.getElementById('playerTitle');
const playerArtist = document.getElementById('playerArtist');

const tabLiked = document.getElementById('tabLiked');
const tabSearch = document.getElementById('tabSearch');
const viewLiked = document.getElementById('viewLiked');
const viewSearch = document.getElementById('viewSearch');
const likedList = document.getElementById('likedList');
const searchInput = document.getElementById('searchInput');
const searchResults = document.getElementById('searchResults');
const likedCount = document.getElementById('likedCount');
const playAllLiked = document.getElementById('playAllLiked');
const syncBtn = document.getElementById('syncBtn');

// Tabs logic
tabLiked.addEventListener('click', () => {
  tabLiked.classList.add('border-purple-400', 'text-purple-400');
  tabLiked.classList.remove('border-transparent', 'text-gray-400');
  tabSearch.classList.remove('border-purple-400', 'text-purple-400');
  tabSearch.classList.add('border-transparent', 'text-gray-400');
  viewLiked.classList.remove('hidden');
  viewSearch.classList.add('hidden');
});

tabSearch.addEventListener('click', () => {
  tabSearch.classList.add('border-purple-400', 'text-purple-400');
  tabSearch.classList.remove('border-transparent', 'text-gray-400');
  tabLiked.classList.remove('border-purple-400', 'text-purple-400');
  tabLiked.classList.add('border-transparent', 'text-gray-400');
  viewSearch.classList.remove('hidden');
  viewLiked.classList.add('hidden');
});

// Load Liked Songs
async function fetchLikedSongs() {
  try {
    const res = await fetch('/api/liked');
    const data = await res.json();
    likedSongs = data.liked || [];
    renderLikedSongs();
  } catch (e) {
    console.error('Error cargando favoritas:', e);
  }
}

function renderLikedSongs() {
  likedCount.innerText = likedSongs.length;
  if (likedSongs.length === 0) {
    likedList.innerHTML = `<p class="text-sm text-gray-400 text-center py-10">No tienes canciones favoritas aún. Usa la pestaña Buscar para agregar canciones.</p>`;
    return;
  }

  likedList.innerHTML = likedSongs.map((song, idx) => `
    <div class="flex items-center justify-between p-2 rounded-xl bg-[#16181b] hover:bg-[#1f2227] transition cursor-pointer" onclick="playSongAtQueue(${idx}, true)">
      <div class="flex items-center space-x-3 overflow-hidden">
        <img src="${song.thumbnail || 'https://via.placeholder.com/44'}" class="w-11 h-11 rounded-lg object-cover flex-shrink-0">
        <div class="overflow-hidden">
          <p class="text-sm font-semibold truncate ${currentSong?.id === song.id ? 'text-purple-400' : 'text-white'}">${escapeHtml(song.title)}</p>
          <p class="text-xs text-gray-400 truncate">${escapeHtml(song.artist || 'Desconocido')}</p>
        </div>
      </div>
      <div class="flex items-center space-x-2">
        <span class="text-xs text-green-400 font-mono ${song.driveSynced ? '' : 'hidden'}" title="En Google Drive">☁️</span>
        <button onclick="event.stopPropagation(); toggleLikeSong('${song.id}')" class="text-lg p-1.5 active:scale-90 transition">
          ❤️
        </button>
      </div>
    </div>
  `).join('');
}

// Search Logic
let searchDebounce = null;
searchInput.addEventListener('input', (e) => {
  clearTimeout(searchDebounce);
  const q = e.target.value.trim();
  if (!q) {
    searchResults.innerHTML = `<p class="text-sm text-gray-400 text-center py-10">Escribe algo arriba para buscar en YouTube Music</p>`;
    return;
  }
  searchDebounce = setTimeout(() => performSearch(q), 500);
});

async function performSearch(query) {
  searchResults.innerHTML = `<p class="text-sm text-gray-400 text-center py-10">Buscando en YouTube Music...</p>`;
  try {
    const res = await fetch(`/api/search?q=${encodeURIComponent(query)}`);
    const data = await res.json();
    const results = data.results || [];

    if (results.length === 0) {
      searchResults.innerHTML = `<p class="text-sm text-gray-400 text-center py-10">No se encontraron resultados</p>`;
      return;
    }

    searchResults.innerHTML = results.map(song => {
      const isLiked = likedSongs.some(s => s.id === song.id);
      return `
        <div class="flex items-center justify-between p-2 rounded-xl bg-[#16181b] hover:bg-[#1f2227] transition cursor-pointer" onclick='playDirect(${JSON.stringify(song)})'>
          <div class="flex items-center space-x-3 overflow-hidden">
            <img src="${song.thumbnail}" class="w-11 h-11 rounded-lg object-cover flex-shrink-0">
            <div class="overflow-hidden">
              <p class="text-sm font-semibold truncate text-white">${escapeHtml(song.title)}</p>
              <p class="text-xs text-gray-400 truncate">${escapeHtml(song.artist)} • ${song.duration}</p>
            </div>
          </div>
          <button onclick='event.stopPropagation(); toggleLikeObject(${JSON.stringify(song)})' class="text-lg p-1.5 active:scale-90 transition">
            ${isLiked ? '❤️' : '🤍'}
          </button>
        </div>
      `;
    }).join('');
  } catch (e) {
    searchResults.innerHTML = `<p class="text-sm text-red-400 text-center py-10">Error en búsqueda: ${e.message}</p>`;
  }
}

// Audio Playback & MediaSession (Native iPhone lockscreen support)
function playDirect(song) {
  queue = [song];
  queueIndex = 0;
  startPlayback(song);
}

function playSongAtQueue(idx, isLikedQueue = false) {
  if (isLikedQueue) queue = [...likedSongs];
  queueIndex = idx;
  startPlayback(queue[queueIndex]);
}

playAllLiked.addEventListener('click', () => {
  if (likedSongs.length > 0) playSongAtQueue(0, true);
});

function startPlayback(song) {
  if (!song) return;
  currentSong = song;

  playerThumb.src = song.thumbnail || 'https://via.placeholder.com/48';
  playerTitle.innerText = song.title;
  playerArtist.innerText = song.artist || 'Metrolist Web';
  updateLikeButtonState();

  audio.src = `/api/stream/${song.id}`;
  audio.play().catch(e => console.log('Autoplay blocked:', e));
  playPauseBtn.innerText = '⏸';

  // Configurar MediaSession para iOS (Pantalla de bloqueo y segundo plano)
  if ('mediaSession' in navigator) {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: song.title,
      artist: song.artist || 'Desconocido',
      album: song.album || 'Metrolist',
      artwork: [
        { src: song.thumbnail, sizes: '512x512', type: 'image/jpeg' },
      ],
    });

    navigator.mediaSession.setActionHandler('play', () => { audio.play(); playPauseBtn.innerText = '⏸'; });
    navigator.mediaSession.setActionHandler('pause', () => { audio.pause(); playPauseBtn.innerText = '▶'; });
    navigator.mediaSession.setActionHandler('previoustrack', () => playPrev());
    navigator.mediaSession.setActionHandler('nexttrack', () => playNext());
    navigator.mediaSession.setActionHandler('seekto', (details) => {
      if (details.seekTime && audio.duration) audio.currentTime = details.seekTime;
    });
  }

  renderLikedSongs();
}

playPauseBtn.addEventListener('click', () => {
  if (!audio.src) return;
  if (audio.paused) {
    audio.play();
    playPauseBtn.innerText = '⏸';
  } else {
    audio.pause();
    playPauseBtn.innerText = '▶';
  }
});

function playNext() {
  if (queue.length === 0) return;
  queueIndex = (queueIndex + 1) % queue.length;
  startPlayback(queue[queueIndex]);
}

function playPrev() {
  if (queue.length === 0) return;
  queueIndex = (queueIndex - 1 + queue.length) % queue.length;
  startPlayback(queue[queueIndex]);
}

nextBtn.addEventListener('click', playNext);
prevBtn.addEventListener('click', playPrev);
audio.addEventListener('ended', playNext);

// Time Updates
audio.addEventListener('timeupdate', () => {
  if (!isNaN(audio.duration) && audio.duration > 0) {
    const pct = (audio.currentTime / audio.duration) * 100;
    seekSlider.value = pct;
    currTime.innerText = formatSeconds(audio.currentTime);
    totalTime.innerText = formatSeconds(audio.duration);
  }
});

seekSlider.addEventListener('input', (e) => {
  if (audio.duration) {
    audio.currentTime = (e.target.value / 100) * audio.duration;
  }
});

function formatSeconds(sec) {
  if (isNaN(sec)) return '0:00';
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${m}:${s < 10 ? '0' : ''}${s}`;
}

// Likes & Sync
function updateLikeButtonState() {
  if (!currentSong) return;
  const isLiked = likedSongs.some(s => s.id === currentSong.id);
  likeCurrentBtn.innerText = isLiked ? '❤️' : '🤍';
}

likeCurrentBtn.addEventListener('click', () => {
  if (!currentSong) return;
  toggleLikeObject(currentSong);
});

async function toggleLikeObject(song) {
  const isLiked = likedSongs.some(s => s.id === song.id);
  if (isLiked) {
    await fetch('/api/unlike', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: song.id }),
    });
    likedSongs = likedSongs.filter(s => s.id !== song.id);
  } else {
    await fetch('/api/like', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(song),
    });
    likedSongs = [{ ...song, driveSynced: false }, ...likedSongs];
  }
  updateLikeButtonState();
  renderLikedSongs();
}

async function toggleLikeSong(id) {
  const song = likedSongs.find(s => s.id === id);
  if (song) toggleLikeObject(song);
}

// Sync Button
syncBtn.addEventListener('click', async () => {
  syncBtn.classList.add('animate-spin');
  await fetchLikedSongs();
  setTimeout(() => syncBtn.classList.remove('animate-spin'), 600);
});

function escapeHtml(str) {
  return (str || '').replace(/[&<>'"]/g, tag => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
  }[tag] || tag));
}

// Init
fetchLikedSongs();
