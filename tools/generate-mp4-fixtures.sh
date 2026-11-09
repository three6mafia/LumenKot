#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
lumen_fixture_root=app/src/test/resources/mp4-fixture
lumen_fixture_work=work/mp4-fixture-generator
mkdir -p "$lumen_fixture_work" "$lumen_fixture_root"/{encrypted,fmp4,audio,byterange}
ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc=size=160x90:rate=12 \
  -f lavfi -i sine=frequency=440:sample_rate=44100 -t 2 -c:v libx264 -preset ultrafast \
  -g 12 -pix_fmt yuv420p -c:a aac -shortest "$lumen_fixture_work/source.mp4"
python3 - <<'PY'
from pathlib import Path
key=Path('app/src/test/resources/mp4-fixture/encrypted/key.bin')
key.write_bytes(bytes(range(16)))
Path('work/mp4-fixture-generator/key-info').write_text('key.bin\n'+str(key.resolve())+'\n00000000000000000000000000000001\n')
PY
ffmpeg -hide_banner -loglevel error -y -i "$lumen_fixture_work/source.mp4" -c copy \
  -hls_time 1 -hls_playlist_type vod -hls_key_info_file "$lumen_fixture_work/key-info" \
  -hls_segment_filename "$lumen_fixture_root/encrypted/segment%02d.ts" "$lumen_fixture_root/encrypted/main.m3u8"
ffmpeg -hide_banner -loglevel error -y -i "$lumen_fixture_work/source.mp4" -an -c copy \
  -hls_time 1 -hls_playlist_type vod -hls_segment_type fmp4 \
  -hls_segment_filename "$lumen_fixture_root/fmp4/segment%02d.m4s" "$lumen_fixture_root/fmp4/main.m3u8"
ffmpeg -hide_banner -loglevel error -y -i "$lumen_fixture_work/source.mp4" -vn -c copy \
  -hls_time 1 -hls_playlist_type vod -hls_segment_filename "$lumen_fixture_root/audio/segment%02d.aac" "$lumen_fixture_root/audio/main.m3u8"
ffmpeg -hide_banner -loglevel error -y -i "$lumen_fixture_work/source.mp4" -c copy \
  -hls_time 1 -hls_playlist_type vod -hls_segment_type fmp4 -hls_flags single_file \
  -hls_segment_filename "$lumen_fixture_root/byterange/all.mp4" "$lumen_fixture_root/byterange/main.m3u8"
cat > "$lumen_fixture_root/master.m3u8" <<'PLAYLIST'
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Main",DEFAULT=YES,AUTOSELECT=YES,URI="audio/main.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=160x90,AUDIO="audio"
fmp4/main.m3u8
PLAYLIST
