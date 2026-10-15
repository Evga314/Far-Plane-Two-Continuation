# FarPlaneTwoContinuation

A Fabric mod for Minecraft **26.3** (Fabric Loader **0.19.5**, Fabric API) that draws simplified level-of-detail (LOD) terrain far
beyond the normal render distance, all the way to the edge of the world: **30 000 000 blocks**. It follows the idea of
[FarPlaneTwo](https://github.com/PorkStudios/FarPlaneTwo) by DaPorkchop_ (see [Credits](#credits)) and is written from scratch for the
26.x renderer.

Works on PC and on Android.

## Installation

| Where you play | Where to install |
|---|---|
| Singleplayer (PC or phone) | client only |
| Server | both the server and every client |

Fabric API for 26.3 is required. If the server does not have the mod, the client simply does not draw LOD terrain and the game works
as usual.

## Settings

**Options → Video Settings → "Far Plane (Distant Terrain)..."**. The button lives right in the menu, so no keyboard is needed on a phone.
With [Mod Menu](https://modrinth.com/mod/modmenu) installed the same settings open from the mod list.

- **LOD Distance**: 256 to 30 000 000 blocks. The maximum means the whole world: everything up to the world border is drawn, so the
  visible area is the square world itself. Tiles form a quadtree, so their number grows logarithmically with the distance and the
  whole world costs only a little more than 1 million blocks.
- **Detail**: how detailed the distant landscape looks right behind the normal render distance, from Ultra (a point every block)
  to Minimal (a point every 32 blocks). Lower settings load faster and suit weak devices.
- **Quality**: how early tiles split into finer ones (1.0-6.0, default 1.5).
- **GPU Memory**: video memory for distant terrain, up to 8 GB. More memory also makes the terrain sharper. Lower it on weak
  devices.
- **Fog Start**: part of the LOD distance after which terrain fades into fog.
- **No Fog**: removes all fog (render distance, weather, water, lava). Blindness and darkness still work. Off by default so it does
  not conflict with other fog mods.
- **Clear LOD cache...**: same as F3 + O, asks for confirmation first.

**Detailed Terrain** (a separate page in the settings): shows real blocks (trees, buildings) around the normal render distance
instead of simplified terrain, from chunks that already exist in the world. It also stands in for vanilla chunks that are not
loaded yet.

- **Generate Unexplored Areas**: also shows real blocks where nobody has been yet; the mod generates those chunks itself, in memory only
  (nothing is added to the world), and saves them for next time. When off, simplified terrain is drawn there. Uses CPU.

- **Distance**: radius in chunks counted from the player, up to 256. Farther away the usual distant terrain is drawn. The area
  never grows past what the GPU memory setting can hold; beyond that the distant terrain takes over.
- **Detail**: finest detail next to the player; it drops with distance automatically.
- **Quality**: how early areas switch to finer detail.
- **GPU Memory**: video memory for detailed terrain, up to 8 GB. More memory makes the area larger and sharper.

With Sodium installed, the same settings are also in Sodium's video settings, as the Distant Terrain and Detailed Terrain pages.

Key bindings (can be changed in Controls; on MojoLauncher they can be put on a custom on-screen button):

- **F7** - toggle distant terrain;
- **F8** - open Far Plane settings;
- **F3 + O** - clear the LOD cache and reload all distant terrain. Listed under Debug in Controls; O can be changed, F3 stays. In
  singleplayer this deletes the cached tiles of the world and generates them again. On a server only the tiles on your client are
  reloaded; the server cache can only be cleared by an admin.

Commands. `/FPhelp` lists them all in chat and works for every player; the `/farplane` commands are for operators, or the player
in singleplayer with cheats allowed:

- `/farplane pregen <radius>` - generate the detailed terrain within `<radius>` chunks (1 to 256) around you ahead of time, like
  Chunky does for chunks, but only the light copies used for distant terrain; the world itself is not changed. Chunks that already
  exist or were made before are skipped, and the work pauses while the server is busy.
- `/farplane pregen <radius> <x> <z>` - the same around the block column `<x> <z>`, for example `/farplane pregen 128 0 0`.
- `/farplane pregen status` - progress and time left.
- `/farplane pregen stop` - stop; what is done stays saved.

Config files: `config/farplanetwocontinuation-client.json` and `config/farplanetwocontinuation-server.json`. The server file lets the
admin limit the distance and detail, the detailed terrain radius, whether saved chunks are read for it, whether unexplored places
are generated for it, the number of generator threads, the request queue per player and the disk cache.

## How it works

1. Every tick the client walks a quadtree around the camera. Level `L` is a tile of 32×32 cells with a spacing of `2^L` blocks;
   level 19 covers ~16.7 million blocks. While a detailed tile is not ready yet, its coarser parent is drawn instead.
2. Missing tiles are requested from the server (the integrated one in singleplayer).
3. The server builds a tile straight from the world generator's noise. Chunks are **not generated or loaded**, which is what makes
   terrain millions of blocks away possible. LOD terrain has no trees, structures or player builds: only terrain, water and biome
   colors.
4. Near tiles find the surface exactly by sampling the noise density. Far tiles (32+ blocks between samples) use the generator's 2D
   surface estimate, which is checked against the exact surface on every tile, and take 3×3 sub-samples per cell whose colors the
   client averages, so the world does not turn into noise made of random islands when seen from far away.
5. Detailed tiles are built from real blocks: chunks loaded on the server are read directly and chunks saved on disk are read from
   the region files without loading them into the world. For every column up to three runs of solid blocks are kept and drawn as
   boxes colored like map colors, with grass, leaves and water tinted by the biome. Cells get coarser with distance, up to one
   cell per 4 blocks. Region files are read a few chunks at a time, so the server's own chunk loading never waits on LOD work.
   When blocks change, the server notes the chunk (a few bytes, nothing extra is saved) and the detailed tiles over it are loaded
   again, so what players built or broke shows up as soon as vanilla stops drawing that place. Where vanilla already draws a chunk,
   the detailed blocks under it are left out.
6. Chunks that were never generated are made in memory with vanilla's own world generation: terrain, surface blocks, trees,
   plants, snow and structures, exactly where the real ones will be. Only what can be seen from above is made: a band around the
   surface is filled, surface rules run only for the top layers, and aquifers, caves, ores and underground features are skipped.
   Only chunks that do not exist yet are made, and the work pauses while the server is short of time. Nothing is lit, saved
   to the world or registered with it; the result is kept in `<world>/farplanetwocontinuation/virtual/`, so every region is made
   only once. Once a chunk is really generated, its real blocks are used instead. While a tile is not ready the rough terrain shows
   through.
7. The higher the camera, the finer the tiles: from above the terrain is seen head-on instead of at a grazing angle. When the camera
   goes past the far plane of the vanilla renderer, the ground below the player is drawn by LOD as well.
8. Finished tiles are cached in the world folder (`<world>/farplanetwocontinuation/`) and are never generated twice.
9. The client builds meshes on a background thread and draws them through Minecraft's own rendering API (renderpearl): a separate
   pass before vanilla terrain, with its own projection fitted to the visible tiles. Where two layers cover the same place, the
   more detailed one wins: vanilla over detailed tiles, detailed tiles over rough ones. This costs nothing on the CPU: less
   detailed layers are only moved a little away from the camera for the depth test. Detailed tiles are left out wherever vanilla
   (or Sodium) already draws a chunk, and their back faces are culled, so walls of neighbouring tiles never flicker. Afterwards
   the depth is written again in vanilla's own depth range, so clouds behind LOD mountains are hidden.

## Phones (MojoLauncher)

Rendering uses the same calls as vanilla, without direct OpenGL, so it runs on every backend that Minecraft 26.3 itself runs on:
OpenGL, LTW (GL on top of GLES 3), Zink/Freedreno, Vulkan. Limits of LTW were taken into account:

- `glPolygonOffset` and `glPolygonMode` are not used, they are stubs in LTW;
- draw calls with a base vertex are not used, LTW needs GLES 3.1 for them. Each tile has its own vertex buffer and `baseVertex = 0`;
- 16-bit indices and one shared index buffer;
- without `clip_control` (common on GLES) depth precision drops, so the LOD pass fits its near and far planes to the visible tiles;
- if the driver cannot compile the shader, LOD terrain turns off with a message in the log instead of crashing the game.

## Safety

- No native code, JNI, `Unsafe`, process launching or network connections other than Minecraft's own channel. Reflection is used
  only to call one public method of Sodium (whether a chunk section is built), and only when Sodium is installed. On rooted devices the mod gets no extra permissions and touches nothing outside the game.
- Files are written in two places only: `config/` (two JSON files) and `<world folder>/farplanetwocontinuation/`. File names are built
  from numbers and a sanitized dimension name, and every path is checked to stay inside the cache root. Writes are atomic (through a
  temporary file).
- All input from the network and from disk is validated: packet and file size, number of requests per packet (≤ 64), palette size,
  string lengths, coordinates inside the world, allowed detail level, height range. Decompression is size-limited (zip bomb
  protection).
- The server limits every player: request queue (512 by default) and tiles generated at the same time (6). Distance and detail are
  set by the admin.
- Generation and mesh building run on a bounded number of background threads with minimum priority. Memory use is bounded by the
  cache limits.

## License

FarPlaneTwoContinuation is released under the [MIT License](LICENSE.txt).

## Building

```
./gradlew build
```

The jar ends up in `build/libs/FarPlaneTwoContinuation-x.x.x.jar`.

## Who made this mod

The idea to make a continuation of FarPlaneTwo is fully mine (Evga314). But my Java knowledge and experience are bad now. So, I use AI (Claude Opus 5.5) to write the code.

## Credits

This mod is a continuation of the idea of **FarPlaneTwo** by **DaPorkchop_**:
https://github.com/PorkStudios/FarPlaneTwo

FarPlaneTwo is Copyright (c) 2020-2026 DaPorkchop_ and is distributed under a license adapted from the MIT License, which asks every
user of the software to credit the original authors (DaPorkchop_) and to link to the original project. FarPlaneTwoContinuation does
not contain FarPlaneTwo source code: it is an independent implementation for Minecraft 26.3 and Fabric, inspired by the original
mod. Credit and the link are given here as the original author asked.
