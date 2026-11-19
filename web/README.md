# Metrolist Web

A web-based YouTube Music client that syncs with the Android app via Google Drive.

## Features

- 🎵 Stream audio from YouTube Music
- ❤️ Like/unlike songs (synced via Google Drive)
- 📋 Create and manage playlists
- 🔍 Search for music
- 📱 Responsive PWA design (Material 3)
- ☁️ Google Drive backup for liked songs
- 📊 Playback statistics

## Quick Start (Local Development)

```bash
cd web
npm install
npm run dev
```

The server will start at `http://localhost:3000`.

## Deployment to Render

1. Fork this repository
2. Connect your GitHub account to Render
3. Create a new Web Service
4. Set the build command to: `npm install`
5. Set the start command to: `node server.js`
6. Add environment variables:
   - `NODE_VERSION`: `18.18.0`
   - `SPOTIFY_CLIENT_ID`: (optional)
   - `SPOTIFY_CLIENT_SECRET`: (optional)
   - `DRIVE_WEBHOOK_URL`: (optional)

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `SPOTIFY_CLIENT_ID` | No | Spotify Developer App ID |
| `SPOTIFY_CLIENT_SECRET` | No | Spotify Developer App Secret |
| `DRIVE_WEBHOOK_URL` | No | Google Apps Script webhook for sync |
| `FRONTEND_URL` | No | CORS origin (default: `*`) |

## Spotify Integration

To enable full Spotify integration (search, library sync, playlists):

1. Go to https://developer.spotify.com/dashboard
2. Create a new app
3. Add `http://localhost:3000` to Redirect URIs
4. Copy Client ID and Client Secret to environment variables

## Google Drive Sync

The app syncs liked songs to Google Drive via a Google Apps Script webhook.

1. Create a new Google Apps Script project
2. Deploy as web app with "Anyone, even anonymous" access
3. Use the deployment URL as `DRIVE_WEBHOOK_URL`

## API Endpoints

### Music

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/search?q=...` | Search for tracks |
| GET | `/api/stream/:videoId` | Stream audio |
| GET | `/api/lyrics/:videoId` | Get lyrics |

### Library

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/library` | Get user's library |
| POST | `/api/like` | Like a song |
| DELETE | `/api/like` | Unlike a song |
| POST | `/api/playlists` | Create playlist |
| POST | `/api/playlists/:id/songs` | Add song to playlist |

### Spotify (requires API credentials)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/spotify/search?q=...` | Search Spotify |
| GET | `/api/me/tracks` | Get saved tracks |
| POST | `/api/me/tracks` | Save track |

### Auth

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/auth/google` | Google OAuth info |
| POST | `/api/auth/verify` | Verify ID token |

## Data Storage

By default, data is stored in `data/library.json` (local JSON file).

For production, you can:
1. Set up PostgreSQL and update the server code
2. Use a cloud database service

## PWA Support

The app is a Progressive Web App:
- Installable on mobile and desktop
- Works offline for cached content
- MediaSession API support for lock screen controls

## License

GPL-3.0 - See LICENSE file for details.