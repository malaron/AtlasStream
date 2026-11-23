# AtlasStream

AtlasStream is a client-side Minecraft mod for distance-based
texture level of detail (LOD) for large resource packs.

It reduces the VRAM used by oversized block-texture atlases by:

- Repacking a smaller, mixed-resolution low atlas for distant textures.
- Keeping nearby textures at full resolution.
- Streaming only the high-resolution tiles currently needed.
- Preserving LabPBR normal/specular textures with Sodium and Iris.

> [!IMPORTANT]
> This project is currently tested primarily with Minecraft 26.3. Treat other
> versions, resource packs, shader packs, GPUs and renderers as experimental
> until explicitly listed in a release's compatibility notes.

## Contents

- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Transparency and licensing](#transparency-and-licensing)
- [Configuration](#configuration)
- [Commands](#commands)
- [How it works](#how-it-works)
- [Development](#development)
- [License](#license)

## Requirements

- Minecraft 26.3
- Fabric Loader 0.20.1
- Fabric API
- Sodium for distance-based terrain streaming and virtual/oversized atlas
  handling
- Iris and `GL_ARB_sparse_texture` for full sparse near-atlas behavior
- Optional: Mod Menu and Yet Another Config Lib for the configuration screen

Compatibility notes and known issues are tracked in [BUGS.md](./BUGS.md).

## Quick start

1. Install the AtlasStream JAR with Fabric API. Add Sodium for distance-based
   streaming and oversized virtual-atlas handling; Iris is optional.
2. Join a world.
3. Let the initial atlas build complete, or run
   `/atlasstream preview-distance`.
4. Open the Mod Menu configuration screen to set:
   - high-resolution distance,
   - cache budget,
   - low-atlas target resolution.
5. Use F3+T after changing a setting that affects atlas construction.
6. Use `/atlasstream vram`, `/atlasstream cache`, and `/atlasstream near-debug`
   if you need to investigate performance or missing detail.

Without Sodium, AtlasStream can still build and preview a normal, GPU-sized
reduced atlas with `/atlasstream preview-low`. The current virtual-atlas path
for packs larger than the GPU's atlas limit requires Sodium.

## Transparency and licensing

AtlasStream was developed with substantial AI assistance. This repository and
its commit history are published so users can inspect how the project works and
make an informed decision about using it.

This is source-visible software, not an open-source distribution license.
Personal, non-commercial use and private modification are allowed, but public
redistribution or repackaging—including uploads to Modrinth, CurseForge,
GitHub Releases, mirrors, or public modpacks—is not permitted without written
permission. See [LICENSE](./LICENSE) for the complete terms.

Only builds published through AtlasStream's official release channel should be
treated as official. If you encounter AtlasStream published elsewhere, verify
the attribution and release source before using it.

AtlasStream integrates with Sodium for terrain streaming and optionally with
Iris shaderpacks. Vanilla rendering can use the reduced-atlas preview; the
current oversized virtual-atlas path and distance streaming require Sodium.

Reported issues and deferred investigations are tracked in [BUGS.md](./BUGS.md).

## How it works

At a high level, AtlasStream provides:

- Minecraft 26.3 and Fabric setup.
- A client configuration file for the high-resolution distance and hysteresis.
- Renderer-independent helpers for classifying a chunk section by its distance
  from the camera.
- A resource-pack atlas builder that creates a matched atlas pair and sprite
  layout manifest.
- A reduced-atlas preview and a streamed distance mode that can release the
  original full-resolution block-atlas GPU allocation.

<details>
<summary><strong>Rendering paths and transparency behavior</strong></summary>

`/atlasstream preview-low` switches terrain to the reduced atlas for visual testing.
`/atlasstream preview-distance` enables distance-based albedo LOD. Without an active
Iris shaderpack, Sodium terrain samples resident high-resolution tiles where
available and uses the reduced atlas on cache misses;
the full high-resolution block-atlas GPU allocation is released. Held and
dropped block-atlas item models also request nearby full-resolution tiles,
using the reduced atlas while tiles load or after eviction. Inventory icons and
other block-atlas users continue to use the reduced atlas. A resource reload
restores the original atlas when it fits; oversized virtual layouts use a
placeholder until the streamed atlas is rebuilt. Cached inventory item images
are refreshed when the reduced atlas becomes ready, so temporary placeholder
colors do not persist.
Every persisted option exposed by the Mod Menu configuration screen is also
available under `/atlasstream config`: distance in chunks, hysteresis in blocks,
cache budget in MiB, low-atlas sprite target resolution, low-resolution debug tint, and
enable-on-launch. Run `/atlasstream config` without arguments to print the saved
values. The UI and commands share the same boundaries and allowed target
resolutions; settings changed by command are saved to the same configuration file.
Use `/atlasstream config target-resolution <16|32|64|128|256>` to change the low-atlas
sprite target from chat.
With an Iris shaderpack active, the reduced atlas (albedo
plus normal/specular LabPBR maps, averaged per channel to the same ratio) serves
distant terrain, and a full-size or compact sparse "near atlas" (needs
`GL_ARB_sparse_texture`) serves nearby terrain, held items, entities and
particles. The near atlas commits GPU pages only for sprites used by sections
within the high-resolution distance (plus a two-chunk prefetch margin) or drawn
recently, so the full-resolution VRAM is mostly not spent. When its committed
memory exceeds `highResolutionCacheMiB`, sprites no nearby section needs
(unused for 10 seconds) are released, least recently needed first; the budget
is soft for the current working set. A compact atlas can also evict needed
sprites under slot pressure, as described below. With the distance at 0,
all near memory is released. Its albedo and PBR content is
streamed from the resource pack on worker threads; PBR maps stay vanilla
(first frame only) while albedo animates, interpolated like vanilla, but only
for sprites used by sections that are in range and in view. When setting the distance to 0
chunks, everything uses the low atlas and nothing animates in the near atlas.
For near albedo sprites with both fully and partly transparent pixels and the
automatic mip strategy, near albedo mip alpha is averaged from source coverage
instead of using cutout-coverage scaling and bias. This avoids raising fully
transparent areas above translucent shader cutoffs and preserves averaged
partial transparency. Mip RGB, explicit mip strategies and normal/specular
maps are unchanged.
Set `-Datlasstream.preserveTransparentMips=false` before launch to disable this
correction for comparison.
Some shaderpacks also refract partially transparent terrain. To reduce blur on
resource-pack foliage overlays, Sodium terrain uses a default-on geometric
heuristic to render certain translucent overlays as cutout; see the overlay
pass commands below for limitations and comparison.
The mod registers the atlases with Iris's texture tracker so Iris still finds
them. With an active Iris near atlas, translucent terrain is also split by
distance. Animated low-atlas sprites follow Minecraft's frame sequence
and interpolation state. Sodium's packed local section indices are decoded
and combined with their owning region coordinates before applying the
world-space distance policy. Sodium's per-region batches are rebuilt from each
filtered section list before drawing, invalidating only the active terrain pass
so other prepared passes remain cached.

</details>

<details>
<summary><strong>Oversized packs and compact near-atlas behavior</strong></summary>

### Oversized packs: compact near atlas

When a pack's stitched atlas is too large to keep (for example 256x packs at
65536x32768), the vanilla block atlas becomes a tiny virtual placeholder and
the near atlas is created as a *compact* sparse atlas (for example 16384x16384)
that is much smaller than the layout. A small remap texture maps each 16x16
texel layout cell to its slot in the compact atlas, and terrain vertex shaders
translate UVs through it. With Iris this works by patching the shader text Iris
generates for Sodium (`_get_texcoord` and `mc_midTexCoord`), so it depends on
the Iris version: an Iris update that changes that text may need a matching
change here. The log warns when `mc_midTexCoord` is present but could not be
patched. The same remap is applied to the vanilla (non-terrain) shaders, guarded
by the bound texture size, so held blocks, falling blocks and particles are
full-resolution too. Where Iris supplies a midpoint UV, non-terrain remapping
uses it to select the sprite slot and remaps the midpoint for shaders' parallax
calculations. Fabric mesh sprites in item frames, minecart display blocks and
primed TNT are requested for the near atlas when drawn. With Iris, translucent
terrain (glass) is split by distance so near glass also uses the near atlas.

If the compact atlas fills up, sprites without a slot wait in a queue. Under
pressure sprites used in sections within four blocks of the camera, a section up to 12 blocks
ahead in the horizontal direction of movement, and recently drawn items,
entities and particles are prioritized over other sections. The movement
direction is retained briefly when stopping. Among sprites of
the same priority, residents are evicted only when a waiting sprite is more
than eight blocks nearer. A freed slot can be split for smaller sprites and
adjacent free rectangles can merge again; evictions can use any slot large
enough. Still-needed evicted sprites rejoin the queue. Sections whose sprites are not all resident
are drawn from the reduced atlas. A large queue of far sprites is normal when
the compact atlas is smaller than the view needs. To test this, launch with
`-Datlasstream.compactSize=8192` (texels, default 16384) and watch the F3
"Near sparse atlas" line and the `Slot pressure:` debug log lines.
Evicted sprites release only pages no remaining sprite references. Adjacent
pages are decommitted in rectangular batches to reduce driver calls without
keeping extra pages committed.
Compact requests and completed uploads are prioritized by proximity rather than
the order in which chunks were built. Distances use the same section bounding
boxes as the rendering policy. Already-started work is not preempted, and
neither this ordering nor prefetching guarantees a fully resolved section when
the atlas is full or streaming cannot keep up.
Compact sprite preparation and uploads each use a roughly 4 ms per-tick
render-thread budget, allowing at least one operation in each phase for
progress. Individual operations can exceed that budget, and spreading work
over ticks can delay sharpening when there is a backlog.

#### Experimental hybrid compact atlas

The mixed-resolution low atlas cannot use the layout-based hybrid fallback,
because that fallback relied on one global division of every sprite coordinate.
For repacked low atlases, `-Datlasstream.hybridCompact=true` uses the regular
compact near-atlas streaming path; this is logged at activation.
The JVM option's previous layout-based fallback remains under development and
will need a second remap-capable fallback path before it can be restored.

Nearby requests remain prioritized, but insufficient capacity or slow uploads
can cause nearby misses while a sprite waits for a compact slot. Mipmap
filtering, parallax, transparency, animations and shader-pack compatibility
still need in-game verification.

</details>

### Compatibility

Distance-based terrain streaming and oversized virtual-atlas stitching require
Sodium. The development and recent
in-game regression tests used Minecraft 26.3, Fabric Loader 0.20.1, Sodium
0.9.2, and Iris 1.11.7, including Kappa and Photon shaderpacks. Other renderer,
shaderpack, GPU, and platform combinations need independent testing; a passing
build alone does not establish compatibility. Sparse near atlases require
`GL_ARB_sparse_texture`; without it, Iris terrain uses the reduced atlas.

<details>
<summary><strong>Reduced-atlas disk cache</strong></summary>

### Reduced-atlas disk cache

Building the reduced atlas (and its PBR maps) takes tens of seconds for large
packs, so the result is cached in `cache/atlasstream/<key>.bin` under the game
directory. The key is a hash of the mod build, loaded mods, atlas layout,
settings and the ordered resource pack stack, including each pack's size and
modification time. Changing a pack, its order or any mod changes the key, so a
fresh atlas is built instead of reusing a stale one. Packs that cannot be
fingerprinted (for example server packs) skip the cache. Entries are checksummed
(bad ones are deleted), the least recently used are pruned beyond 3 GiB, and
animation data is always rebuilt from the packs. On a cache hit the log shows
"Loaded the reduced atlas from the disk cache".

A second, per-pack cache (`cache/atlasstream/tiles/`) stores each reduced
sprite tile under the pack that provided its texture (keyed by that pack's size
and modification time, or the mod version). When the pack stack changes, only
sprites whose providing pack changed are decoded again; the rest are copied from
the tile files and the mip levels are rebuilt. Animated sprites and sprites from
packs that cannot be fingerprinted are always decoded. The log line
"Tile cache (...)" shows how many sprites came from cached tiles.

</details>

## Configuration

On first launch the mod creates `config/atlasstream.properties`:

```properties
highResolutionDistance=128.0
hysteresis=16.0
highResolutionCacheMiB=1024
targetResolution=64
```

| Setting | Default | Meaning |
|---|---:|---|
| `highResolutionDistance` | 128 blocks | Distance at which nearby textures use full resolution. The UI shows chunks; `0` disables near textures. |
| `hysteresis` | 16 blocks | Dead band before switching back between near and far detail. |
| `highResolutionCacheMiB` | 1024 MiB | Tile-cache limit without Iris; soft near-atlas budget with Iris. Allowed range: 64–16384 MiB. |
| `targetResolution` | 64 | Maximum low-atlas sprite dimension: 16, 32, 64, 128 or 256. Smaller sprites are not upscaled. |
| `debugTintLowResolution` | false | Tints low-resolution terrain magenta for comparison. |
| `enableOnLaunch` | true | Builds and activates streamed mode automatically. Takes effect after restart. |

All settings can be edited in game via Mod Menu (21.0.0 for 26.3) when Yet
Another Config Lib is installed; both are optional. To activate distance-based
LOD manually, join a world, save any pending settings, and use **Activate** in
the AtlasStream settings. The screen closes and progress or errors appear in
chat. The button shows **Active** and is disabled while LOD is already enabled.
Reload resource packs to rebuild the atlas after changing texture reduction or
debug tint.

<details>
<summary><strong>Configuration behavior and hysteresis details</strong></summary>

`targetResolution` applies to albedo and Iris normal/specular atlases. Larger
sprites are reduced with area averaging; smaller sprites remain at their
original resolution and are never upscaled. The reduced sprites are repacked,
and a remap texture preserves Minecraft's original atlas UVs.

When `enableOnLaunch` is enabled, Iris's full-size PBR loading is replaced by
the reduced build while shaders initialize. GPU activation waits until a world
and Iris pipeline are ready.

A section remains high resolution until it crosses `distance + hysteresis`, and
switches back inside `distance - hysteresis` or half the configured distance,
whichever is larger. This dead band prevents visible boundary flicker.

Without Iris, the cache limit bounds the GPU tile cache, subject to atlas and
GPU size limits. With Iris, it is a soft target: the current working set may
temporarily exceed it.

</details>

## Commands

| Command | Purpose |
|---|---|
| `/atlasstream preview-distance` | Build/activate distance-based streamed mode. |
| `/atlasstream preview-low` | Preview only the reduced atlas without releasing the original. |
| `/atlasstream preview-high` | Return a low-atlas preview to the original atlas. |
| `/atlasstream config` | Print current persisted settings. |
| `/atlasstream config distance <chunks>` | Set high-resolution distance. |
| `/atlasstream config target-resolution <pixels>` | Set low-atlas target resolution. |
| `/atlasstream config hysteresis <blocks>` | Set transition dead band. |
| `/atlasstream config cache <mib>` | Set cache budget. |
| `/atlasstream config debug-tint <true\|false>` | Toggle low-resolution tinting. |
| `/atlasstream config enable-on-launch <true\|false>` | Toggle automatic activation. |
| `/atlasstream vram` | Print GPU and atlas VRAM information. |
| `/atlasstream cache` | Print streaming/cache status. |
| `/atlasstream near-debug` | Report near-atlas misses and slot pressure. |

<details>
<summary><strong>Advanced diagnostic and comparison commands</strong></summary>

`/atlasstream vram` prints a VRAM report, and an F3 debug entry shows the
current mode and atlas state. With Iris, `/atlasstream near-debug` reports
missing near-atlas sprites and targeted block sections, writes details to the
log, and includes slot-pressure information.
`/atlasstream sprite-alpha minecraft:block/poppy` compares selected source, original CPU
mip, expected near upload and resident GPU alpha values for a static sprite. This diagnostic
synchronously reads GPU pixels and can briefly hitch; its output is also
written to the game log. It does not measure shader filtering or alpha-test state.
`/atlasstream terrain-alpha` records the active OpenGL program and alpha-test uniforms
after terrain batch draws for five seconds, plus diffuse/remap sampler bindings
and their mip limits. The output is written to the log; it does not identify
which blocks are in each batch or measure per-fragment alpha.
For a controlled plant transparency comparison with Sodium, look at an affected
block and run `/atlasstream plant-pass cutout`. Translucent terrain quads for that block
type temporarily use the cutout pass; `/atlasstream plant-pass normal` ends this
override, while the independent flower and overlay settings still apply.
Select another block with the cutout command to switch targets.
Both commands schedule terrain rebuilding and log each distinct matching quad
layer and its selected layer. This experiment does not change texture data,
other blocks, or item rendering; it resets to normal when the game restarts.
The default-on geometry-based overlay correction changes only translucent,
upward-facing horizontal quads immediately above a solid block, with a bounded
footprint; it does not change every translucent quad on that block. It is a
heuristic, not a way to determine the pack author's intent: matching overlays
lose soft transparency and might include intentionally translucent effects.
It applies to Sodium terrain with or without shaders, independently of the
LOD preview. The first matching block IDs and quad heights are logged. Use
`/atlasstream overlay-pass normal` to disable it for the current game session, and
`/atlasstream overlay-pass cutout` to re-enable it; it is enabled again after restart.
Each change reloads Sodium terrain and may briefly interrupt rendering; the
plant-pass experiment is independent and can still override its target.
By default, translucent terrain quads of blocks in Minecraft's `small_flowers`
tag use cutout, including any additions to that tag. This applies with Sodium
both with and without shaders, independently of LOD preview. Matching quads
lose soft transparency, and the tag does not include every plant. Use
`/atlasstream flower-pass normal` to disable it for the current game session, or
`/atlasstream flower-pass cutout` to re-enable it; it is enabled again after restart.
Each change reloads Sodium terrain. The independent plant-pass experiment can
still override its selected block.
With Iris and an active near atlas, `/atlasstream near-mip-test base` temporarily limits
near terrain albedo sampling to mip level 0 without changing Minecraft's mipmap
setting or reloading resources. It affects all nearby terrain and may cause
shimmering; PBR and distant fallback textures are unchanged. Restore normal
sampling with `/atlasstream near-mip-test normal`. Rebuilding the near atlas or
restarting the game also resets the test.

</details>

## Development

- Minecraft: 26.3
- Loader: Fabric
- Java: 25
- Fabric API: see `gradle.properties`

### Code organization

- `atlas` — atlas construction and image/PBR processing.
- `lod` — configuration and section-distance policy.
- `render` — terrain-layer correction policies.
- `shader` — shader source patching.
- `streaming` — renderer-independent layout, residency and request handling.
- `client.atlas` — runtime atlas construction and near atlas.
- `client.streaming` — GPU cache and item-pipeline integration.
- `client.sodium` — Sodium terrain integration.
- `client.diagnostics` — runtime diagnostics and reports.
- `client.command` — `/atlasstream` commands.
- `client.mixin` — compatibility hooks into Minecraft, Sodium and Iris.

Build and run all tests with:

```powershell
.\gradlew.bat clean build
```

<details>
<summary><strong>Detailed renderer implementation notes</strong></summary>

To test against the resource packs
currently loaded by Minecraft, install the resulting mod jar, enter a world,
and run `/atlasstream preview-low` to build and select a second GPU block atlas.
Each sprite is independently reduced to at most the configured target
resolution and the reduced sprites are repacked. Original sprite UVs are
preserved by a low-atlas remap texture, while sprites already below the target
are copied without upscaling. For a non-virtual atlas that has not been
released, run
`/atlasstream preview-high` to switch back to the untouched original atlas without
reloading resource packs after `/atlasstream preview-low`; the generated low atlas
stays resident for a quick switch back. This low-only preview does not reduce
total VRAM usage. `/atlasstream preview-distance` instead activates streamed mode and
releases the original full-resolution block atlas. A resource reload restores
the original atlas when it fits and then rebuilds streamed mode automatically;
oversized virtual layouts instead use a placeholder. The low-atlas CPU mip
chain and cached animation frames share a 2 GiB safety limit.
Mip levels follow the reduced atlas and are generated within each sprite's
padded bounds to avoid sampling neighboring sprites.
A resource-pack reload discards the cached low atlas. If the distance preview
was on, it is rebuilt automatically about a second after the reload (this
also picks up a changed `targetResolution` or debug tint), so no command is
needed. A manual `/atlasstream preview-distance` is only needed if that rebuild is
skipped (see `latest.log`).
Run `/atlasstream preview-distance` to enable streamed terrain detail. Without an
active Iris shaderpack, virtual tiles are sampled where resident, with the
reduced atlas as fallback. With an active Iris shaderpack, sections switch
between the reduced atlas and a sparse near atlas; the reduced albedo and PBR
atlases use matching dimensions, while the near atlas supplies full-resolution
albedo and PBR. In low-preview mode the original full-resolution normal and
specular maps stay bound. Iris's active shader samplers are refreshed at
high/low draw boundaries.
Iris shaderpack terrain currently uses a hard per-section albedo switch.
Generic shaderpack crossfading is not
enabled because Iris's current shader source hook does not reliably preserve
injected declarations through its compilation pipeline.
Streamed mode releases the full-resolution albedo atlas.
Animated albedo sprites follow
Minecraft's frame sequence and interpolation state; animation frame caches
share the CPU image safety limit. Previously blended low-atlas animation frames
are reused in a separate cache of up to 32 MiB of CPU memory, cleared on atlas
reload. `/atlasstream perf start` reports how many blends were built or reused during
its CPU sample, along with near-atlas refresh time. The repeating Nether portal
sprite's opposite edges are matched at each low-atlas mip level to reduce visible
seams between adjacent portal blocks after downscaling.
The byte-bounded LRU eviction primitive and CPU-side virtual-UV-to-tile address
model are unit-tested. The GPU page-table texture and bounded 2D tile-atlas
cache upload mip-complete tiles, update residency entries, and clear entries
on eviction. When the cache is full, nearer requests take priority over farther
resident tiles; equal-priority requests do not repeatedly evict one another.
Demand is refreshed periodically so tiles no longer nearby can be replaced.
This uses a 2D texture because Minecraft's current RenderPearl
backend does not support texture arrays. Tiles are composed from Minecraft's
stitched sprite mipmaps. Tiles touching animated sprites are drawn from the
sprite's current full-resolution frame and rewritten in place when it changes,
for sprites used by sections within the streaming range or by recently drawn
block-item models (within a time-limited per-tick upload budget, built on worker threads). Interpolated
animations are blended in 4 steps between frames. New tiles are also built on
worker threads, nearest first, and the render thread only uploads finished
tiles within a small time budget. Tile construction finds intersecting sprites
once per tile rather than rescanning the entire atlas for every cache mip.
Each mip level is requested only within its
useful range (about 48 blocks for mip 0, doubling per level), and levels as
coarse as the low atlas are never requested, which keeps the cache from
overflowing. Sodium's chunk shader is patched at compile time to
sample resident virtual tiles in the high-resolution and translucent terrain
passes and use the
low-resolution atlas on cache misses. Mip selection comes from the UV derivatives
against the original atlas size, and sampling is clamped inside the tile rather
than falling back at tile edges. The original atlas dimensions are
supplied separately so page-table lookup remains correct after replacing the
full atlas with the smaller fallback. Empty fallback bindings are allocated
during block-atlas reload, not inside Sodium's active render pass. Shader
patching targets the known Sodium chunk shader and Minecraft item shader sources;
unsupported source variants log an error or warning and keep using the normal atlas.
Block-item shaders are separate from regular items and GUI icons, preserve glint
and translucent passes, and share the terrain cache. The configured cache
budget bounds the 2D cache allocation, further limited to one quarter of the
estimated full-atlas allocation to ensure the cache is smaller than the
texture it replaces, and by the GPU's maximum RGBA8 texture dimensions and
allocation size.

Virtual block atlas: the full block atlas is only used for its layout, because the albedo is
rebuilt from the resource pack. A pack whose stitched atlas exceeds the GPU's maximum texture size
(or `virtualAtlas=always` in the config file, which also skips the full-size upload for packs that
fit) is therefore stitched against a larger virtual limit of up to 65536x65536, and the block
atlas keeps those dimensions and sprite coordinates but only holds a 1x1 placeholder texture.
The rebuild always runs after such a reload, so there is no separate opt-in. With Iris, a layout
exceeding the sparse texture limit uses a compact near atlas when supported; if sparse or compact
allocation is unavailable, terrain stays on the reduced atlas. Only when a
layout does not fit even 65536x65536, or for other atlases, are large
sprites reduced by the smallest power of two that lets the atlas stitch. Atlas dimensions are
stored minus one in the metadata texture so 65536 fits in two bytes.

Sodium's resolved chunk-mesh sprite usage is collected into bounded UV requests and expanded
incrementally into tile requests; tile construction and upload are throttled
during the client tick, outside the terrain render pass where GPU texture
commands are prohibited. The service plans up to 512 tile keys and processes up
to 32 new tile uploads per tick, stopping after roughly 4 ms once at least one
upload has completed. Already-resident tiles are skipped when planning and again
before upload; resident requests refresh their distance priority. The
bounded pending queues prioritize nearby requests, deduplicate sprites and tiles,
and prefer finer mip levels at the same distance. New nearby requests can pass
older prefetch work rather than waiting behind it.
Enabling distance preview schedules loaded Sodium
terrain for remeshing so already-built sections also generate initial requests.
`/atlasstream cache` includes a lifetime count of completed UV quads to distinguish
an empty request queue from a capture hook that is not running. Without Iris, tiles are
requested for sections within `highResolutionDistance + hysteresis + 32` blocks and
for recently drawn block-atlas item models;
sections that come into range later are requested as the camera moves, and requests are
renewed after an atlas rebuild and after moving 16 blocks. Requests are based on chunk meshing, not
camera visibility. Requests now carry the squared distance between the meshed
section center and its captured Sodium build camera: nearby section requests
are planned first, and repeated requests for a tile upgrade its queue priority
when a nearer section uses it. Tile keys include atlas generation and mip level so
stale resource-pack tiles can be invalidated cleanly. Streamed mode releases
the full-resolution albedo atlas and replaces it with the bounded tile cache
for near terrain. With Iris, the PBR atlases are reduced as well; without
Iris there are none, so the saving is the albedo atlas. Without an active
shaderpack, other consumers of Minecraft's shared block atlas, including GUI
icons, see the low-resolution fallback while streamed mode is active.
Use `/atlasstream cache` during `/atlasstream preview-distance` to see resident-tile and
pending-request counts.
To diagnose a low-resolution held item, hold it with shaders off and use
`/atlasstream item-debug`. The report lists recently drawn first-person sprites,
their block-cache tile residency by mip level, and recent item draw paths;
it is also written to the game log. The command monitors those sprites and
cache evictions for 30 seconds to help diagnose intermittent fallback.
Use `/atlasstream perf start` and hold a steady camera view. The sample stops
automatically after 30 seconds and reports render-thread time spent building both
filtered section lists, preparing Sodium batches, submitting split draws, refreshing near-atlas
demand, handling slot pressure, and starting and finishing near sprite uploads.
It also reports sparse pages released and decommit calls during the sample.
Sections are classified once while both lists are built. Section classifications are cached
in a bounded per-region table and reused across terrain passes while the
camera remains in the same quarter-block cell. Classification uses that cell's
center, limiting its positional approximation to 0.125 blocks per axis.
The visible-section scan uses cached method handles for Sodium's iterator and
filtered-list operations to avoid reflective dispatch in the per-section loop.
Prepare timings exclude any lazy list-filtering work they trigger. These
measurements can include driver waits but exclude GPU execution and are useful for comparing repeated runs
from the same camera position and render settings; they are not total
frame-time measurements.
Run `/atlasstream build-atlases` to create the separate generated atlas pages and
manifest. Those files are written to
`config/atlasstream/generated/`; the game reports the manifest path or an
explicit error in chat and the log.

The standalone builder task is also available for development using an
extracted pack or ZIP:

The builder accepts an extracted resource-pack directory or a `.zip` pack. It
scans `assets/<namespace>/textures/block/**/*.png` and writes `atlas-high.png`,
`atlas-low.png`, and `atlas-layout.json` to the output directory. Large packs
are split into bounded atlas pages (`atlas-high-001.png`, `atlas-low-001.png`,
and so on); the manifest maps each sprite to its page:

```powershell
.\gradlew.bat buildLodAtlases `
  "-PresourcePack=C:\path\to\resource-pack.zip" `
  "-PatlasOutput=C:\path\to\generated-atlases" `
  -PlodScale=4
```

`lodScale` must be a power of two from 2 through 16 (the in-game command
currently uses 4). The low atlas dimensions
are the high atlas dimensions divided by this factor. Sprite positions and
dimensions in `atlas-layout.json` are scaled by the same factor, so each sprite
has identical normalized UV bounds in both images. A one-low-resolution-pixel
edge gutter is included around sprites when their dimensions allow it. The manifest also records animation
frame order/timing and points to extracted high/low frame images for future
runtime atlas updates. Repeating a build with unchanged inputs reuses the
generated atlas pages. Each page is currently capped at 8192 pixels per
dimension; oversized packs are split across pages instead of failing just
because the combined atlas would exceed that limit. Individual texture frames
that exceed the per-page limit are warned about and skipped; a build fails only
if no usable block textures remain or the output itself cannot be written.

</details>

## License

AtlasStream uses the **AtlasStream Personal Source License**, which allows
personal, non-commercial use and private modification while reserving public
redistribution rights. It is not an open-source license. See
[LICENSE](./LICENSE) for the full terms.

The project owner may change the license for future releases. Versions already
received remain subject to the license included with that version.
