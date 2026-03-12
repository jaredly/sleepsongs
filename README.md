# Sleep Songs (Android)

A simple app that:
- lets you choose an audio file from device storage
- asks how many times to play it
- plays it exactly that many loops
- optionally fades volume out during the final loop

## Build

1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Run on a device/emulator (Android 7.0+).

## Notes

- Audio picking uses Android's system document picker (`OpenDocument`).
- The app requests persistable read permission for the chosen file when possible.
