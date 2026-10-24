#!/usr/bin/env bash
set -e

APK_FILE="${1:-app/build/outputs/apk/release/app-release.apk}"
VERSION_NAME="${2:-1.2.0}"
VERSION_CODE="${3:-3}"
MANDATORY="${4:-true}"
MESSAGE="${5:-নতুন আপডেট: MKV HEVC 10-bit এবং নেটিভ FFmpeg অডিও-ভিডিও ডিকোডার যুক্ত করা হয়েছে।}"
REPO="jessicaalbert1234567-netizen/ban-video-player"
TAG="v${VERSION_NAME}"

if [ ! -f "$APK_FILE" ]; then
  # Check debug apk if release apk is not present
  if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    APK_FILE="app/build/outputs/apk/debug/app-debug.apk"
  else
    echo "Warning: APK file not found at $APK_FILE. Using placeholder SHA-256."
    SHA256_HASH="REPLACE_WITH_REAL_SHA256"
  fi
fi

if [ -f "$APK_FILE" ]; then
  SHA256_HASH=$(sha256sum "$APK_FILE" | awk '{print $1}')
  echo "Calculated SHA-256 for $APK_FILE: $SHA256_HASH"
fi

APK_URL="https://github.com/${REPO}/releases/download/${TAG}/app-release.apk"

cat <<EOF > version.json
{
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION_NAME}",
  "mandatory": ${MANDATORY},
  "apkUrl": "${APK_URL}",
  "sha256": "${SHA256_HASH}",
  "message": "${MESSAGE}"
}
EOF

echo "Successfully written version.json:"
cat version.json
