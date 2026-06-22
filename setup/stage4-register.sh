#!/usr/bin/env bash
# Stage 4 — register the OpenHMD driver with SteamVR + write the OpenHMD config.
# Run:  cd ~/lgvr && git pull && bash setup/stage4-register.sh
# The actual SteamVR launch is a GUI step, printed at the end.

set -e
WORK="$HOME/LGVR"
BRIDGE="$WORK/SteamVR-OpenHMD"

if [ ! -f "$BRIDGE/build/bin/linux64/driver_openhmd.so" ]; then
    echo "!! Stage 3 output not found. Run setup/stage3-steamvr.sh first."
    exit 1
fi

echo ">>> Registering the OpenHMD driver with SteamVR (disables other HMD plugins)..."
cd "$BRIDGE"
./register.sh

echo ">>> Writing ~/.ohmd_config.txt ..."
cat > "$HOME/.ohmd_config.txt" <<'EOF'
hmddisplay 0
hmdtracker 0
leftcontroller -1
rightcontroller -1
EOF
echo "----- ~/.ohmd_config.txt -----"
cat "$HOME/.ohmd_config.txt"
echo "------------------------------"
echo "(Tip: if head-tracking drift is annoying for watching a fixed screen,"
echo " change 'hmdtracker 0' to 'hmdtracker -1' to lock the orientation.)"

cat <<'NEXT'

================ Stage 4: manual GUI steps ================
1. Plug the headset DIRECTLY into USB-C; cover the proximity sensor.
2. First launch (terminal) to run room setup:
     OHMD_VENDOR_OVERRIDE=Oculus ~/.steam/steam/steamapps/common/SteamVR/bin/vrstartup.sh
   -> choose "Standing Only", set height ~36in, finish, then press Ctrl+C.
3. In Steam: SteamVR -> Properties -> Launch Options, add:
     OHMD_VENDOR_OVERRIDE=Oculus %command%
4. Launch SteamVR. Navigate with the DECK'S screen/trackpads (in-VR
   controllers don't work). Open the Desktop/Theater view for big-screen
   flat media.

If SteamVR won't start, grab the log tail:
     tail -n 60 ~/.local/share/Steam/logs/vrserver.txt
and paste it to me.
===========================================================
NEXT
