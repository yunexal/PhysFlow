# PhysEx

**Languages:** **English** | [Українська](README.uk.md)

**PhysEx** is a server-side finite water and lava physics plugin for **Paper / Purpur** and **Folia** (`folia-supported: true`, Minecraft **1.16.5 – 26.3**, Java **16+**), developed by **Yunexal**.

It replaces Minecraft's infinite fluid behavior with volume-conserving finite fluids, gravity, hydrostatic pressure, block/piston displacement, evaporation, and async chunk-snapshot processing.

---

## Features

- **True Finite Water & Lava** (`finite-mode`): Disables vanilla 2x2 infinite water sources and conserves fluid volume.
- **Gravity & Level Equalization** (`gravity-enabled`, `equalize-enabled`, `min-spread-difference`): Water and lava fall downward and equalize surface levels without infinitely flattening shallow puddles.
- **Hydrostatic Pressure** (`pressure-enabled`, `pressure-max-depth`, `pressure-max-visited`): Connected vessels and pressurized gushing when breaching enclosed reservoirs.
- **Block & Piston Displacement** (`displace-on-block-place`, `displace-on-piston`): Placing solid blocks or extending pistons inside fluid pushes volume outward/upward instead of deleting it.
- **Waterlogging & Fragile Blocks** (`waterlog-capable-blocks`, `break-fragile-blocks`, `break-and-burn-fragile-blocks`): Fluids naturally fill waterloggable slabs, stairs, fences, and trapdoors, and break grass, flowers, saplings, torches, and redstone.
- **Survival Interactions**:
  - Filling glass bottles consumes water (`consume-on-bottle-fill`, `bottle-volume-cost`).
  - Buckets can scoop partial puddles together up to a full block (`bucket-scoop-partial-volume`).
  - Hydrating farmland gradually consumes nearby water (`consume-on-farmland-hydrate`, `farmland-consume-chance`).
  - Dripstone cauldron refilling can be disabled (`disable-dripstone-cauldron-fill`).
  - Thin puddles slowly evaporate in hot/dry biomes (`evaporate-thin-puddles-in-dry-biomes`, `thin-puddle-evaporate-chance`), with optional rain accumulation (`rain-fills-world`).
  - Three water–lava reaction modes (`water-lava-interaction.mode`): `PHYSEX_EVAPORATE`, `CONSUME_WATER_STONE`, `VANILLA`.
  - Melting ice triggers finite simulated water (`melting-ice-creates-finite-water`), and `unfreeze-marine-flora-on-drain` clears underwater kelp/seagrass walls during draining.
- **Multi-Language Support (i18n)**:
  - Built-in locales: English (`en`, default), Ukrainian (`uk`), Polish (`pl`), German (`de`), and Spanish (`es`) in `plugins/PhysEx/lang/<code>.yml`.
  - Automatic per-player client locale detection (`general.per-player-locale: true`) with configurable default fallback (`general.language: en`).
  - Drop any custom `<code>.yml` file into `plugins/PhysEx/lang/` to add a new language.
- **Background Async Processing & Native Folia Multithreading**:
  - Automatic runtime detection (`Paper/Purpur` or `Folia (Regionized)`).
  - By default (`general.async-processing: true`), fluid math, spreading checks, and hydrostatic pressure BFS run on dedicated worker threads (`general.async-worker-threads`) over immutable `ChunkSnapshot` overlays; only verified `BlockData` diffs are applied back on the main/region thread with strict volume conservation.
  - On Folia, fluid queues are sharded per region (`RegionScheduler` / `GlobalRegionScheduler` / `EntityScheduler`) with `Bukkit.isOwnedByCurrentRegion(...)` guards and safe cross-region volume transfer.
  - Per-tick CPU budget (`max-tick-time-ms`), update cap (`max-updates-per-tick`), queue limit (`max-queue-size`), TPS auto-throttling (`min-tps-threshold`), and queue persistence on shutdown (`persist-queue-on-shutdown`).

---

## Requirements

- **Server:** Paper, Purpur, or Folia **1.16.5 – 26.3**
- **Java:** **16** or newer (runs on Java 16, 17, 21+)

---

## Installation & Building

1. Download or build `PhysEx.jar` and place it into your server's `plugins/` folder.
2. Restart the server to generate `plugins/PhysEx/config.yml` and `plugins/PhysEx/lang/*.yml`.

### Build from Source

```bash
git clone https://github.com/yunexal/PhysEx.git
cd PhysEx
mvn package
```

Output artifact: `target/PhysEx.jar`.

---

## Commands & Permissions

- **Permission:** `physex.admin` (default: `op`)
- **Command:** `/physex` (aliases: `/pe`, `/fluidphysics`, `/fluids`)

| Command | Description |
| :--- | :--- |
| `/physex status` | Show detected runtime (`Paper/Purpur` or `Folia`), simulation state, TPS, last tick duration, queue sizes, and active modes. |
| `/physex reload` | Reload `config.yml` and `lang/*.yml` live without restarting. |
| `/physex pause` / `/physex resume` | Pause or resume fluid simulation. |
| `/physex clearqueue` | Clear all pending fluid update queues. |
| `/physex drain <radius 1..32> [water\|lava]` | Drain fluid within the given radius around the player. |
| `/physex fill <radius 1..32> [water\|lava]` | Fill an area around the player with water or lava. |
| `/physex set <key> <value>` | Update and save a `config.yml` option live (e.g. `/physex set general.language uk`). |

---

## Configuration Overview (`config.yml`)

- **`general`**: `enabled`, `async-processing`, `async-worker-threads`, `language`, `per-player-locale`, `worlds-whitelist`, `worlds-blacklist`, `max-tick-time-ms`, `max-updates-per-tick`, `max-queue-size`, `min-tps-threshold`, `persist-queue-on-shutdown`, `unfreeze-marine-flora-on-drain`.
- **`water`**: `enabled`, `tick-interval`, `finite-mode`, `gravity-enabled`, `equalize-enabled`, `min-spread-difference`, `pressure-enabled`, `pressure-max-depth`, `pressure-max-visited`, `displace-on-block-place`, `displace-on-piston`, `waterlog-capable-blocks`, `break-fragile-blocks`, `consume-on-bottle-fill`, `bottle-volume-cost`, `bucket-scoop-partial-volume`, `consume-on-farmland-hydrate`, `farmland-consume-chance`, `disable-dripstone-cauldron-fill`, `evaporate-thin-puddles-in-dry-biomes`, `thin-puddle-evaporate-chance`, `rain-fills-world`, `rain-fill-attempts-per-tick`.
- **`lava`**: `enabled`, `tick-interval`, `nether-tick-interval`, `finite-mode`, `gravity-enabled`, `equalize-enabled`, `min-spread-difference`, `pressure-enabled`, `pressure-max-depth`, `pressure-max-visited`, `displace-on-block-place`, `displace-on-piston`, `break-and-burn-fragile-blocks`, `disable-dripstone-cauldron-fill`, `bucket-scoop-partial-volume`.
- **`water-lava-interaction`**: `mode` (`PHYSEX_EVAPORATE` / `CONSUME_WATER_STONE` / `VANILLA`), `evaporate-effects`.
- **`ice-and-sponge`**: `melting-ice-creates-finite-water`.
