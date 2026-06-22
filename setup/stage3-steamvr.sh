#!/usr/bin/env bash
# Stage 3 — build the SteamVR plugin, reusing the LG-R100 OpenHMD from Stage 2.
# Run:  cd ~/lgvr && git pull && bash setup/stage3-steamvr.sh
# Headset not required for the build itself.

set -e
WORK="$HOME/LGVR"
cd "$WORK"

if [ ! -d "$WORK/LG-R100/build" ]; then
    echo "!! Stage 2 output not found at ~/LGVR/LG-R100/build."
    echo "   Run setup/stage2-openhmd.sh first (must build successfully)."
    exit 1
fi

echo ">>> [1/3] udev rules (xr-hardware) so hidraw works without sudo..."
sudo pacman -S --noconfirm --needed python git make
if [ ! -d "$WORK/xr-hw" ]; then
    git clone https://gitlab.freedesktop.org/monado/utilities/xr-hardware.git "$WORK/xr-hw"
fi
( cd "$WORK/xr-hw" && make && sudo make install )

echo ">>> [2/3] Clone SteamVR-OpenHMD and swap in our LG-R100 OpenHMD..."
if [ ! -d "$WORK/SteamVR-OpenHMD" ]; then
    git clone --recursive https://github.com/ChristophHaag/SteamVR-OpenHMD.git
fi
# The bundled OpenHMD has no R100 driver — replace it with our working one.
rm -rf "$WORK/SteamVR-OpenHMD/subprojects/openhmd"
cp -r "$WORK/LG-R100" "$WORK/SteamVR-OpenHMD/subprojects/openhmd"

echo ">>> [3/3] Build the SteamVR plugin..."
cd "$WORK/SteamVR-OpenHMD"
rm -rf build && mkdir build && cd build
cmake ..
make

echo
echo "================ Stage 3 check ================"
DRV="$WORK/SteamVR-OpenHMD/build/bin/linux64/driver_openhmd.so"
if [ -f "$DRV" ]; then
    echo "OK: $DRV"
    echo "Now replug the headset (so udev ACLs apply), then:  bash setup/stage4-register.sh"
else
    echo "MISSING: $DRV — the build didn't finish. Paste me the last errors above."
fi
