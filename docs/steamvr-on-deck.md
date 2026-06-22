# SteamVR on the Steam Deck via OpenHMD (LG 360 VR / R100)

Goal: use the R100 as a 3DOF headset in **SteamVR**, then use SteamVR's desktop
theater to watch flat media / play flatscreen games on a giant virtual screen.

> ⚠️ **Reality check.** This is the heaviest path, mostly because SteamOS has a
> read-only root. Expect a long package sync and some trial-and-error. We do it in
> stages so each step is verifiable before moving on. Sources: the community
> [R100-on-Deck guide](https://rentry.co/VR-R100-on-Deck),
> [SteamVR-OpenHMD](https://github.com/ChristophHaag/SteamVR-OpenHMD), and the
> [OpenHMD Journey thread](https://xdaforums.com/t/lg-360-vr-openhmd-journey-linux-mac-windows-bsd-support-and-steamvr.3810873/).

## Practical workflow notes

- **Do the long build with your normal keyboard/mouse attached** (via your hub).
  The headset is only needed for the *run/test* steps — and it wants the Deck's
  single USB-C port directly (no hub; DP Alt Mode doesn't traverse hubs). For the
  run steps, a **Bluetooth keyboard/mouse** is strongly recommended, or fall back
  to the Deck's built-in trackpads + on-screen keyboard (STEAM+X).
- **Cover the proximity sensor** (lens cover or a wad of paper) before running, or
  the headset thinks it's off your face and sleeps. (`r100_wake.py` worked around
  this with `Sleep Disable`; OpenHMD sends the same, but covering it is reliable.)
- **SteamOS updates wipe pacman changes** (read-only root). If an OS update lands,
  you may need to redo Stage 1. A `distrobox` container avoids this but adds
  complexity — noted at the end.

---

## Stage 1 — Prep SteamOS for building (~1 hour, mostly waiting)

In Desktop Mode → Konsole. Set a sudo password first if you haven't (`passwd`).

```bash
sudo steamos-readonly disable

# keyring / signing so pacman can install
echo "keyserver hkps://keyserver.ubuntu.com" | sudo tee -a /etc/pacman.d/gnupg/gpg.conf
sudo pacman-key --init
sudo pacman-key --populate
sudo pacman -Sc
sudo pacman -S archlinux-keyring
sudo pacman -Syyuu            # the long one — can take ~an hour
sudo pacman-key --refresh-keys

# build toolchain + libs
sudo pacman -S ninja meson cmake base-devel hidapi linux-headers \
  libglvnd glibc gcc sdl sdl2 sdl2_ttf lib32-sdl lib32-sdl2 \
  lib32-sdl2_ttf glew glu lib32-glu lib32-glew python3
sudo pacman -S linux-api-headers linux-neptune-headers
```

If packages come down corrupt, install them individually and re-run after
`sudo pacman -Sc`.

✅ **Stage 1 done when:** `gcc --version`, `meson --version`, `cmake --version` all
work and `pacman -Q hidapi` shows a version.

---

## Stage 2 — Build OpenHMD + prove 3DOF (the milestone before SteamVR)

This validates that OpenHMD itself drives the R100 (display + head tracking)
before we add the SteamVR layer.

```bash
mkdir -p ~/LGVR && cd ~/LGVR
git clone --branch LG-R100 https://github.com/OpenHMD/OpenHMD.git LG-R100
cd LG-R100
meson ./build -Ddrivers=lgr -Dexamples=simple,opengl
ninja -C ./build
sudo ninja -C ./build install
```

Now plug the headset **directly** into the Deck's USB-C, cover the proximity
sensor, and run:

```bash
cd ~/LGVR/LG-R100/build
sudo ./openhmd_opengl_example
```

✅ **Stage 2 done when:** the headset screens come alive and the rendered view
**moves as you turn your head** (3 degrees of freedom). If tracking is jittery or
drifts, that's expected — OpenHMD's R100 fusion is rough; we tune later.

> If `-Ddrivers=lgr` errors, the driver name may differ in this branch — run
> `meson ./build` once and check the printed driver options, or try the newer
> branch: `git clone --branch LG-R100-new https://github.com/ChristophHaag/OpenHMD.git`.

---

## Stage 3 — Build the SteamVR bridge (with R100-capable OpenHMD)

SteamVR-OpenHMD bundles OpenHMD as a submodule, but upstream OpenHMD has no R100
driver — so we point the submodule at the R100 branch before building.

```bash
cd ~/LGVR
git clone --recursive https://github.com/ChristophHaag/SteamVR-OpenHMD.git
cd SteamVR-OpenHMD

# Make the bundled OpenHMD the R100-capable one:
cd subprojects/openhmd 2>/dev/null || cd external/openhmd
git remote add lgr https://github.com/ChristophHaag/OpenHMD.git || true
git fetch lgr
git checkout LG-R100-new || git checkout LG-R100
cd -                       # back to SteamVR-OpenHMD root

mkdir -p build && cd build
cmake ..
make
```

The build dir must end up containing `driver.vrdrivermanifest`, `resources/`, and
`bin/linux64/driver_openhmd.so`.

> ⚠️ This is the step most likely to need iteration (submodule paths, the OpenHMD
> version the bridge expects, Steam runtime libstdc++ mismatches). If `make`
> fails, capture the error — there's also a `docker.sh`/runtime-matched build path
> we can fall back to. **Don't push through errors here; report them.**

---

## Stage 4 — Register with SteamVR and go big-screen

```bash
cd ~/LGVR/SteamVR-OpenHMD
./register.sh     # disables built-in HMD plugins + registers this build dir
```

Or manually:
```bash
~/.local/share/Steam/steamapps/common/SteamVR/bin/linux64/vrpathreg adddriver ~/LGVR/SteamVR-OpenHMD/build
cp ~/LGVR/SteamVR-OpenHMD/steamvr.vrsettings ~/.local/share/Steam/config/steamvr.vrsettings
```

Then:
1. Plug in the headset (direct USB-C), cover the proximity sensor.
2. Launch **SteamVR** from Steam.
3. Check `~/.local/share/Steam/logs/vrserver.txt` for errors if it doesn't start.
4. Once SteamVR sees the HMD, use **Desktop / Theater view** to throw any flat
   game or video onto a huge virtual screen with head tracking.

Useful config:
- Refresh rate: edit `displayFrequency` in
  `build/resources/settings/default.vrsettings` (panels do 57/60 Hz).
- Device selection: `~/.ohmd_config.txt` with `hmddisplay 0` etc.

To undo everything: `./unregister.sh`, then `sudo steamos-readonly enable`.

---

## Known gotchas specific to this headset

- 3DOF only (rotational) — no positional tracking. Fine for seated big-screen use.
- Tracking drift/jitter: OpenHMD's R100 fusion is a WIP; recenter as needed.
- Display must be in SteamVR's "extended" mode (the headset is a normal DP
  monitor, not a SteamVR direct-mode device).
- Each eye panel is physically rotated 90° — OpenHMD's driver already compensates
  in its projection; if the image looks rotated, that's the knob to check.

## Durable alternative: distrobox

If you don't want SteamOS updates wiping the toolchain, build inside an Arch
`distrobox` container instead of unlocking the root FS. Same build commands,
isolated environment. More moving parts to wire into the host SteamVR, so we'll
only go here if the read-only-disable route proves too fragile.
