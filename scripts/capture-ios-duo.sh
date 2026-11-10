#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 3 || ( "$3" != *.jpg && "$3" != *.jpeg ) ]]; then
    echo "Usage: $0 <simulator UDID> <display ID> <output.jpg>" >&2
    exit 1
fi

device="$1"
display="$2"
output="$3"
scratch="$(mktemp -d "$(dirname "$output")/.duo-screenshot.XXXXXX")"
trap 'rm -f "$scratch/screenshot.jpg"; rmdir "$scratch"' EXIT

xcrun simctl io "$device" screenshot --display="$display" --mask=ignored --type=jpeg "$scratch/screenshot.jpg"
dimensions="$(sips -g pixelWidth -g pixelHeight "$scratch/screenshot.jpg" |
    awk '/pixelWidth:/ { width = $2 } /pixelHeight:/ { print width "x" $2 }')"
case "$dimensions" in
    1398x2034|2034x1398|2007x2853|2853x2007) ;;
    *)
        echo "Unexpected screenshot size $dimensions. Select the active iPhone Duo display." >&2
        exit 1
        ;;
esac

mv "$scratch/screenshot.jpg" "$output"
printf 'Saved %s (%s)\n' "$output" "$dimensions"
