# Local Android smoke tests

The fixtures are synthetic and are never part of production app configuration. They preserve the API shapes needed for deterministic tests without real account credentials.

1. Generate a small MP4 with ffmpeg:

```sh
ffmpeg -f lavfi -i testsrc=size=320x180:rate=24 \
  -f lavfi -i sine=frequency=440:sample_rate=44100 -t 4 \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart \
  /tmp/lumenkot-smoke.mp4
```

2. Start `python3 tools/smoke_server.py` on the host. It binds to localhost:8765. Forward it to the emulator with `adb reverse tcp:8765 tcp:8765`; the tests use `127.0.0.1:8765`. This also works with ATD images whose external networking is disabled.
3. Boot an Android API 24+ emulator, then run `tools/run-smoke.sh` (or `scripts/build.sh connectedDebugAndroidTest` after `adb reverse`). The tests clear their application preferences before and after each test. Use a dedicated test emulator; this is not a command for a personal device with user data.

The tests exercise catalog/film/stream parsing over real HTTP, search/comments, cookie persistence and domain separation, local history, Activity launch and actual Media3 playback. Assertions distinguish a progressing video from a merely visible PlayerView.

To inspect the UI manually, use Settings to disable official mode and set the custom mirror to `http://127.0.0.1:8765`. Reset the application data or restore the default mirror after testing. No fixture address is the app default.
