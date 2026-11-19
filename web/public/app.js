// KILLFLY Web Player Engine (Spotify Clone)

let state = {
  currentSong: null,
  queue: [],
  queueIndex: -1,
  isPlaying: false,
  isShuffle: false,
  isRepeat: false,
  likedSongs: [],
  playlists: [],
  currentPlaylistView: 'liked',
};

// DOM Elements
const audio = document.getElementById('coreAudio');
const btnPlayPause = document.getElementById('btnPlayPause');
const playPauseIcon = document.getElementById('playPauseIcon');
const btnPrev = document.getElementById('btnPrev');
const btnNext = document.getElementById('btnNext');
const btnShuffle = document.getElementById('btnShuffle');
const btnRepeat = document.getElementById('btnRepeat');
const btnLikeCurrent = document.getElementById('btnLikeCurrent');
const heartIconCurrent = document.getElementById('heartIconCurrent');
const mainSeekbar = document.getElementById('mainSeekbar');
const labelCurrentTime = document.getElementById('labelCurrentTime');
const labelTotalTime = document.getElementById('labelTotalTime');
const volumeSlider = document.getElementById('volumeSlider');
const nowPlayingThumb = document.getElementById('nowPlayingThumb');
const nowPlayingTitle = document.getElementById('nowPlayingTitle');
const nowPlayingArtist = document.getElementById('nowPlayingArtist');

// Navigation & Pages
const pageHome = document.getElementById('pageHome');
const pageSearch = document.getElementById('pageSearch');
const pagePlaylist = document.getElementById('pagePlaylist');
const pageLyrics = document.getElementById('pageLyrics');
const queueSidebar = document.getElementById('queueSidebar');

const navHome = document.getElementById('navHome');
const navSearch = document.getElementById('navSearch');
const navLibrary = document.getElementById('navLibrary');
const likedPlaylistTile = document.getElementById('likedPlaylistTile');
const globalSearchInput = document.getElementById('globalSearchInput');
const btnToggleLyrics = document.getElementById('btnToggleLyrics');
const btnToggleQueue = document.getElementById('btnToggleQueue');
const closeQueueBtn = document.getElementById('closeQueueBtn');

// Greetings
function updateGreeting() {
  const hour = new Date().getHours();
  const el = document.getElementById('greetingTitle');
  if (!el) return;
  if (hour >= 5 && hour < 12) el.innerText = '¡Buenos días!';
  else if (hour >= 12 && hour < 20) el.innerText = '¡Buenas tardes!';
  else el.innerText = '¡Buenas noches!';
}

// 1. Initial Load & Fetch Home Feed
async function initApp() {
  updateGreeting();
  await loadLibrary();
  await loadHomeFeed();
}

async function loadLibrary() {
  try {
    const res = await fetch('/api/library');
    const data = await res.json();
    state.likedSongs = data.liked || [];
    state.playlists = data.playlists || [];
    renderSidebarLibrary();
  } catch (e) {
    console.error('Error cargando biblioteca:', e);
  }
}

function renderSidebarLibrary() {
  const countEl = document.getElementById('likedCountSidebar');
  if (countEl) countEl.innerText = state.likedSongs.length;

  const container = document.getElementById('userPlaylistsList');
  if (!container) return;

  container.innerHTML = state.playlists.map(pl => `
    <div onclick="openPlaylistView('${pl.id}')" class="flex items-center gap-3 p-2 rounded-md hover:bg-[#1a1a1a] cursor-pointer transition group">
      <div class="w-12 h-12 rounded bg-[#282828] flex items-center justify-center flex-shrink-0 text-gray-400 group-hover:text-white">
        <svg class="w-6 h-6" fill="currentColor" viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 14H9V8h2v8zm4 0h-2V8h2v8z"/></svg>
      </div>
      <div class="overflow-hidden">
        <p class="font-semibold text-white truncate group-hover:text-[#1ed760]">${escapeHtml(pl.name)}</p>
        <p class="text-xs text-[#b3b3b3] truncate">Playlist • ${pl.songs?.length || 0} canciones</p>
      </div>
    </div>
  `).join('');
}

