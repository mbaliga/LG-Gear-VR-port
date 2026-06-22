#!/usr/bin/env bash
# Stage 1 — prep SteamOS for building OpenHMD.
# Run with:  bash setup/stage1-deck.sh
#
# pacman steps are left INTERACTIVE on purpose: if it asks you something
# (replace package? which provider?), DON'T guess — paste the question to me.
# The headset does NOT need to be plugged in for this stage.

echo ">>> [1/6] Disabling read-only root filesystem..."
sudo steamos-readonly disable || { echo "FAILED to disable readonly"; exit 1; }

echo ">>> [2/6] Adding keyserver (if not already there)..."
if ! grep -q keyserver.ubuntu.com /etc/pacman.d/gnupg/gpg.conf 2>/dev/null; then
    echo "keyserver hkps://keyserver.ubuntu.com" | sudo tee -a /etc/pacman.d/gnupg/gpg.conf
fi

echo ">>> [3/6] Initializing + populating pacman keyring..."
sudo pacman-key --init
sudo pacman-key --populate
sudo pacman -Sc

echo ">>> [4/6] Updating keyring package..."
sudo pacman -S --needed archlinux-keyring

echo ">>> [5/6] Full system sync/upgrade — THE LONG ONE (up to ~1 hour)..."
sudo pacman -Syyuu
sudo pacman-key --refresh-keys

echo ">>> [6/6] Installing build toolchain + libraries..."
sudo pacman -S --needed \
    ninja meson cmake base-devel hidapi libusb linux-headers \
    libglvnd glibc gcc sdl sdl2 sdl2_ttf lib32-sdl lib32-sdl2 \
    lib32-sdl2_ttf glew glu lib32-glu lib32-glew python3
sudo pacman -S --needed linux-api-headers linux-neptune-headers

echo
echo "================ Stage 1 check ================"
ok=1
for t in gcc meson cmake ninja; do
    if command -v "$t" >/dev/null 2>&1; then
        printf "  %-6s OK (%s)\n" "$t" "$(command -v "$t")"
    else
        printf "  %-6s MISSING\n" "$t"; ok=0
    fi
done
if pacman -Q hidapi >/dev/null 2>&1; then
    printf "  %-6s OK (%s)\n" "hidapi" "$(pacman -Q hidapi)"
else
    printf "  %-6s MISSING\n" "hidapi"; ok=0
fi
if [ "$ok" = 1 ]; then
    echo "Stage 1 done -> run:  bash setup/stage2-openhmd.sh"
else
    echo "Something is missing above. Re-run the install and check pacman's errors:"
    echo "  sudo pacman -S --needed base-devel gcc meson ninja cmake"
fi

