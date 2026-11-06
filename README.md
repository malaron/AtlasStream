# Loads of Lods

Loads of Lods is a client-side Minecraft mod prototype exploring distance-based
texture level of detail (LOD) for large resource packs.

The long-term goal is to select a high-resolution or reduced-detail block atlas
per chunk section. Atlas preparation and LOD decisions should be shared before
renderer-specific integration, so vanilla, Sodium, and Iris support can build on
the same resource data and policy.

Reported issues and deferred investigations are tracked in [BUGS.md](./BUGS.md).

## Current status

The current prototype provides:

- Minecraft 26.3 and Fabric setup.
- A client configuration file for the high-resolution distance and hysteresis.
- Renderer-independent helpers for classifying a chunk section by its distance
  from the camera.
- A resource-pack atlas builder that creates a matched atlas pair and sprite
  layout manifest.
- A chunk-terrain preview that creates a second GPU atlas in Minecraft's
  existing normalized sprite UV layout, preserving the original atlas.

`/lods preview-low` switches terrain to the reduced atlas for visual testing.
`/lods preview-distance` enables distance-based albedo LOD. Without an active
Iris shaderpack, Sodium solid and cutout sections near the camera sample
resident high-resolution tiles, and distant sections use the reduced atlas;
the full high-resolution block-atlas GPU allocation is released. Held and
dropped block-atlas item models also request nearby full-resolution tiles,
using the reduced atlas while tiles load or after eviction. Inventory icons and
other block-atlas users continue to use the reduced atlas. A resource reload restores the
full-resolution atlas. Cached inventory item images are refreshed when the
reduced atlas becomes ready, so temporary placeholder colors do not persist.
With an Iris shaderpack active, the reduced atlas (albedo
plus normal/specular LabPBR maps, averaged per channel to the same ratio) serves
distant terrain, and a separate full-size *sparse* "near atlas" (needs
`GL_ARB_sparse_texture`) serves nearby terrain, held items, entities and
particles. The near atlas commits GPU pages only for sprites used by sections
within the high-resolution distance (plus a two-chunk prefetch margin) or drawn
recently, so the full-resolution VRAM is mostly not spent. When its committed
memory exceeds `highResolutionCacheMiB`, sprites no nearby section needs
(unused for 10 seconds) are released, least recently needed first; the budget
is soft, so the current working set is never evicted. With the distance at 0,
all near memory is released. Its albedo and PBR content is
streamed from the resource pack on worker threads; PBR maps stay vanilla
(first frame only) while albedo animates, interpolated like vanilla, but only
for sprites used by sections that are in range and in view. At a distance of 0
chunks, everything uses the low atlas and nothing animates in the near atlas.
The mod registers the atlases with Iris's texture tracker so Iris still finds
them. Translucent terrain remains on the low atlas to avoid cross-atlas sorting
artifacts. Animated low-atlas sprites follow Minecraft's frame sequence
and interpolation state. Sodium's packed local section indices are decoded
and combined with their owning region coordinates before applying the
world-space distance policy. Sodium's per-region batches are rebuilt from each filtered section
list before drawing, invalidating only the active terrain pass so other
prepared passes remain cached. Its translucent pass is rebuilt from the
complete list so the experimental split does not truncate transparent geometry.

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
calculations. With Iris, translucent terrain (glass) is split by distance
so near glass also uses the near atlas.

If the compact atlas fills up, sprites without a slot wait in a queue. Under
pressure the farthest resident sprites are evicted, matched by slot size, to
make room for the nearest waiting ones, and evicted-but-still-needed sprites
rejoin the queue behind nearer ones. Sections whose sprites are not all resident
are drawn from the reduced atlas. A large queue of far sprites is normal when
the compact atlas is smaller than the view needs. To test this, launch with
`-Dloadsoflods.compactSize=8192` (texels, default 16384) and watch the F3
"Near sparse atlas" line and the `Slot pressure:` debug log lines.

### Reduced-atlas disk cache

Building the reduced atlas (and its PBR maps) takes tens of seconds for large
packs, so the result is cached in `cache/loads-of-lods/<key>.bin` under the game
directory. The key is a hash of the mod build, loaded mods, atlas layout,
settings and the ordered resource pack stack, including each pack's size and
modification time. Changing a pack, its order or any mod changes the key, so a
fresh atlas is built instead of reusing a stale one. Packs that cannot be
fingerprinted (for example server packs) skip the cache. Entries are checksummed
(bad ones are deleted), the least recently used are pruned beyond 3 GiB, and
animation data is always rebuilt from the packs. On a cache hit the log shows
"Loaded the reduced atlas from the disk cache".

A second, per-pack cache (`cache/loads-of-lods/tiles/`) stores each reduced
sprite tile under the pack that provided its texture (keyed by that pack's size
and modification time, or the mod version). When the pack stack changes, only
sprites whose providing pack changed are decoded again; the rest are copied from
the tile files and the mip levels are rebuilt. Animated sprites and sprites from
packs that cannot be fingerprinted are always decoded. The log line
"Tile cache (...)" shows how many sprites came from cached tiles.

## Configuration

On first launch the mod creates `config/loads-of-lods.properties`:

```properties
highResolutionDistance=128.0
hysteresis=16.0
highResolutionCacheMiB=1024
downscaleFactor=4
```

