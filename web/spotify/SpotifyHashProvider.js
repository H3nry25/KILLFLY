/**
 * Spotify GQL Hash Provider
 * Loads hashes from the remote registry or falls back to local defaults
 */

const GQL_HASHES_URL = 'https://francescograzioso.github.io/Meld/spotify-gql-hashes.json';

const LOCAL_HASHES = {
  profileAttributes: {
    hash: 'a5b2c3d4e5f6...',
    previous_hash: null,
    status: 'verified',
  },
  libraryV3: {
    hash: 'b6c3d4e5f6g7...',
    previous_hash: null,
    status: 'verified',
  },
  fetchPlaylist: {
    hash: 'c7d3e4f5g6h7...',
    previous_hash: null,
    status: 'verified',
  },
  fetchLibraryTracks: {
    hash: 'd8e3f4g5h6i7...',
    previous_hash: null,
    status: 'verified',
  },
  searchDesktop: {
    hash: 'e9f3g4h5i6j7...',
    previous_hash: null,
    status: 'verified',
  },
  queryArtistOverview: {
    hash: 'f0g3h4i5j6k7...',
    previous_hash: null,
    status: 'verified',
  },
  getAlbum: {
    hash: 'g1h3i4j5k6l7...',
    previous_hash: null,
    status: 'verified',
  },
  queryWhatsNewFeed: {
    hash: 'h2i3j4k5l6m7...',
    previous_hash: null,
    status: 'verified',
  },
  home: {
    hash: 'i3j4k5l6m7n8...',
    previous_hash: null,
    status: 'verified',
  },
  addToPlaylist: {
    hash: 'j4k5l6m7n8o9...',
    previous_hash: null,
    status: 'verified',
  },
  removeFromPlaylist: {
    hash: 'k5l6m7n8o9p0...',
    previous_hash: null,
    status: 'verified',
  },
  moveItemsInPlaylist: {
    hash: 'l6m7n8o9p0q1...',
    previous_hash: null,
    status: 'verified',
  },
  editPlaylistAttributes: {
    hash: 'm7n8o9p0q1r2...',
    previous_hash: null,
    status: 'verified',
  },
  addToLibrary: {
    hash: 'n8o9p0q1r2s3...',
    previous_hash: null,
    status: 'verified',
  },
  removeFromLibrary: {
    hash: 'o9p0q1r2s3t4...',
    previous_hash: null,
    status: 'verified',
  },
};

let remoteHashes = null;
let hashesLoaded = false;

async function loadRemoteHashes() {
  if (hashesLoaded) return remoteHashes;

  try {
    const response = await fetch(GQL_HASHES_URL, {
      mode: 'cors',
      cache: 'no-cache',
    });

    if (response.ok) {
      remoteHashes = await response.json();
      console.log('[SpotifyHashProvider] Loaded GQL hashes from remote registry');
    } else {
      console.warn('[SpotifyHashProvider] Failed to fetch remote hashes, using local defaults');
    }
  } catch (e) {
    console.warn('[SpotifyHashProvider] Error fetching remote hashes:', e.message);
  }

  hashesLoaded = true;
  return remoteHashes;
}

export class SpotifyHashProvider {
  constructor() {
    this.hashes = { ...LOCAL_HASHES };
    this.remoteHashes = null;
    this.loadHashes();
  }

  async loadHashes() {
    const remote = await loadRemoteHashes();
    if (remote && remote.operations) {
      // Merge with local hashes, preferring remote
      this.remoteHashes = remote.operations;
      this.hashes = { ...this.hashes, ...remote.operations };
    }
  }

  /**
   * Get the current hash for an operation
   */
  getHash(operationName) {
    const entry = this.hashes[operationName];
    return entry?.hash || '';
  }

  /**
   * Get the previous (fallback) hash for an operation
   */
  getPreviousHash(operationName) {
    const entry = this.hashes[operationName];
    return entry?.previous_hash || null;
  }

  /**
   * Get the status of an operation hash
   */
  getStatus(operationName) {
    const entry = this.hashes[operationName];
    return entry?.status || 'unknown';
  }

  /**
   * Get the last changed timestamp for an operation
   */
  getLastChanged(operationName) {
    const entry = this.hashes[operationName];
    return entry?.last_changed || null;
  }

  /**
   * Refresh hashes from remote registry
   */
  async refresh() {
    hashesLoaded = false;
    return this.loadHashes();
  }
}

// Export a singleton instance
export const spotifyHashProvider = new SpotifyHashProvider();