async function loadHomeFeed() {
  try {
    const res = await fetch('/api/home');
    const data = await res.json();

    renderCardRow('homeTrendingCards', data.trending || []);
    renderCardRow('homeLatinCards', data.latino || []);
    renderCardRow('homeElectronicCards', data.electronic || []);
  } catch (e) {
    console.error('Error cargando feed de inicio:', e);
  }
}

function renderCardRow(containerId, items) {
  const container = document.getElementById(containerId);
  if (!container) return;

  container.innerHTML = items.map(song => `
    <div onclick='playSingleTrack(${JSON.stringify(song)})' class="bg-[#181818] hover:bg-[#282828] p-3.5 rounded-lg cursor-pointer transition group relative flex flex-col shadow-md">
      <div class="relative w-full aspect-square mb-3.5 rounded overflow-hidden shadow-lg bg-[#282828]">
        <img src="${song.thumbnail}" class="w-full h-full object-cover">
        <button onclick='event.stopPropagation(); playSingleTrack(${JSON.stringify(song)})' class="w-11 h-11 rounded-full bg-[#1ed760] text-black flex items-center justify-center absolute right-2 bottom-2 translate-y-2 opacity-0 group-hover:translate-y-0 group-hover:opacity-100 transition shadow-xl play-btn-glow">
          <svg class="w-5 h-5 ml-0.5" fill="currentColor" viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
        </button>
      </div>
      <p class="font-bold text-white text-sm truncate mb-1">${escapeHtml(song.title)}</p>
      <p class="text-xs text-[#b3b3b3] truncate">${escapeHtml(song.artist)}</p>
    </div>
  `).join('');
}

// 2. Navigation Switcher
function switchView(viewName) {
  [pageHome, pageSearch, pagePlaylist, pageLyrics].forEach(p => p.classList.add('hidden'));

  navHome.classList.remove('text-white');
  navHome.classList.add('text-[#b3b3b3]');
  navSearch.classList.remove('text-white');
  navSearch.classList.add('text-[#b3b3b3]');

  if (viewName === 'home') {
    pageHome.classList.remove('hidden');
    navHome.classList.add('text-white');
    navHome.classList.remove('text-[#b3b3b3]');
  } else if (viewName === 'search') {
    pageSearch.classList.remove('hidden');
    navSearch.classList.add('text-white');
    navSearch.classList.remove('text-[#b3b3b3]');
    globalSearchInput.focus();
  } else if (viewName === 'playlist') {
    pagePlaylist.classList.remove('hidden');
  } else if (viewName === 'lyrics') {
    pageLyrics.classList.remove('hidden');
  }
}

navHome.addEventListener('click', () => switchView('home'));
navSearch.addEventListener('click', () => switchView('search'));
navLibrary.addEventListener('click', () => openPlaylistView('liked'));
likedPlaylistTile.addEventListener('click', () => openPlaylistView('liked'));

// 3. Search Engine
let searchDebounce = null;
globalSearchInput.addEventListener('input', (e) => {
  clearTimeout(searchDebounce);
  const q = e.target.value.trim();
  if (!q) {
    document.getElementById('searchResultsWrapper').classList.add('hidden');
    document.getElementById('searchBrowseSection').classList.remove('hidden');
    return;
  }
  searchDebounce = setTimeout(() => executeSearch(q), 400);
});