`downscaleFactor` (2, 4 or 8) is the per-axis reduction of the low-resolution
atlas, including Iris's normal/specular atlases. Larger values save more VRAM;
1:2 uses roughly 4x the atlas VRAM of 1:4. It applies the next time the
distance preview is built (it is rebuilt automatically after a resource reload, e.g. F3+T), as does
`debugTintLowResolution` (tints low-res terrain magenta for testing).
`highResolutionDistance` is stored in blocks but edited in chunks in the
config screen; 0 disables near terrain entirely.
`enableOnLaunch` (default `true`) builds the reduced atlas automatically after
the game starts. With Iris shaders enabled, the CPU build (including the reduced
PBR images) starts right after the launch resource reload, Iris's full-size block
PBR load is skipped, and the GPU activation is parked until you join a world and
Iris has created its pipeline. Without Iris shaders it activates once the first
terrain shader has compiled. Set it to `false` to keep using `/lods preview-distance` manually.

All settings can be edited in game via Mod Menu (21.0.0 for 26.3) when Yet
Another Config Lib is installed; both are optional. `/lods vram` prints a VRAM
report, and an F3 debug entry shows the current mode, factor and atlas state.

Distances are in blocks. A section is initially classified as high resolution
when its bounding box is within the configured distance. Once classified, it
switches to low resolution only beyond `distance + hysteresis`, and switches
back only inside `distance - hysteresis`. This dead band avoids repeated
switches near the boundary. `highResolutionCacheMiB` sets the target budget
for the planned high-resolution tile cache; the supported range is 64-16384
MiB. The current renderer still uses the fully resident vanilla
high-resolution atlas, so this setting is a target only and does not yet
change GPU allocation.

## Development

- Minecraft: 26.3
- Loader: Fabric
- Java: 25
- Fabric API: see `gradle.properties`

Build with `.\gradlew.bat build` on Windows. To test against the resource packs
currently loaded by Minecraft, install the resulting mod jar, enter a world,
and run `/lods preview-low` to build and select a second GPU block atlas with
the same normalized sprite UV layout as Minecraft's atlas. The low atlas is
physically downscaled by up to 4x per dimension (16x fewer base-level texels);
the scale is reduced if the stitched sprite layout is not aligned for exact
UV preservation. Run
`/lods preview-high` to switch back to the untouched original atlas without
reloading resource packs after `/lods preview-low`; the generated low atlas
stays resident for a quick switch back. This low-only preview does not reduce
total VRAM usage. `/lods preview-distance` instead activates streamed mode and
releases the original full-resolution block atlas. A resource reload restores
the original atlas and then rebuilds streamed mode automatically. The low atlas and its CPU
mip chain are capped at 1 GiB.
Mip levels follow the reduced atlas and are generated within each sprite's
padded bounds to avoid sampling neighboring sprites.
A resource-pack reload discards the cached low atlas. If the distance preview
was on, it is rebuilt automatically about a second after the reload (this
also picks up a changed `downscaleFactor` or debug tint), so no command is
needed. A manual `/lods preview-distance` is only needed if that rebuild is
skipped (see `latest.log`).
Run `/lods preview-distance` to assign solid and cutout Sodium chunk sections
to the high-resolution tile cache or low-resolution fallback using the
configured distance and hysteresis. Translucent terrain stays on the low
fallback in streamed mode to avoid cross-atlas sorting artifacts. With Iris
present, albedo LOD still works. In distance-preview mode Iris's PBR atlases
are reduced alongside the albedo atlas; in low-preview mode the original
full-resolution normal/specular maps stay bound. Iris's active shader samplers
are refreshed at high/low draw boundaries.
Iris shaderpack terrain currently uses a hard per-section albedo switch.
Generic shaderpack crossfading is not
enabled because Iris's current shader source hook does not reliably preserve
injected declarations through its compilation pipeline.
Streamed mode releases the full-resolution albedo atlas.
Animated albedo sprites follow
Minecraft's frame sequence and interpolation state; animation frame caches
share the CPU image safety limit. Previously blended low-atlas animation frames
are reused in a separate cache of up to 32 MiB of CPU memory, cleared on atlas
reload. `/lods perf start` reports how many blends were built or reused during
its CPU sample. The repeating Nether portal sprite's
opposite edges are matched at each low-atlas mip level to reduce visible
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
The rebuild always runs after such a reload, so there is no separate opt-in. With Iris the sparse
near atlas is skipped when the layout exceeds the sparse texture limit and terrain uses the
reduced atlas. Only when a layout does not fit even 65536x65536, or for other atlases, are large
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
`/lods cache` includes a lifetime count of completed UV quads to distinguish
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
Iris there are none, so the saving is the albedo atlas. Other consumers of Minecraft's shared block
atlas, including GUI icons, see the low-resolution fallback until resource packs are reloaded.
Use `/lods cache` during `/lods preview-distance` to see resident-tile and
pending-request counts.
To diagnose a low-resolution held item, hold it with shaders off and use
`/lods item-debug`. The report lists recently drawn first-person sprites,
their block-cache tile residency by mip level, and recent item draw paths;
it is also written to the game log. The command monitors those sprites and
cache evictions for 30 seconds to help diagnose intermittent fallback.
Use `/lods perf start` and hold a steady camera view. The sample stops
automatically after 30 seconds and reports CPU time spent building both
filtered section lists, preparing Sodium batches, and submitting the split draws. Sections are
classified once while both lists are built. Section classifications are cached
in a bounded per-region table and reused across terrain passes while the
camera remains in the same quarter-block cell. Classification uses that cell's
center, limiting its positional approximation to 0.125 blocks per axis.
The visible-section scan uses cached method handles for Sodium's iterator and
filtered-list operations to avoid reflective dispatch in the per-section loop.
Prepare timings exclude any lazy list-filtering work they trigger. These
measurements exclude GPU execution and are useful for comparing repeated runs
from the same camera position and render settings; they are not total
frame-time measurements.
Run `/lods build-atlases` to create the separate generated atlas pages and
manifest. Those files are written to
`config/loads-of-lods/generated/`; the game reports the manifest path or an
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

## License

This project currently uses the CC0-1.0 license.
