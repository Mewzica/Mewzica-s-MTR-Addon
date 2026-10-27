# Mewzica's MTR Addon

An addon for **[Minecraft Transit Railway](https://modrinth.com/mod/minecraft-transit-railway)** (MTR) on **Fabric / Minecraft 1.20.1**.

It adds a **Signal Dashboard** — an in-game tool for viewing and editing the signal connections between rail nodes — plus a set of **manual driving** quality-of-life features (a departure countdown and a three-position reverser).

> Mod ID: `mewsmtr` · Built against MTR `4.0.5+1.20.1`

---

## Features

### Signal Dashboard

An item that opens a top-down view of the rails around you.

- **Rail list** on the left, with platform / siding type, length, signal colour count, occupancy and current speed.
- **Top-down map** in the centre, rendered on top of a live terrain backdrop baked from the world (the same block-colour baker MTR uses for its own map).
- **Real rail geometry** — the drawn line follows the actual curved path of the rail, sampled along its arc length, not a straight segment between two endpoints.
- **Node editing** — click node A, click node B, pick one of 16 signal colours, then `Connect` or `Disconnect`. Changes are sent to the server and applied through MTR's own `SignalModification`.
- **Navigation** — scroll to zoom, middle-drag to pan, `Reset view` to fit the bounding box of the rails.
- **Terrain toggle** — turn the backdrop off to free up VRAM on low-end machines.

### TPWS protection

A **TPWS grid** block (the "coil") — a thin plate that sits on the track bed between the rails, modelled in Blockbench. Unlike a pure decoration it is a **working train protection system**, modelled on the UK's TPWS:

| Mode | Behaviour |
| --- | --- |
| **Overspeed** (TPWS *OSS* loop) | A train passing over the grid faster than the threshold is braked to a standstill. The threshold is either a **fixed km/h value**, or the **rail's own speed limit plus a margin**. |
| **Signal** (TPWS *Arming + Trigger* loops) | The grid is linked to a nearby signal. If that signal's block section is occupied (red), a train passing over the grid is braked to a standstill — **the decision point moves from the signal node to the coil**. |

- **Configure it with MTR's brush** — right-click the grid while holding the brush to open the configuration screen. No new tool to learn.
- **Linking** — either press `Auto detect` in the screen (finds the nearest MTR signal within 8 blocks and reads its colour), or use the **TPWS Linker** item: right-click a signal, then right-click the grid.
- **Two different trigger semantics**, deliberately: overspeed is *edge triggered* (brake once on entry, hold for 3 s, then release) so a low threshold can't permanently trap a train; signal linking is *level triggered* (braked for as long as the signal is red and the train is on the grid).
- **Applies to every train**, autonomous or manually driven.
- **Trigger direction** — a coil can fire in both directions (default) or only for trains running north / east / south / west. Real TPWS loops are directional; firing both ways drags down trains legitimately running the other way on a single-track section.
- **Signals taken over** — once a signal is linked to a coil, the coil decides what it shows instead of MTR: **red while a train is on the coil, green otherwise**. MTR's own behaviour is the opposite of what you'd expect here — its signals are *dark* when the block section is clear and only light up while it is occupied, and only if the signal has been given a colour in its own configuration screen. Linking a coil to an unconfigured signal is why the lamp appears to do nothing.
- **Sounds** — a start-up tone when a train enters a coil's zone, plus dedicated overspeed and SPAD alarms.

> ⚠️ The coil index is built from block entities as their chunks load. A train passing a grid in a chunk that has **never** been loaded since the server started will not be protected there. In practice this only matters on a large server with trains running far from any player.

### Manual driving

Everything below shows up **inside MTR's own driving dashboard**, not as a separate overlay panel.

| Feature | Description |
| --- | --- |
| **Departure countdown** | Two lines anchored to the bottom-right of the dashboard: a `Departure` label and an `MM:ss` countdown, rounded up to the next second. Turns amber in the last 5 seconds, and shows `Ready` once the train may depart. |
| **Reverser** | A single letter — `F` / `N` / `R` — drawn just below the power indicator on the speedometer. `R` is highlighted in a warning colour. |

**Reverser keys**

| Key | Action |
| --- | --- |
| `Page Up` | Step the reverser one notch toward **Forward** |
| `Page Down` | Step the reverser one notch toward **Reverse** |

The reverser is a three-position handle, exactly like a real locomotive: `F ↔ N ↔ R`. Each key press moves it one notch and it stops at either end (so going from `F` to `R` takes two presses).

- **`F` (Forward)** — normal travel.
- **`N` (Neutral)** — traction is cut. The train is held in place and the throttle does nothing.
- **`R` (Reverse)** — the direction of travel is mirrored, so the throttle drives the train backwards.

**Interlocks**

- **The train must be at a standstill** to change the reverser position. Pressing a key while moving is ignored, on both the client and the server.
- **Only the driver can change it.** MTR's *driver* flag is "is holding the driver key", which keeps passengers from touching the reverser.

> ⚠️ **Reversing does not check behind you.** MTR only checks the track ahead for occupancy, so reversing is effectively a blind move. Drive carefully.

---

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 1.20.1 |
| Fabric Loader | ≥ 0.15.0 |
| Fabric API | 0.92.2+1.20.1 (or compatible) |
| Minecraft Transit Railway | ≥ 4.0.0 (developed against 4.0.5) |

This is a **Fabric-only** mod. It must be installed on both the client and the server.

---

## Installation

1. Install Fabric Loader for Minecraft 1.20.1.
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) and [Minecraft Transit Railway](https://modrinth.com/mod/minecraft-transit-railway) into your `mods` folder.
3. Drop `Mewzica-s-MTR-Addon-fabric-<version>+1.20.1.jar` into `mods` as well.
4. Launch the game.

---

## Usage

### Signal Dashboard

1. Craft or obtain the **Signal Dashboard** item (it lives in MTR's own creative tab).
2. Right-click to open it. You will see the rails within render distance of your current position.
3. Pick a rail on the left to highlight it on the map.
4. On the map, **left-click** to select node A, **left-click** again to select node B. **Right-click** cancels a selection.
5. Choose a signal colour on the right, then press **Connect** to link the two nodes with that signal, or **Disconnect** to remove an existing link.

### Manual driving

1. Board a train and take the driver's seat with the driver key, then switch the train to manual mode.
2. The departure countdown and the reverser letter appear inside the dashboard.
3. Use `Page Up` / `Page Down` to move the reverser — but only while the train is stopped.

---

## Building from source

### Prerequisites

- **JDK 17**
- **Gradle 8.14** (or use the bundled wrapper)
- Internet access on the first build — the **MTR mod jar** is downloaded automatically (see below)

### The MTR dependency

MTR is not pulled from a Maven repository, because this addon compiles against the **published release jar**. That jar is **not** committed to this repository (it is MTR's own artifact, ~81 MB), so the build fetches it for you:

- **First build downloads it automatically.** If `libs/MTR-fabric-4.0.5+1.20.1.jar` is missing, `build.gradle` downloads it from the Modrinth CDN and verifies its SHA-1 before compiling. The URL and hash live next to `mtrJar` in `gradle.properties`.
- **Behind a proxy?** Gradle does not read the `http_proxy` environment variable. Put the proxy in `GRADLE_USER_HOME/gradle.properties` (outside this project, so it is never committed) as `systemProp.http.proxyHost` / `systemProp.http.proxyPort`, and the same two keys with `https`.

- **Offline / manual:** download `MTR-fabric-4.0.5+1.20.1.jar` from [Modrinth](https://modrinth.com/mod/minecraft-transit-railway/versions), place it at `libs/MTR-fabric-4.0.5+1.20.1.jar`, then build with `./gradlew build -PskipMtrDownload`.

If you use a different MTR version, update `mtrJar`, `mtrDownloadUrl` and `mtrSha1` in `gradle.properties` together.

### Build

```bash
./gradlew build
```

The jar is written to `build/libs/Mewzica-s-MTR-Addon-fabric-<version>+1.20.1.jar`.

### If the build fails to resolve the Loom plugin

`fabric-loom` is resolved from a snapshot repository. If your machine has no internet access to it, or the snapshot metadata cache has expired, build offline instead — the plugin itself is normally already cached:

```bash
./gradlew build --offline
```

---

## Project layout

```
src/main/java/org/mtr/signaldashboard/
├── MTRSignalDashboard*           mod entrypoints and client init
├── Items                         item registration
├── Blocks                        block registration (TPWS grid)
├── item/ItemSignalDashboard      the dashboard item
├── data/                         IGui (all layout constants), Lang, SignalRailData
├── client/                       DrivingHud (dashboard overlay), DriveInput (reverser keys)
├── drive/ReverseDrive            server-side reverser state
├── mixin/                        MTR hooks (vehicle simulation, dashboard renderer)
├── packet/                       network packets
└── screen/SignalDashboardScreen  the dashboard GUI

src/main/resources/
├── fabric.mod.json
├── mewsmtr.mixins.json
└── assets/mewsmtr/               lang, models, textures
```

## Notes on implementation

A few things that are non-obvious, and are easy to get wrong:

- **The mod builds its own MTR `Registry`** instead of reusing MTR's. MTR's registry is deferred — `registerItem` only enqueues a request, and MTR has already flushed its own by the time this mod loads. Reusing it means the item never gets registered at all.
- **The reverser does not touch speed.** Negative speed is blocked in several places inside MTR's simulation. Instead, the addon mirrors the *increment* of `railProgress` — MTR still computes its normal acceleration curve, speed limits, braking and door timing, and the addon only flips the resulting displacement.
- **The client mirrors the displacement too.** MTR's client runs its own per-frame position prediction. If only the server mirrored, the client would keep predicting forward and get snapped back every tick — visible as the train creeping forward and jittering while reversing.
- **The terrain backdrop reuses MTR's `WorldMap`**, which bakes chunks in the background roughly every two seconds.

---

## License

[MIT](LICENSE) © Mewzica

## Credits

- **Mewzica** — author
- **[Minecraft Transit Railway](https://github.com/jonafanho/Minecraft-Transit-Railway)** by Jonathan Ho and contributors — the mod this addon extends