async function executeSearch(query) {
  switchView('search');
  document.getElementById('searchBrowseSection').classList.add('hidden');
  document.getElementById('searchResultsWrapper').classList.remove('hidden');

  try {
    const res = await fetch(`/api/search?q=${encodeURIComponent(query)}`);
    const data = await res.json();
    const songs = data.songs || [];
    const topResult = data.topResult || songs[0];

    // Render Top Result Card
    const topCard = document.getElementById('topResultCard');
    if (topResult) {
      topCard.innerHTML = `
        <div onclick='playSingleTrack(${JSON.stringify(topResult)})'>
          <img src="${topResult.thumbnail}" class="w-24 h-24 rounded-lg object-cover shadow-2xl mb-4">
          <h2 class="text-3xl font-black text-white truncate mb-1">${escapeHtml(topResult.title)}</h2>
          <p class="text-xs text-[#b3b3b3]">
            <span class="text-white font-bold">${escapeHtml(topResult.artist)}</span> • Canción
          </p>
        </div>
        <button onclick='event.stopPropagation(); playSingleTrack(${JSON.stringify(topResult)})' class="w-12 h-12 rounded-full bg-[#1ed760] text-black flex items-center justify-center absolute right-6 bottom-6 opacity-0 group-hover:opacity-100 transition shadow-2xl play-btn-glow">
          <svg class="w-6 h-6 ml-0.5" fill="currentColor" viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
        </button>
      `;
    }

    // Render Top 4 Tracks List
    const topList = document.getElementById('topSongsList');
    topList.innerHTML = songs.slice(0, 4).map(song => renderTrackRow(song, false)).join('');

    // Render More Tracks Grid
    renderCardRow('moreSearchResultsGrid', songs.slice(4));
  } catch (e) {
    console.error('Error buscando:', e);
  }
}

function quickSearchGenre(genre) {
  globalSearchInput.value = genre;
  executeSearch(genre);
}

// 4. Playlist View (Tus Me gusta / Custom)
function openPlaylistView(plId) {
  state.currentPlaylistView = plId;
  switchView('playlist');

  const titleEl = document.getElementById('playlistTitle');
  const statsEl = document.getElementById('playlistStats');
  const tbody = document.getElementById('playlistTracksTbody');
  const coverArt = document.getElementById('playlistCoverArt');

  let tracks = [];
  if (plId === 'liked') {
    titleEl.innerText = 'Tus Me gusta';
    tracks = state.likedSongs;
    coverArt.className = "w-48 h-48 lg:w-56 lg:h-56 rounded bg-gradient-to-br from-indigo-700 via-purple-600 to-pink-500 shadow-2xl flex items-center justify-center flex-shrink-0";
    coverArt.innerHTML = `<svg class="w-24 h-24 text-white" fill="currentColor" viewBox="0 0 24 24"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>`;
  } else {
    const pl = state.playlists.find(p => p.id === plId);
    if (!pl) return;
    titleEl.innerText = pl.name;
    tracks = pl.songs || [];
    coverArt.className = "w-48 h-48 lg:w-56 lg:h-56 rounded bg-[#282828] shadow-2xl flex items-center justify-center flex-shrink-0";
    coverArt.innerHTML = `<svg class="w-20 h-20 text-gray-500" fill="currentColor" viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 14H9V8h2v8zm4 0h-2V8h2v8z"/></svg>`;
  }

  statsEl.innerText = `${tracks.length} canciones`;

  if (tracks.length === 0) {
    tbody.innerHTML = `<tr><td colspan="5" class="py-12 text-center text-[#b3b3b3]">Esta lista está vacía. Busca canciones y dales "Me gusta" para verlas aquí.</td></tr>`;
    return;
  }

  tbody.innerHTML = tracks.map((song, idx) => renderTrackRow(song, true, idx + 1)).join('');
}

