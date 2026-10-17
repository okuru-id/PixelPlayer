# Design: External Download (Extension-Based)

Date: 2026-10-17
Status: Approved (design), pending implementation plan

## Goal

Add in-app music downloads from external streaming providers, mirroring
SpotiFLAC-Mobile's extension system. Users search a track, pick a quality,
and the file (FLAC/MP3 with embedded metadata + cover art) is saved into a
user-chosen SAF folder and shows up in PixelPlayer's local library.

Reference project: `/home/kurob/code/music/SpotiFLAC-Mobile` (MIT).
Reuse its Go backend (`go_backend/`, 183 files) as a prebuilt AAR instead of
reimplementing provider resolution, download pipeline, and tagging.

## Non-Goals

- No new Go code; the AAR is consumed as-is from SpotiFLAC-Mobile.
- No streaming from external providers — download only.
- No extension marketplace beyond a repository URL / local file install.
- No download from PixelPlayer's own sources (S3, Telegram, etc.) — that is a
  separate, later feature.

## Architecture

```
app/libs/gobackend.aar                  gomobile bind of SpotiFLAC go_backend (arm64)
data/download/
  DownloadBackend.kt                    thin JSON wrapper over gobackend API
  DownloadRepository.kt                 orchestration: search → download → postprocess → SAF publish
  DownloadWorker.kt                     WorkManager long-running worker + foreground notification
presentation/download/
  DownloadScreen.kt                     search bar, results, active queue
  ExtensionsScreen.kt                   install/manage .spotiflac-ext packages
```

Wiring: Hilt modules provide `DownloadBackend` (singleton) and
`DownloadRepository`. UI reachable from Home (new entry, same pattern as the
S3/Google Drive provider entries in `StreamingProviderSheet`).

## gobackend API Surface Used

All calls are JSON-string based (gomobile bindings):

| Function | Purpose |
|---|---|
| `InitExtensionSystem(extensionsDir, dataDir)` | once per app start |
| `LoadExtensionsFromDir(dir)` | load installed extensions |
| `LoadExtensionFromPath(path)` | install/upgrade a `.spotiflac-ext` (ZIP + manifest) |
| `GetInstalledExtensions()` | list for Extensions screen |
| `RemoveExtensionByID(id)` / `SetExtensionEnabledByID(id, enabled)` | management |
| `SearchTracksWithMetadataProvidersJSON(query, limit, includeExtensions)` | cross-provider search |
| `DownloadByStrategy(requestJSON)` | download, writes to caller-provided `OutputFD` |
| `GetAllDownloadProgressDelta(seq)` / `WaitForAllDownloadProgressDelta(seq, timeoutMs)` | progress polling |
| `CancelDownload(itemID)` | cancel |
| `runPostProcessingV2JSON(inputObj, metadata)` | embed tags + cover into output file |
| `GetExtensionPendingAuthJSON(extId)` / `SetExtensionAuthCodeByID(extId, code)` | extension auth flows |

`.spotiflac-ext` = ZIP with a JSON manifest; gobackend handles validation,
staging, version compare (refuses downgrade), and per-extension data dirs.

## Data Flow

1. App start (or first Download screen open): `InitExtensionSystem` with
   `filesDir/extensions` and `filesDir/download_data`; then
   `LoadExtensionsFromDir`.
2. Download screen: `SearchTracksWithMetadataProvidersJSON(query, limit, true)`
   → list of tracks with provider, quality options.
3. User taps download, optionally picks quality (default FLAC):
   enqueue `DownloadWorker` (unique work, `ExistingWorkPolicy.APPEND`).
4. Worker: create staging file in `cacheDir/downloads/<itemID>.part`, open
   `ParcelFileDescriptor`, call `DownloadByStrategy` with `OutputFD`.
5. Poll progress deltas (~500 ms) → update notification and UI state
   (`DownloadRepository` exposes `StateFlow<List<DownloadJobState>>`).
6. On success: `runPostProcessingV2JSON` writes title/artist/album/cover tags.
7. SAF publish: resolve user's chosen tree URI via `DocumentFile`, copy
   staging → final name (`Artist - Title.flac`), delete staging. Name
   collisions get ` (1)` suffixes (DocumentFile `createFile` semantics).
8. Trigger local library scan so the new file appears under "Local".

## UI

- **DownloadScreen**: search field, results list (art, title, artist,
  provider chip, quality), active queue section with progress bars and
  cancel buttons.
- **ExtensionsScreen**: list installed extensions (name, version, enabled
  toggle), install via system file picker (`.spotiflac-ext`) or repository
  URL, remove, update. Includes third-party-code disclaimer.
- **Settings**: download folder (SAF picker, persisted tree URI), default
  quality.
- Download notification: progress bar, cancel action; foreground service via
  WorkManager `setForeground`.

## Error Handling

- Per-track failure → error state in queue + notification, manual retry.
- `verification_required` response → dialog prompting for the extension's
  auth code (`GetExtensionPendingAuthJSON` → `SetExtensionAuthCodeByID`).
- Process death → WorkManager retries the worker.
- No extensions installed → empty state with "Install extension" button.
- SAF folder revoked → clear stored tree URI, prompt to re-pick on next
  download.

## Security Notes

- Extensions are third-party JS run inside gobackend's Goja VM — sandboxing
  is gobackend's responsibility (SpotiFLAC ships extensive security tests);
  PixelPlayer adds a visible disclaimer on the Extensions screen.
- Downloaded files land only in the user-chosen SAF folder; no path
  traversal from provider-supplied filenames (sanitized by DocumentFile
  display-name rules).

## Testing

- Unit (plain JVM): `DownloadBackend` JSON request/response parsing, queue
  state reduction in `DownloadRepository` (fake backend).
- Manual on device: install a community extension → search → download FLAC →
  verify file exists in chosen folder, tags/cover embedded, appears in local
  library.

## Effort

- AAR build + `DownloadBackend` + repository + worker: ~2 sessions
- UI (search, extensions, settings, notifications): ~2 sessions
