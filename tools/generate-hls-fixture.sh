#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p work/hls-fixture/720
ffmpeg -hide_banner -loglevel error -y -i "${1:-/tmp/lumenkot-smoke.mp4}" \
  -c copy -hls_time 4 -hls_playlist_type vod \
  -hls_segment_filename work/hls-fixture/720/segment%03d.ts work/hls-fixture/720/main.m3u8
cat > work/hls-fixture/master.m3u8 <<'EOF'
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=320x180,CODECS="avc1.42c00d,mp4a.40.2"
720/main.m3u8
EOF