function renderTrackRow(song, isTable = false, trackNumber = 1) {
  const isLiked = state.likedSongs.some(s => s.id === song.id);
  const isCurrent = state.currentSong?.id === song.id;

  if (isTable) {
    return `
      <tr onclick='playSongInList(${JSON.stringify(song)})' class="hover:bg-[#282828]/60 group cursor-pointer transition rounded-md">
        <td class="py-2.5 text-center text-sm ${isCurrent ? 'text-[#1ed760] font-bold' : 'text-[#b3b3b3]'}">
          <span class="group-hover:hidden">${trackNumber}</span>
          <span class="hidden group-hover:inline text-white">▶</span>
        </td>
        <td class="py-2.5">
          <div class="flex items-center gap-3 overflow-hidden">
            <img src="${song.thumbnail}" class="w-10 h-10 rounded object-cover flex-shrink-0 shadow">
            <div class="overflow-hidden">
              <p class="font-bold text-sm truncate ${isCurrent ? 'text-[#1ed760]' : 'text-white'}">${escapeHtml(song.title)}</p>
              <p class="text-xs text-[#b3b3b3] truncate">${escapeHtml(song.artist)}</p>
            </div>
          </div>
        </td>
        <td class="py-2.5 text-xs text-[#b3b3b3] truncate hidden md:table-cell">${escapeHtml(song.album || 'Sencillo')}</td>
        <td class="py-2.5 text-xs text-[#b3b3b3] text-right pr-4">${song.duration || '3:30'}</td>
        <td class="py-2.5 text-center">
          <button onclick='event.stopPropagation(); toggleLikeSong(${JSON.stringify(song)})' class="text-[#b3b3b3] hover:text-white transition active:scale-90 p-1">
            <svg class="w-5 h-5 ${isLiked ? 'text-[#1ed760] fill-current' : 'fill-none stroke-current'}" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z"/></svg>
          </button>
        </td>
      </tr>
    `;
  } else {
    return `
      <div onclick='playSingleTrack(${JSON.stringify(song)})' class="flex items-center justify-between p-2 rounded-md hover:bg-[#282828] cursor-pointer group transition">
        <div class="flex items-center gap-3 overflow-hidden">
          <img src="${song.thumbnail}" class="w-10 h-10 rounded object-cover flex-shrink-0">
          <div class="overflow-hidden">
            <p class="font-bold text-sm truncate ${isCurrent ? 'text-[#1ed760]' : 'text-white'}">${escapeHtml(song.title)}</p>
            <p class="text-xs text-[#b3b3b3] truncate">${escapeHtml(song.artist)}</p>
          </div>
        </div>
        <div class="flex items-center gap-3">
          <button onclick='event.stopPropagation(); toggleLikeSong(${JSON.stringify(song)})' class="text-[#b3b3b3] hover:text-white transition active:scale-90 p-1">
            <svg class="w-4 h-4 ${isLiked ? 'text-[#1ed760] fill-current' : 'fill-none stroke-current'}" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z"/></svg>
          </button>
          <span class="text-xs text-[#b3b3b3]">${song.duration}</span>
        </div>
      </div>
    `;
  }
}

// 5. Playback Controller
function playSingleTrack(song) {
  state.queue = [song];
  state.queueIndex = 0;
  startTrack(song);
}

function playSongInList(song) {
  const currentList = state.currentPlaylistView === 'liked' ? state.likedSongs : (state.playlists.find(p => p.id === state.currentPlaylistView)?.songs || []);
  state.queue = [...currentList];
  const idx = state.queue.findIndex(s => s.id === song.id);
  state.queueIndex = idx >= 0 ? idx : 0;
  startTrack(song);
}

function playAllLiked() {
  if (state.likedSongs.length > 0) {
    state.queue = [...state.likedSongs];
    state.queueIndex = 0;
    startTrack(state.queue[0]);
  }
}

document.getElementById('playlistMainPlayBtn').addEventListener('click', playAllLiked);

