# Loads of Lods

Loads of Lods is a client-side Minecraft mod prototype exploring distance-based
texture level of detail (LOD) for large resource packs.

The long-term goal is to select a high-resolution or reduced-detail block atlas
per chunk section. Atlas preparation and LOD decisions should be shared before
renderer-specific integration, so vanilla, Sodium, and Iris support can build on
the same resource data and policy.

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
the full high-resolution block-atlas GPU allocation is released. Because
Minecraft shares this atlas with other block-atlas users, they also see the
low-resolution fallback in this mode. Reload resource packs to restore the
full-resolution atlas. With an Iris shaderpack active, terrain consistently
uses the reduced albedo atlas instead of splitting sections between high and
low resolutions. This avoids the visible hard boundary and the extra two-pass
draw overhead. Iris normal (`_n`) and specular (`_s`) LabPBR maps are reduced
to the same ratio with per-channel averaging (normals are renormalized; alpha,
which carries POM height and emission, is averaged independently rather than
used as a weight). Animated PBR sprites use their first frame. The mod registers
the reduced atlas with Iris's texture
tracker so Iris still finds them. The full-resolution albedo atlas is released
in this mode to save its VRAM, and no high-resolution tile cache is used (only
its minimum allocation); nearby terrain also remains reduced
until a reliable shader-level transition is available. Translucent terrain
remains on the low atlas to avoid cross-atlas sorting artifacts when distance
LOD is active. Animated low-atlas sprites follow Minecraft's frame sequence
and interpolation state. Sodium's packed local section indices are decoded
and combined with their owning region coordinates before applying the
world-space distance policy. Sodium's per-region batches are rebuilt from each filtered section
list before drawing, invalidating only the active terrain pass so other
prepared passes remain cached. Its translucent pass is rebuilt from the
complete list so the experimental split does not truncate transparent geometry.

## Configuration

On first launch the mod creates `config/loads-of-lods.properties`:

```properties
highResolutionDistance=128.0
hysteresis=16.0
highResolutionCacheMiB=1024
```

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
releases the original full-resolution block atlas. Reload resource packs to
leave streamed mode and restore the original atlas. The low atlas and its CPU
mip chain are capped at 1 GiB.
Mip levels follow the reduced atlas and are generated within each sprite's
padded bounds to avoid sampling neighboring sprites.
A resource-pack reload discards the cached low atlas;
run `/lods preview-distance` again after reloading packs to rebuild the
distance-preview atlas.
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
share the 1 GiB CPU image safety limit. The repeating Nether portal sprite's
opposite edges are matched at each low-atlas mip level to reduce visible
seams between adjacent portal blocks after downscaling.
The byte-bounded LRU eviction primitive and CPU-side virtual-UV-to-tile address
model are unit-tested. The GPU page-table texture and bounded 2D tile-atlas
cache upload mip-complete tiles, update residency entries, and clear entries
on LRU eviction. This uses a 2D texture because Minecraft's current RenderPearl
backend does not support texture arrays. Tiles are composed from Minecraft's
stitched sprite mipmaps; tiles touching animated sprites are rejected until
animated-tile synchronization is implemented and continue sampling the
low-resolution fallback. Sodium's chunk shader is patched at compile time to
sample resident virtual tiles in the high-resolution terrain pass and use the
low-resolution atlas on cache misses. The original atlas dimensions are
supplied separately so page-table lookup remains correct after replacing the
full atlas with the smaller fallback. Empty fallback bindings are allocated
during block-atlas reload, not inside Sodium's active render pass. Shader
patching is limited to the known Sodium chunk shader source; unsupported source
variants log a warning and keep using the normal atlas. The configured cache
budget bounds the 2D cache allocation, further limited to one quarter of the
estimated full-atlas allocation to ensure the cache is smaller than the
texture it replaces, and by the GPU's maximum RGBA8 texture dimensions and
allocation size.
Sodium's resolved chunk-mesh sprite usage is collected into bounded UV requests and expanded
incrementally into tile requests; tile construction and upload are throttled
during the client tick, outside the terrain render pass where GPU texture
commands are prohibited. The service plans up to 512 tile keys and processes up
to four tile uploads per tick, stopping after roughly 4 ms of tile work once at
least one tile has been attempted. Already-resident tiles are skipped when
planning and again before upload; resident requests refresh LRU recency. The
bounded pending queue prioritizes finer
atlas mip levels, deduplicates keys, and evicts coarse requests first when full.
Enabling distance preview schedules loaded Sodium
terrain for remeshing so already-built sections also generate initial requests.
`/lods cache` includes a lifetime count of completed UV quads to distinguish
an empty request queue from a capture hook that is not running. Tiles that touch animated sprites are skipped and
continue sampling the low-resolution fallback. Requests are based on chunk meshing, not
camera visibility. Requests now carry the squared distance between the meshed
section center and its captured Sodium build camera: nearby section requests
are planned first, and repeated requests for a tile upgrade its queue priority
when a nearer section uses it. Fine mip levels remain higher priority than
distance within each mip level. Tile keys include atlas generation and mip level so
stale resource-pack tiles can be invalidated cleanly. Streamed mode releases
the full-resolution albedo atlas and replaces it with the bounded tile cache
for near terrain. With Iris, the PBR atlases are reduced as well; without
Iris there are none, so the saving is the albedo atlas. Other consumers of Minecraft's shared block
atlas see the low-resolution fallback until resource packs are reloaded.
Use `/lods cache` during `/lods preview-distance` to see resident-tile and
pending-request counts.
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
