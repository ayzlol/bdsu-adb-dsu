#!/bin/bash
# bDSU ROM SHA256 Checksum Generator
# Usage: chmod +x get_sha256.sh && ./get_sha256.sh

set -e

echo "=========================================="
echo "bDSU v1.2.4 ROM SHA256 Generator"
echo "=========================================="
echo ""

# ROM list: name=url
declare -A ROMS=(
  ["AOSP_Vanilla_16"]="https://github.com/ponces/treble_aosp/releases/download/v2025.06.25/aosp-arm64-ab-vanilla-16.0-20250625.img.xz"
  ["AOSP_GApps_16"]="https://github.com/ponces/treble_aosp/releases/download/v2025.06.25/aosp-arm64-ab-gapps-16.0-20250625.img.xz"
  ["AOSP_Vanilla_15"]="https://github.com/ponces/treble_aosp/releases/download/v2025.05.13/aosp-arm64-ab-vanilla-15.0-20250513.img.xz"
  ["AOSP_GApps_15"]="https://github.com/ponces/treble_aosp/releases/download/v2025.05.13/aosp-arm64-ab-gapps-15.0-20250513.img.xz"
  ["DerpFest_Full"]="https://github.com/KoysX/treble_DerpFest_GSI/releases/download/v2024.02.12/DerpFest-arm64_bgN-14.0-unofficial-20240212.img.xz"
  ["DerpFest_Mini"]="https://github.com/KoysX/treble_DerpFest_GSI/releases/download/v2024.02.12/DerpFest-arm64_bgN-mini-14.0-unofficial-20240212.img.xz"
  ["DerpFest_Pico"]="https://github.com/KoysX/treble_DerpFest_GSI/releases/download/v2024.02.12/DerpFest-arm64_bgN-pico-14.0-unofficial-20240212.img.xz"
  ["ArrowOS_Slim"]="https://github.com/naz664/ArrowOS_gsi/releases/download/v2023.10.28/ArrowOS-13.1-arm64_bgN-slim-Unofficial.img.xz"
  ["ArrowOS_bgN"]="https://github.com/naz664/ArrowOS_gsi/releases/download/v2023.10.28/ArrowOS-13.1-arm64_bgN-Unofficial.img.xz"
  ["ArrowOS_bvN"]="https://github.com/naz664/ArrowOS_gsi/releases/download/v2023.10.28/ArrowOS-13.1-arm64_bvN-Unofficial.img.xz"
  ["crDroid_Slim"]="https://github.com/naz664/crDroid_gsi/releases/download/v2025.03.08/crDroid-10.13-arm64_bgN-slim-Unofficial.img.xz"
  ["crDroid_bgN"]="https://github.com/naz664/crDroid_gsi/releases/download/v2025.03.08/crDroid-10.13-arm64_bgN-Unofficial.img.xz"
  ["crDroid_bvN"]="https://github.com/naz664/crDroid_gsi/releases/download/v2025.03.08/crDroid-10.13-arm64_bvN-Unofficial.img.xz"
  ["LineageOS_Vanilla"]="https://github.com/Doze-off/lineage_treble/releases/download/2026-06-03/LineageOS-23.2_GSI_treble_arm64-ab-VANILLA-20260603.img.xz"
  ["LineageOS_microG"]="https://github.com/Doze-off/lineage_treble/releases/download/2026-06-03/LineageOS-23.2_GSI_treble_arm64-ab-MICROG-20260603.img.xz"
  ["LineageOS_GApps"]="https://github.com/Doze-off/lineage_treble/releases/download/2026-06-03/LineageOS-23.2_GSI_treble_arm64-ab-GAPPS-20260603.img.xz"
)

RESULTS=()

for name in "${!ROMS[@]}"; do
  url="${ROMS[$name]}"
  file="/tmp/${name}.img.xz"
  
  echo "----------------------------------------"
  echo "İndiriliyor: $name"
  echo "URL: $url"
  
  if wget -q --show-progress -O "$file" "$url"; then
    hash=$(sha256sum "$file" | cut -d' ' -f1)
    size=$(stat -c%s "$file" 2>/dev/null || stat -f%z "$file" 2>/dev/null)
    echo "✅ $name = $hash"
    echo "   Boyut: $size bytes"
    RESULTS+=("$name=$hash")
    rm -f "$file"
  else
    echo "❌ $name - İNDİRME BAŞARISIZ"
    RESULTS+=("$name=ERROR")
  fi
done

echo ""
echo "=========================================="
echo "SONUÇLAR (MainActivity.kt için):"
echo "=========================================="
for result in "${RESULTS[@]}"; do
  echo "$result"
done

echo ""
echo "Kopyalayıp MainActivity.kt romCatalog'a yapıştırın."