function startTrack(song) {
  if (!song) return;
  state.currentSong = song;

  nowPlayingThumb.src = song.thumbnail || 'https://via.placeholder.com/56';
  nowPlayingTitle.innerText = song.title;
  nowPlayingArtist.innerText = song.artist || 'KILLFLY';

  updateHeartState();
  updateQueueDrawer();

  audio.src = `/api/stream/${song.id}`;
  audio.play().then(() => {
    state.isPlaying = true;
    updatePlayPauseButton();
  }).catch(e => console.log('Autoplay:', e));

  // Native iOS MediaSession API for lockscreen background play
  if ('mediaSession' in navigator) {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: song.title,
      artist: song.artist || 'Desconocido',
      album: song.album || 'KILLFLY Music',
      artwork: [
        { src: song.thumbnail, sizes: '512x512', type: 'image/jpeg' },
      ],
    });

    navigator.mediaSession.setActionHandler('play', () => { audio.play(); state.isPlaying = true; updatePlayPauseButton(); });
    navigator.mediaSession.setActionHandler('pause', () => { audio.pause(); state.isPlaying = false; updatePlayPauseButton(); });
    navigator.mediaSession.setActionHandler('previoustrack', () => playPrevTrack());
    navigator.mediaSession.setActionHandler('nexttrack', () => playNextTrack());
    navigator.mediaSession.setActionHandler('seekto', (details) => {
      if (details.seekTime && audio.duration) audio.currentTime = details.seekTime;
    });
  }

  // Refresh active rows
  if (!pagePlaylist.classList.contains('hidden')) {
    openPlaylistView(state.currentPlaylistView);
  }
}

function updatePlayPauseButton() {
  if (state.isPlaying) {
    playPauseIcon.innerHTML = `<path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/>`;
  } else {
    playPauseIcon.innerHTML = `<path d="M8 5v14l11-7z"/>`;
  }
}

btnPlayPause.addEventListener('click', () => {
  if (!audio.src) {
    if (state.likedSongs.length > 0) playAllLiked();
    return;
  }
  if (audio.paused) {
    audio.play();
    state.isPlaying = true;
  } else {
    audio.pause();
    state.isPlaying = false;
  }
  updatePlayPauseButton();
});

function playNextTrack() {
  if (state.queue.length === 0) return;
  state.queueIndex = (state.queueIndex + 1) % state.queue.length;
  startTrack(state.queue[state.queueIndex]);
}

function playPrevTrack() {
  if (state.queue.length === 0) return;
  state.queueIndex = (state.queueIndex - 1 + state.queue.length) % state.queue.length;
  startTrack(state.queue[state.queueIndex]);
}

btnNext.addEventListener('click', playNextTrack);
btnPrev.addEventListener('click', playPrevTrack);
audio.addEventListener('ended', playNextTrack);

// Time Updates
audio.addEventListener('timeupdate', () => {
  if (!isNaN(audio.duration) && audio.duration > 0) {
    const pct = (audio.currentTime / audio.duration) * 100;
    mainSeekbar.value = pct;
    labelCurrentTime.innerText = formatTime(audio.currentTime);
    labelTotalTime.innerText = formatTime(audio.duration);
  }
});

mainSeekbar.addEventListener('input', (e) => {
  if (audio.duration) {
    audio.currentTime = (e.target.value / 100) * audio.duration;
  }
});

