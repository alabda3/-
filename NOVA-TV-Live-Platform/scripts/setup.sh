#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
if [ -f server/mediamtx.yml ]; then
  echo 'Already initialized. No credentials were overwritten.'
  exit 0
fi
command -v openssl >/dev/null || { echo 'Install openssl first'; exit 1; }
PASS="$(openssl rand -hex 24)"
cat > server/mediamtx.yml <<EOC
# Generated locally by scripts/setup.sh. DO NOT COMMIT THIS FILE.
logLevel: info
rtmp: true
rtmpAddress: :1935
hls: true
hlsAddress: :8888
hlsAlwaysRemux: true
hlsVariant: mpegts
hlsSegmentDuration: 2s
hlsSegmentCount: 20
hlsAllowOrigins: ["*"]
authMethod: internal
authInternalUsers:
  - user: publisher
    pass: "$PASS"
    ips: []
    permissions:
      - action: publish
        path: live
  - user: any
    pass:
    ips: []
    permissions:
      - action: read
        path: live
paths:
  live:
    source: publisher
EOC
chmod 600 server/mediamtx.yml
cat > server/OBS-PRIVATE.txt <<EOC
NOVA TV OBS settings (KEEP THIS FILE PRIVATE)
Service: Custom...
Server: rtmp://YOUR_SERVER_IP:1935/live?user=publisher&pass=$PASS
Stream key: (leave empty)

Viewer URL on local computer: http://YOUR_SERVER_IP:8080
HLS URL after HTTPS domain setup: https://YOUR_DOMAIN/hls/live/index.m3u8

NOTE: plain RTMP does NOT encrypt password or video. Use a VPN for remote publishing
or configure RTMPS with a valid TLS cert before public production use.
EOC
chmod 600 server/OBS-PRIVATE.txt
printf 'Created server/mediamtx.yml and server/OBS-PRIVATE.txt. Keep both private.\n'
