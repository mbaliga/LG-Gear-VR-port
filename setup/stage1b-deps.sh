#!/usr/bin/env bash
# Stage 1b — install ONLY the build toolchain/libs.
# Use this if Stage 1's install step got skipped (e.g. the sudo password timed
# out while you were away). Skips the long system sync. Run:
#   cd ~/lgvr && git pull && bash setup/stage1b-deps.sh
# Have your password ready and stay at the keyboard.

echo ">>> Installing build toolchain + libraries (type your password promptly)..."
sudo pacman -S --needed \
    ninja meson cmake base-devel hidapi linux-headers \
    libglvnd glibc gcc sdl sdl2 sdl2_ttf lib32-sdl lib32-sdl2 \
    lib32-sdl2_ttf glew glu lib32-glu lib32-glew python3
sudo pacman -S --needed linux-api-headers linux-neptune-headers

echo
echo "================ check ================"
ok=1
for t in gcc meson cmake ninja; do
    if command -v "$t" >/dev/null 2>&1; then
        printf "  %-6s OK (%s)\n" "$t" "$(command -v "$t")"
    else
        printf "  %-6s MISSING\n" "$t"; ok=0
    fi
done
if [ "$ok" = 1 ]; then
    echo "Toolchain ready -> next:  bash setup/stage2-openhmd.sh"
else
    echo "Still missing something above — paste me pacman's error output."
fi
