# About SyncIt

Version: 1.0.1
Release: Bug Fixes

## Changes in 1.0.1

* Added Acoustic Sync Trim manual slider to Host screen when streaming local files.
* Removed Test Metronome Tone button from Join Screen to prevent calibration loops.
* Updated Join Screen title header to show detailed real-time connection status (Buffering, Authenticating, Connected, Weak Connection, Reconnecting, Disconnected).
* Fixed Audio Capture Service persistence so stopping capture properly releases the media projection resources and clears the active notification.
* Fixed live capture routing bug that blocked audio playback on peers after playing a local file.
