#!/usr/bin/env bash
# Stage 1b — (re)install the whole build environment.
# Use this after Stage 1, OR to recover after a SteamOS update wipes the
# toolchain from /usr (gcc/meson/etc go missing; your ~/LGVR work is safe).
# Run:  cd ~/lgvr && git pull && bash setup/stage1b-deps.sh
# Have your password ready and stay at the keyboard.

# SteamOS re-locks (and updates revert) the root FS — make it writable first.
echo ">>> Ensuring root filesystem is writable..."
sudo steamos-readonly disable || true

echo ">>> Installing the build environment..."
# NOTE: no --needed on purpose. SteamOS marks some packages "installed" but ships
# them stripped of dev files; --needed would skip them and break the build. We
# force them down so headers/.pc/.cmake files actually land in /usr.
sudo pacman -S --noconfirm \
    base-devel gcc make ninja meson cmake git python \
    glibc linux-api-headers linux-headers \
    hidapi libusb sdl sdl2 sdl2_ttf glew glu libglvnd

echo
echo "================ check ================"
ok=1
for t in gcc meson cmake ninja make git; do
    if command -v "$t" >/dev/null 2>&1; then
        printf "  %-6s OK (%s)\n" "$t" "$(command -v "$t")"
    else
        printf "  %-6s MISSING\n" "$t"; ok=0
    fi
done
if [ "$ok" = 1 ]; then
    echo "Build environment ready -> next:  bash setup/stage2-openhmd.sh"
else
    echo "Still missing something above — paste me pacman's error output."
    echo "(If you see keyring errors, run: sudo pacman-key --init && sudo pacman-key --populate)"
fi
