/**
 * Spotify API Client
 * Uses the internal GraphQL API (api-partner.spotify.com) for most operations
 */

import { SpotifyHashProvider } from '../spotify/SpotifyHashProvider.js';

export class SpotifyClient {
  constructor(clientId, clientSecret) {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.baseUrl = 'https://api.spotify.com/v1';
    this.gqlUrl = 'https://api-partner.spotify.com/pathfinder/v2/query';
    this.hashProvider = new SpotifyHashProvider();
  }

  // ── Authentication ──────────────────────────────────────────────────────

  /**
   * Get an access token using client credentials (for public endpoints)
   */
  async getAccessToken(code = null, redirectUri = null) {
    if (!this.clientId || !this.clientSecret) {
      throw new Error('SPOTIFY_CLIENT_ID y SPOTIFY_CLIENT_SECRET son requeridos');
    }

    const response = await fetch('https://accounts.spotify.com/api/token', {
      method: 'POST',
      headers: {
        'Authorization': 'Basic ' + btoa(`${this.clientId}:${this.clientSecret}`),
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: new URLSearchParams({
        grant_type: 'client_credentials',
      }),
    });

    if (!response.ok) {
      throw new Error(`Error obteniendo token: ${response.status}`);
    }

    return response.json();
  }

  // ── GraphQL Operations ─────────────────────────────────────────────────

  async graphql(accessToken, operationName, variables = {}) {
    const hash = this.hashProvider.getHash(operationName);
    const previousHash = this.hashProvider.getPreviousHash(operationName);

    const body = {
      variables,
      operationName,
      extensions: {
        persistedQuery: {
          version: 1,
          sha256Hash: hash,
        },
      },
    };

    const response = await fetch(this.gqlUrl, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        'app-platform': 'WebPlayer',
        'Origin': 'https://open.spotify.com',
      },
      body: JSON.stringify(body),
    });

    const data = await response.json();

    if (response.status === 412) {
      // Hash expired, try previous hash
      if (previousHash && previousHash !== hash) {
        const retryBody = { ...body, extensions: { persistedQuery: { version: 1, sha256Hash: previousHash } } };
        const retryResponse = await fetch(this.gqlUrl, {
          method: 'POST',
          headers: response.headers,
          body: JSON.stringify(retryBody),
        });
        return retryResponse.json();
      }
      throw new Error('PersistedQueryNotFound - hash expiró');
    }

    if (response.status === 401) {
      throw new Error('Token expirado o inválido');
    }