function formatTime(sec) {
  if (isNaN(sec)) return '0:00';
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${m}:${s < 10 ? '0' : ''}${s}`;
}

// Volume & Mute
let previousVolume = 0.8;
const btnMute = document.getElementById('btnMute');
volumeSlider.addEventListener('input', (e) => {
  audio.volume = e.target.value / 100;
  if (audio.volume > 0) previousVolume = audio.volume;
});
if (btnMute) {
  btnMute.addEventListener('click', () => {
    if (audio.volume > 0) {
      previousVolume = audio.volume;
      audio.volume = 0;
      volumeSlider.value = 0;
    } else {
      audio.volume = previousVolume || 0.8;
      volumeSlider.value = (previousVolume || 0.8) * 100;
    }
  });
}

// Like / Heart Controller
function updateHeartState() {
  if (!state.currentSong) return;
  const isLiked = state.likedSongs.some(s => s.id === state.currentSong.id);
  if (isLiked) {
    heartIconCurrent.classList.add('text-[#1ed760]', 'fill-current');
    heartIconCurrent.classList.remove('text-[#b3b3b3]', 'fill-none');
  } else {
    heartIconCurrent.classList.remove('text-[#1ed760]', 'fill-current');
    heartIconCurrent.classList.add('text-[#b3b3b3]', 'fill-none');
  }
}

btnLikeCurrent.addEventListener('click', () => {
  if (state.currentSong) toggleLikeSong(state.currentSong);
});

async function toggleLikeSong(song) {
  const isLiked = state.likedSongs.some(s => s.id === song.id);
  if (isLiked) {
    await fetch('/api/unlike', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: song.id }),
    });
    state.likedSongs = state.likedSongs.filter(s => s.id !== song.id);
  } else {
    await fetch('/api/like', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(song),
    });
    state.likedSongs = [song, ...state.likedSongs];
  }
  updateHeartState();
  renderSidebarLibrary();
  if (!pagePlaylist.classList.contains('hidden') && state.currentPlaylistView === 'liked') {
    openPlaylistView('liked');
  }
}

// 6. Lyrics View
btnToggleLyrics.addEventListener('click', async () => {
  if (!state.currentSong) return;
  if (!pageLyrics.classList.contains('hidden')) {
    switchView('home');
    return;
  }

  switchView('lyrics');
  document.getElementById('lyricsTrackTitle').innerText = state.currentSong.title;
  document.getElementById('lyricsTrackArtist').innerText = state.currentSong.artist;
  const content = document.getElementById('lyricsContent');
  content.innerText = 'Cargando letras desde YouTube Music...';

  try {
    const res = await fetch(`/api/lyrics/${state.currentSong.id}`);
    const data = await res.json();
    content.innerText = data.description || 'No se encontraron letras disponibles.';
  } catch (e) {
    content.innerText = 'Letras no disponibles.';
  }
});

// 7. Queue Sidebar Drawer
btnToggleQueue.addEventListener('click', () => {
  queueSidebar.classList.toggle('hidden');
});
closeQueueBtn.addEventListener('click', () => {
  queueSidebar.classList.add('hidden');
});

function updateQueueDrawer() {
  if (!state.currentSong) return;
  document.getElementById('queueCurrentThumb').src = state.currentSong.thumbnail;
  document.getElementById('queueCurrentTitle').innerText = state.currentSong.title;
  document.getElementById('queueCurrentArtist').innerText = state.currentSong.artist;

  const upNextContainer = document.getElementById('queueUpNextList');
  if (!upNextContainer) return;

  const upcoming = state.queue.slice(state.queueIndex + 1);
  if (upcoming.length === 0) {
    upNextContainer.innerHTML = `<p class="text-xs text-[#b3b3b3] py-4 text-center">No hay más canciones en la cola</p>`;
    return;
  }

  upNextContainer.innerHTML = upcoming.map((song, i) => `
    <div onclick='startTrack(${JSON.stringify(song)})' class="flex items-center gap-3 p-1.5 rounded hover:bg-[#282828] cursor-pointer transition">
      <img src="${song.thumbnail}" class="w-9 h-9 rounded object-cover flex-shrink-0">
      <div class="overflow-hidden">
        <p class="font-bold text-white text-xs truncate">${escapeHtml(song.title)}</p>
        <p class="text-[11px] text-[#b3b3b3] truncate">${escapeHtml(song.artist)}</p>
      </div>
    </div>
  `).join('');
}

// 8. Create Playlist Prompt
document.getElementById('createPlaylistBtn').addEventListener('click', async () => {
  const name = prompt('Nombre de la nueva playlist:');
  if (!name || !name.trim()) return;

  try {
    const res = await fetch('/api/playlists', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: name.trim() }),
    });
    const newPl = await res.json();
    state.playlists.push(newPl);
    renderSidebarLibrary();
    openPlaylistView(newPl.id);
  } catch (e) {
    alert('Error creando playlist');
  }
});

function escapeHtml(str) {
  return (str || '').replace(/[&<>'"]/g, tag => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
  }[tag] || tag));
}

// Launch
initApp();
