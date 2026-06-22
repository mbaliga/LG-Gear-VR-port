#!/usr/bin/env bash
# Stage 2 — build OpenHMD (LG-R100 driver) and run the head-tracking demo.
# Run with:  bash setup/stage2-openhmd.sh
#
# This is the milestone: OpenHMD driving the R100 with 3DOF head tracking.
# For the RUN step at the end, plug the headset DIRECTLY into the Deck's USB-C
# port (no hub) and cover the proximity sensor with paper or a lens cover.

set -e
WORK="$HOME/LGVR"
mkdir -p "$WORK"
cd "$WORK"

echo ">>> Force-reinstalling build dev-files..."
# NOTE: SteamOS marks several dev packages "installed" but ships them WITHOUT
# their dev files (.pc, headers, cmake configs). '--needed' then skips them and
# the files never land in the unlocked /usr, breaking pkg-config at build time.
# So we deliberately reinstall (no --needed) to force the files onto disk.
sudo pacman -S --noconfirm hidapi libusb sdl sdl2 sdl2_ttf glew glu libglvnd

if [ ! -d "$WORK/LG-R100" ]; then
    echo ">>> Cloning OpenHMD (LG-R100 branch)..."
    git clone --branch LG-R100 https://github.com/OpenHMD/OpenHMD.git LG-R100
else
    echo ">>> OpenHMD already cloned, reusing $WORK/LG-R100"
fi

cd "$WORK/LG-R100"
echo ">>> Configuring build (driver: lgr, examples: simple,opengl)..."
# Wipe any previous (possibly half-configured) build dir for a clean reconfigure.
rm -rf ./build
meson setup ./build -Ddrivers=lgr -Dexamples=simple,opengl
echo ">>> Building..."
ninja -C ./build
echo ">>> Installing..."
sudo ninja -C ./build install

echo
echo "================ Build done ================"
echo "Now:"
echo "  1. Plug the headset DIRECTLY into the Deck's USB-C port (no hub)."
echo "  2. Cover the proximity sensor (paper / lens cover)."
echo "  3. Run:   cd ~/LGVR/LG-R100/build && sudo ./openhmd_opengl_example"
echo
echo "SUCCESS = headset screens light up and the view turns as you move your head."
echo "(If meson errored on -Ddrivers=lgr, tell me — the driver name may differ on"
echo " this branch and we may switch to the LG-R100-new branch.)"