    return data;
  }

  // ── REST API Operations ─────────────────────────────────────────────────

  async rest(accessToken, endpoint, method = 'GET', data = null) {
    const response = await fetch(`${this.baseUrl}${endpoint}`, {
      method,
      headers: {
        'Authorization': `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
      },
      body: data ? JSON.stringify(data) : null,
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.error?.message || `HTTP ${response.status}`);
    }

    return response.json();
  }

  // ── Search ───────────────────────────────────────────────────────────────

  async search(accessToken, query, types = ['track'], limit = 20) {
    const params = new URLSearchParams({
      q: query,
      type: types.join(','),
      limit: limit.toString(),
    });

    const response = await fetch(`${this.baseUrl}/search?${params}`, {
      headers: { 'Authorization': `Bearer ${accessToken}` },
    });

    if (!response.ok) throw new Error('Error en búsqueda');
    const data = await response.json();

    // Map to compatible format
    const items = [];
    if (data.tracks?.items) {
      items.push(...data.tracks.items.map(t => ({
        id: t.id,
        title: t.name,
        artist: t.artists?.map(a => a.name).join(', ') || 'Desconocido',
        album: t.album?.name || '',
        duration: t.duration_ms,
        thumbnail: t.album?.images?.[0]?.url || '',
        explicit: t.explicit,
        uri: t.uri,
        preview_url: t.preview_url,
      })));
    }
    if (data.artists?.items) {
      items.push(...data.artists.items.map(a => ({
        id: a.id,
        title: a.name,
        artist: a.name,
        album: '',
        duration: 0,
        thumbnail: a.images?.[0]?.url || '',
        type: 'artist',
        uri: a.uri,
      })));
    }

    return { items, total: items.length };
  }

  // ── User Library (Saved Tracks) ───────────────────────────────────────────

  async getSavedTracks(accessToken, limit = 50, offset = 0) {
    const response = await fetch(
      `${this.baseUrl}/me/tracks?limit=${limit}&offset=${offset}`,
      { headers: { 'Authorization': `Bearer ${accessToken}` } }
    );

    if (!response.ok) throw new Error('Error obteniendo tracks guardados');
    const data = await response.json();

    return data.items.map(item => ({
      id: item.track.id,
      title: item.track.name,
      artist: item.track.artists?.map(a => a.name).join(', ') || 'Desconocido',
      album: item.track.album?.name || '',
      duration: item.track.duration_ms,
      thumbnail: item.track.album?.images?.[0]?.url || '',
      explicit: item.track.explicit,
      added_at: item.added_at,
      uri: item.track.uri,
      preview_url: item.track.preview_url,
    }));
  }

  async saveTracks(accessToken, trackUris) {
    await this.rest(accessToken, '/me/tracks', 'PUT', { ids: trackUris.map(u => u.split(':')[2]) });
  }

  async removeTracks(accessToken, trackUris) {
    await this.rest(accessToken, '/me/tracks', 'DELETE', { ids: trackUris.map(u => u.split(':')[2]) });
  }

  // ── User Playlists ───────────────────────────────────────────────────────

  async getUserPlaylists(accessToken, limit = 50, offset = 0) {
    const response = await fetch(
      `${this.baseUrl}/me/playlists?limit=${limit}&offset=${offset}`,
      { headers: { 'Authorization': `Bearer ${accessToken}` } }
    );

    if (!response.ok) throw new Error('Error obteniendo playlists');
    const data = await response.json();

    return data.items.map(p => ({
      id: p.id,
      name: p.name,
      description: p.description,
      thumbnail: p.images?.[0]?.url || '',
      track_count: p.tracks.total,
      collaborative: p.collaborative,
      uri: p.uri,
      owner: p.owner?.display_name,
      public: p.public,
    }));
  }

  async createPlaylist(accessToken, name, description = '') {
    // First get user ID
    const user = await this.rest(accessToken, '/me');
    const playlist = await this.rest(accessToken, '/users/' + user.id + '/playlists', 'POST', {
      name,
      description,
      public: false,
    });

    return {
      id: playlist.id,
      name: playlist.name,
      description: playlist.description,
      thumbnail: playlist.images?.[0]?.url || '',
      track_count: 0,
      collaborative: false,
      uri: playlist.uri,
      owner: user.display_name,
      public: playlist.public,
      tracks: [],
    };
  }

  async addTracksToPlaylist(accessToken, playlistId, trackUris) {
    await this.rest(accessToken, `/playlists/${playlistId}/tracks`, 'POST', {
      uris: trackUris,
    });
  }

  // ── Track Audio Features ─────────────────────────────────────────────────

  async getTrackAudioFeatures(accessToken, trackId) {
    const response = await fetch(
      `${this.baseUrl}/audio-features/${trackId}`,
      { headers: { 'Authorization': `Bearer ${accessToken}` } }
    );

    if (response.status === 404) return null;
    if (!response.ok) throw new Error('Error obteniendo audio features');

    return response.json();
  }

  // ── Artist Top Tracks ────────────────────────────────────────────────────

  async getArtistTopTracks(accessToken, artistId, market = 'US') {
    const response = await fetch(
      `${this.baseUrl}/artists/${artistId}/top-tracks?market=${market}`,
      { headers: { 'Authorization': `Bearer ${accessToken}` } }
    );

    if (!response.ok) throw new Error('Error obteniendo top tracks');
    const data = await response.json();

    return data.tracks.map(t => ({
      id: t.id,
      title: t.name,
      artist: t.artists?.map(a => a.name).join(', ') || 'Desconocido',
      album: t.album?.name || '',
      duration: t.duration_ms,
      thumbnail: t.album?.images?.[0]?.url || '',
      explicit: t.explicit,
      preview_url: t.preview_url,
      uri: t.uri,
      popularity: t.popularity,
      disc_number: t.disc_number,
      track_number: t.track_number,
    }));
  }

  // ── Album Tracks ─────────────────────────────────────────────────────────

  async getAlbumTracks(accessToken, albumId) {
    const response = await fetch(
      `${this.baseUrl}/albums/${albumId}`,
      { headers: { 'Authorization': `Bearer ${accessToken}` } }
    );

    if (!response.ok) throw new Error('Error obteniendo álbum');
    const data = await response.json();

    return {
      id: data.id,
      name: data.name,
      artist: data.artists?.map(a => a.name).join(', ') || 'Desconocido',
      thumbnail: data.images?.[0]?.url || '',
      tracks: data.tracks?.items?.map(t => ({
        id: t.id,
        title: t.name,
        artist: t.artists?.map(a => a.name).join(', ') || 'Desconocido',
        album: data.name,
        duration: t.duration_ms,
        explicit: t.explicit,
        preview_url: t.preview_url,
        uri: t.uri,
        disc_number: t.disc_number,
        track_number: t.track_number,
      })) || [],
    };
  }
}