# Potential bugs and deferred investigations

This log tracks reported behavior that has not yet been resolved or confirmed
as a defect. Update entries as evidence is gathered or issues are resolved.

## Open

### Oversized atlas fails to activate with mipmaps disabled

- **Status:** Confirmed; restore Minecraft's mipmap levels to 4 as a workaround.
- **Observed:** With Optimum Realism 1024 and Iris, changing Minecraft's
  mipmap levels from 4 to 0 caused a long reload and left the scene without
  normal textures. Restoring level 4 recovered rendering.
- **Diagnostic evidence:** The zero-mipmap stitch selected a 1:2 reduction for
  a 65536x32768 layout, producing a 32768x16384 reduced image. Uploading the
  2 GiB image failed with an integer overflow in `NativeImage.getPixelBytes`;
  the log subsequently reported OpenGL framebuffer errors. The source layout
  was virtual, so a failed preview could not simply use a full GPU atlas.
- **Next investigation:** Support safe reduction and compact remapping when
  mipmaps are disabled, including sprite alignment, upload size limits, and
  a usable fallback for virtual atlases. Do not assume the overflow is the
  only obstacle.

### Limited near detail with a 1024x resource pack

- **Status:** Confirmed compact-atlas slot pressure; residency-cache
  invalidation fix under in-game testing; jump blur not isolated.
- **Observed:** With Optimum Realism 1024 and Iris, increasing the
  high-resolution distance from 1 to 2 chunks did not visibly expand the
  sharp area. Ground looked blurrier while jumping, but did not turn magenta
  with the reduced-atlas debug tint enabled.
- **Diagnostic evidence:** The 16384x16384 compact near atlas reported full.
  At one view F3 showed 247 sprites resident, 217 waiting, 0 pending, 247
  evicted, and 3883/4160 MiB committed. The cache budget does not enlarge the
  compact texture's slot capacity. The untinted jump blur is not evidence of
  a switch to the reduced atlas; filtering or shader effects remain possible.
  The grass top uses 16 repeating 1024x1024 CTM tiles and separate normal and
  specular maps. The user reported the jump blur with shaders on but not off;
  disabling Eclipse parallax and TAA did not remove it. Shader-off testing
  showed a nearby magenta boundary and a full tile cache with 303 UV quads
  pending. In that path, sufficiently coarse mip levels also sample the tinted
  reduced atlas, so the tint boundary does not by itself establish the
  world-distance boundary.
  Cached Iris section classifications also did not track changes in compact
  sprite residency or section sprite usage. Both cache layers now invalidate
  on those changes; this does not add compact-atlas capacity. In the first
  in-game test of that fix, the user reported marginal improvement but a
  nearby section still did not update. The compact atlas again logged full;
  F3 showed 255 resident, 183 waiting, 0 pending, 497 evicted, 0 failed, and
  3929.1/4160 MiB committed. After restarting with a build that only added
  diagnostics, the user could no longer reproduce the nearby magenta section,
  but noticed a possible small FPS decrease. The atlas still logged full; the
  change in behavior and performance has not been isolated. At a later
  reproduction, `/atlasstream near-debug` reported 109 recorded sprites in the
  camera section, eight missing and waiting for 1088x1088 slots. For each,
  there were no free slots and 195 compatible resident sprites, but none
  eligible for eviction under the eight-block priority margin. The targeted
  block was in that section. A 30-second CPU sample reported 157.3 ms across
  4764 list scans and 143.6 ms across 300 near-atlas refreshes; this has no
  comparable baseline and excludes GPU work.
  Prioritizing the camera section allowed the previously stuck area to
  eventually resolve, but the user still observed delayed updates and
  occasional magenta flicker while approaching. A further change prioritizes
  one section ahead in the direction of movement and services slot retries
  ahead of ordinary requests. The user reported faster loading with that
  build, but FPS sometimes fell into the teens while walking; the cause
  and whether flicker persists have not been established. A 30-second walking
  sample reported 2537.3 ms across 301 near-atlas refreshes, versus 143.6 ms
  across 300 refreshes in the earlier stationary sample. Different conditions
  and builds mean these are not a controlled comparison. A second walking
  sample measured 1616.6 ms across 286 refreshes (peak 24.7 ms); slot pressure
  accounted for 1507.9 ms of that, with 102 evictions. Sprite start and
  finish/upload took another 571.4 ms and 477.8 ms respectively. These
  render-thread timings can include driver waits and exclude GPU execution.
  In a subsequent 90-second Java Flight Recorder capture, the user saw FPS
  drop into the teens but no flicker. Many render-thread samples were in item
  feature geometry preparation, including Iris shadow rendering. Samples in
  the near-atlas pressure path included sparse-page decommit calls; sampling
  does not establish exact time, causation, or GPU cost. One 195.8 ms GC
  pause was attributed to `System.gc()`, and the other recorded GC pauses
  were 13.2 ms or shorter. At a later stationary view the user reported
  roughly 33 FPS facing a detailed test area and 80 FPS facing away, with
  F3 showing E: 70/100. These observations do not identify which visible
  geometry, entities, or shader effects account for the difference. Reducing
  entity distance from 100% to 50% raised FPS above 60 and changed F3 to
  E: 7/9; the user considers that setting too low. In two stationary
  30-second profiles at the same view, Minecraft's item-feature preparation
  appeared in 1573 render-thread samples at 100%, versus 337 at 50%.
  AtlasStream' sprite-use tracking appeared in 46 and 9 samples respectively.
  These are samples, not exact timings or proof of a specific entity type.
  The user later reported that magenta terrain was again not keeping up.
  The active compact atlas had logged full; the cause of this recurrence is
  now identified for the targeted section: one 192x192 fire sprite was
  waiting with no exactly matching free slot or resident victim, leaving
  all 94 recorded sprites' section on the reduced atlas. The allocator has
  been changed to split larger freed rectangles and merge compatible adjacent
  rectangles. In an initial test with that build, the user could not
  reproduce the stuck section; the atlas still logged full. This is
  encouraging but does not establish that the issue is fully resolved.
  A later 30-second ground-walking sample at 100% entity distance had no
  stuck magenta but some slow updates. The user separately reported FPS in
  the 30s while flying. The walking sample measured 2395.1 ms
  across 300 refreshes (peak 28.4 ms), including 2278.2 ms of slot-pressure
  work and 147 evictions; sprite start and finish/upload took 776.5 ms and
  704.1 ms respectively. Different routes and conditions limit comparisons
  with the earlier walking sample. Adjacent unreferenced sparse pages are now
  decommitted in rectangular batches, reducing OpenGL calls without retaining
  extra pages; in-game performance and visual behavior await verification.
  The performance sample now reports pages released and decommit call counts.
  In a subsequent test the user reported a nearby magenta section remaining
  for over a minute and hitches when sections sharpened. The camera section
  had all 104 recorded sprites resident; the adjacent targeted section had
  two of 122 missing. Both 1088x1088 sprites had 195 fitting residents but
  no eligible victims because of priority ties. An unverified change now
  prioritizes sections within four blocks of the camera, including neighbors.
  Per-sprite start/upload peak timings were added to help investigate hitches.
  In an initial test with that change, the user did not see magenta areas
  remain stuck, but reported small lag spikes as areas became high resolution.
  A subsequent 30-second sample with hitches reported 650.5 ms across 300
  refreshes, including 501.3 ms of slot-pressure handling and 225 evictions.
  It released 54000 pages in 2121 decommit calls. Sprite start took 1340.5 ms
  across 295 calls (peak 12.5 ms); finish/upload took 1170.1 ms across 225
  calls (peak 13.1 ms). These timings do not prove which work caused the
  hitches. Compact-mode sprite start and upload bursts now each have a
  4 ms per-tick budget with at least one operation for progress; this may
  delay sharpening and does not cap the time for an individual operation.
  The user reported that the change appeared smoother but textures still
  sharpened too late for the desired nearby experience. The saved settings
  were a 32-block high-resolution distance and 8-block hysteresis; the user
  confirmed the eight-block figure refers to hysteresis. An existing low
  section re-enters high resolution inside 24 blocks when its sprites are
  resident; full atlas pressure can still prevent that transition.
  The compact queue now prioritizes new requests alongside retries and orders
  ready uploads by current section-AABB distance. A synthetic moving-camera
  test covers a section completing ahead of an older distant backlog, but
  assumes one sprite per tick and available capacity; actual streaming
  latency, hitches and atlas pressure remain to be verified in-game.
  The user tested that build and reported getting halfway through a magenta
  section before it changed. The installed jar matched the build, and the
  log again reported the 16384x16384 compact atlas full. The log does not
  identify which missing sprites or pipeline stage delayed that section.
  Further queue tuning alone cannot remove the whole-section residency gate.
  The user also reports that changing a daylight sensor to a texture not in
  high-resolution memory causes their entire section to switch to low
  resolution until that texture loads. The residency gate can explain this;
  the precise missing sprite was not captured.
  The user approved investigating an opt-in mixed-resolution compact-atlas
  prototype: resident sprites remain sharp and missing sprites use a packed
  reduced fallback, instead of demoting their whole section. An opt-in
  `-Datlasstream.hybridCompact=true` prototype now reserves the fallback
  separately from streamed slots at every sparse mip level, uploads albedo and
  reduced PBR with matching UVs, builds a complete fallback mip chain, and
  copies animated albedo updates. Section-residency and layout tests pass.
  In the first in-game test, the user reported that changing the daylight
  sensor now makes only the sensor temporarily magenta, rather than the
  entire section. The installed jar matched the build, the log confirmed
  hybrid activation and 511 MiB committed for its fallback, and the compact
  atlas subsequently filled. This does not establish that nearby misses are
  rare, or verify midpoint coordinates, animations, PBR or walking latency.
  The user later showed a TNT minecart immediately nearby with its TNT stuck
  magenta. Minecraft submits minecart TNT as a block model; Fabric can submit
  that model as a mesh, which previously only had sprite tracking enabled for
  item frames. That mesh tracking has been extended to the shared TNT
  submission path used by minecarts and primed TNT. The cause and fix await
  in-game verification; slot pressure remains another possible contributor.
  The user also observed a furnace minecart with magenta faces from the front
  while its back appeared correct. Unlike TNT, ordinary minecart display blocks
  use the base minecart submission path, which was not covered by the TNT
  scope. Mesh tracking now wraps the shared minecart contents submission;
  the user reported that the furnace looked better after testing that change.
  At 1:8 the current 8192x4096 fallback consumes 12.5% of the 16384x16384
  compact atlas area before alignment and roughly 480 MiB for three layers
  with its two existing mips; additional mips and page alignment cost more.
  The proposed page-aligned partition fits 186 uniform 1088x1088 slots,
  versus 225 in an otherwise empty compact atlas. Actual capacity depends
  on mixed sprite sizes and fragmentation; a prior diagnostic observed 195
  fitting residents, so this tradeoff can increase high-resolution misses.

- **Armor stand equipment appears coarse:** The user reported visibly large
  pixels on nearby armor without the magenta low-atlas tint. Vanilla renders
  equipment using standalone textures rather than the block atlas. The active
  Optimum Realism R4.2.0 1024x pack contains 512x512 diamond humanoid and
  leggings textures; the 256x and 512x editions also contain 512x512 files
  for those layers. The block-atlas reduction therefore does not explain the
  armor's lack of tint, and the pack's source resolution is a possible cause
  of the coarse detail, not proof that the runtime renders the texture unchanged.
  The user also observed coarse detail with shaders off. The source albedo is
  smooth; the normal map supplies much of the material detail seen with PBR.
  Vanilla's armor-stand model uses a 64x32 UV layout, with an 8x12 front
  torso face: on this pack's 512x512 sheet that face covers 64x192 source
  texels, not 1024x1024. This assumes the vanilla model and texture are
  actually selected at runtime; installed entity rendering mods or runtime
  texture changes have not been ruled out.

- **Plant transparency / card artifacts:** The user reported visible
  rectangular backing on flowers and cave vines with shaders enabled, and
  supplied a screenshot of poppies in front of foliage. The active 1024x
  pack's poppy and cave-vine albedo images contain substantial fully
  transparent areas, so opaque source backgrounds do not explain the issue.
  The screenshot alone does not distinguish an alpha-cutout issue, shader or
  PBR effect, or compact-atlas interaction. The user reports transparency looks
  correct with shaders off, but corrected their earlier observation: the backing
  remains with POM disabled and was merely harder to see. The user reports
  artifacts with Kappa and Eclipse but not Photon, and describes the
  rectangles as outlines of the plants' intersecting quads rather than
  shadows on the background. The same issue remains when the experimental
  hybrid compact atlas is disabled; the log confirms regular compact mode.
  The user reports seeing this only with the 1024x pack, not its 256x or
  512x editions, with Kappa and AtlasStream enabled without hybrid mode.
  Logs show sparse layout-preserving atlases for the smaller editions and
  the compact remapped atlas for 1024x. The 1024x plant albedo files contain
  partially transparent pixels, while the corresponding 256x and 512x files
  use binary alpha. Minecraft's cutout mip generation also adds a small alpha
  bias to transparent pixels. A CPU simulation of the vanilla algorithm for
  poppy and cave vines produced corner alpha values of 6, 12, 17 and 22-23
  out of 255 at mip levels 1-4. Iris substitutes Kappa's alpha-test reference
  with a dynamic uniform, so its effective runtime cutoff cannot be inferred
  from Kappa's fallback constant. Kappa's terrain shader
  has different alpha-test behavior for solid and non-solid programs, with or
  without POM; which program actually draws the affected plants is unverified.
  The cause could be in the pack, shader, rendering integration, or compact
  atlas. The user ran `/atlasstream sprite-alpha minecraft:block/poppy` in regular
  compact mode with Kappa: the sprite was resident and all five sampled GPU
  alpha values matched their CPU mips at each level. Four transparent-area
  samples were 0, 6, 12, 17 and 22 across levels 0-4, and an interior sample
  was 255, 245, 236, 227 and 219. This rules out an upload mismatch at those
  sampled points only; it does not measure shader-filtered alpha, UV mapping
  across a quad, render-pass selection, or the effective alpha-test state.
  `/atlasstream terrain-alpha` now samples the active OpenGL program and alpha-test
  uniforms immediately after Sodium terrain batch draws. The Kappa capture
  reported alphaTestRef 0.5 for non-translucent cutout passes and 0.1 for a
  translucent pass, in both high- and low-resolution draws; the diagnostic
  cannot identify the contents of a batch. Minecraft 26.3 classifies baked
  block faces using the source UV region's transparency and selects the
  translucent layer for partial alpha. The active 1024x pack does not override
  the vanilla poppy model, whose cross faces each use the entire texture. Its
  partial alpha predicts translucent vanilla baked quads. Sodium maps the
  quad layer to a terrain material at mesh build time. The user reports that
  `/atlasstream plant-pass cutout` eliminated the card artifact on both poppies and
  dandelions. Logs confirm each recorded quad changed from TRANSLUCENT to
  CUTOUT. This establishes a useful workaround and implicates the pass change,
  but not its precise cause; it also changes blending, shader, and atlas paths.
  The user later observed colored flower-shaped shadows and no wind animation
  for poppies in both regular and hybrid modes. In Kappa, the targeted cutout
  experiment removed the colored shadow and restored wind; normal mode brought
  the colored shadow back. A flower-colored wireframe cube was reported when
  targeting a poppy in cutout mode and disappeared on looking away; whether
  this is the block selection outline remains unverified. The user reports it
  has the same shape and behavior as a normal selection outline, and is not
  concerned if it is only the selection outline. The user reports the broader
  flower-pass test looks good with Kappa and hybrid enabled; logs show it
  matched poppy, oxeye daisy and red tulip. The correction now defaults on
  for translucent quads of blocks in Minecraft's `small_flowers` tag;
  `/atlasstream flower-pass normal` disables it for the session and
  `/atlasstream flower-pass cutout` re-enables it. It is not a general plant
  classification, and it may remove intentionally soft transparency.
  The default-on behavior and other packs remain to be verified in-game.
  The user also reports improved grass-block blur after the experiment. The
  short-grass quad recorded earlier was CUTOUT and unchanged. The original
  diagnostic logged only the first grass-block quad, which was SOLID.
  The user noted that grass blocks have raised 3D grass. The active pack's
  grass-block multipart model optionally adds one of two horizontal overlay
  models above the block. Their cloverleaf_overlay texture has 135,074 partial
  alpha pixels out of 1,048,576, making a translucent overlay quad plausible.
  The updated diagnostic confirmed that grass blocks have SOLID, CUTOUT and
  TRANSLUCENT quads; only the last changed to CUTOUT during the experiment
  and returned to TRANSLUCENT in normal mode. The user confirmed the blur
  returned after switching back to normal. The log does not identify which
  sprite produced each quad, and other sources of geometry are not ruled out.
  Further inspection found Kappa's translucent terrain program includes
  `gbuffers_translucent.fsh`, which discards below alpha 0.02. The sampled
  poppy's fully transparent corners become 6/255 (~0.0235) at mip 1, already
  above that cutoff, and increase at higher mips. This is a concrete candidate
  for surviving background fragments; actual shader sampling and additional
  transformations still need verification. The earlier uniform capture
  alone did not reveal this hard-coded threshold. Photon and Eclipse use
  different translucent shaders; the basis of their different results is not
  yet established. Repeating the global mipmap-zero test reproduced the
  previously documented oversized-atlas failure; restoring mipmaps to 4
  restored the near atlas. The user reports the poppy backing remained with
  the near-albedo-only base-mip sampling experiment enabled. Subsequent
  diagnostics showed the draw still used max mip 4: the renderer resets the
  texture's mip bounds from its view, so this test was inconclusive. The
  corrected experiment uses a one-mip terrain texture view: diagnostics
  confirmed max mip 0 during high-resolution translucent draws, and the user
  reported the poppy backing disappeared. This implicates mip sampling, though
  it does not prove which filtered fragments caused the artifact.
  An initial near-albedo correction cleared alpha only where the entire source
  footprint was transparent. The user reported flowers improved, glass and
  water looked normal, but grass remained unchanged; poppy GPU readback
  confirmed corrected zero alpha at the sampled backgrounds. Grass improved
  when the corrected base-mip experiment was enabled, so mip effects remain
  plausible there. The near correction now instead averages source alpha at
  each mip, removing cutout-coverage scaling and bias from partial as well as
  fully transparent areas. It applies only to automatic-mip sprites containing
  both zero and partial alpha, preserves RGB and PBR maps, and leaves explicit
  mip strategies alone. The reduced atlas already averages source alpha. The
  correction can be disabled at launch with
  `-Datlasstream.preserveTransparentMips=false`. With the revised correction,
  the user reports flowers look correct and glass and water look normal, but
  the grass blur is unchanged. Kappa's translucent pass writes material and
  normal data and its composite shader can refract translucent surfaces except
  material ID 100; the pack's grass overlay is a plausible contributor, but
  its actual material ID is not yet verified. The user reports that disabling
  Kappa's refraction removes the grass blur. This points to a separate shader
  interaction rather than the flower mip-alpha problem. The 1024x pack's
  cloverleaf overlay has 135,074 partial-alpha pixels; its 256x and 512x
  editions use binary alpha. In Kappa's translucent vertex shader, ordinary
  terrain is assigned material ID 101 while its composite shader refracts
  translucent material IDs other than 100. Actual shader material assignment
  for the overlay has not been captured. A generic automatic distinction
  between an intentionally refractive translucent overlay and non-refractive
  soft foliage is not provided by the texture's alpha alone; a blanket
  cutout override would destroy legitimate transparency.
  The default-on overlay cutout heuristic targets only
  upward horizontal translucent quads just above solid blocks with bounded
  footprints, without relying on pack or block names. The log confirmed matches
  for grass-block overlays at both 1.03125 and 1.05 block heights. The user
  reports that the grass blur disappeared with the experiment, returned when
  disabled, and overlay edges, glass and water looked okay. It is a geometry
  heuristic rather than proof of intent, and it sacrifices soft transparency
  for matched quads. `/atlasstream overlay-pass normal` disables it for the current
  session, and `/atlasstream overlay-pass cutout` re-enables it; it defaults to
  enabled after restart. Other packs and matching translucent effects remain
  unverified.
- **Next investigation:** Identify the entities responsible for item-feature
  rendering and compare with the mod disabled if feasible. Profile
  slot-pressure handling separately before changing eviction behavior.
  Check for flicker while crossing section boundaries.
  Slot-size fragmentation and capacity can still prevent a section resolving.
  Compare jump blur separately against the same pack and shader without the
  mod where feasible.

### FPS drops in a complex world area with Kappa

- **Status:** Performance investigation deferred; cause not isolated.
- **Observed:** With Kappa and Patrix 256, FPS dropped into the 30s while
  walking through an area containing a cow pen, a water item stream, and nearby
  storage. At one stationary viewpoint, facing the pen gave about 60 FPS and
  looking away gave about 70 FPS; roughly 80 entities were reported.
- **Mitigation:** Reducing Minecraft's entity distance from 200% to 100%
  increased FPS at one comparable view from about 50 to 60. A later walking
  test reached a low of about 47 FPS; the user did not notice missing distant
  entities. Walking results are not directly controlled comparisons.
- **Diagnostic evidence:** A render-thread profile showed low-atlas animation
  blending and near-atlas sprite refresh among the sampled work. Both paths
  were optimized, but the user did not observe a clear FPS improvement from
  the changes alone. The render thread also appeared in graphics
  synchronization waits; GPU utilization and CPU samples did not establish
  the limiting stage.
- **Next investigation:** Compare repeatable routes and views, and isolate
  entity, block-entity, terrain, and shader shadow costs before changing
  quality settings or attributing the slowdown to a particular component.

### Chest and hopper reflections change abruptly with view

- **Status:** Unconfirmed; deferred pending a comparison without the mod.
- **Observed:** With Kappa and Photon, chest and hopper surfaces visibly change
  while approaching; one repeatable transition was around 6-7 blocks away.
  Nearby terrain and a crafter did not visibly change, and the effect was not
  observed with shaders off.
- **Diagnostic evidence:** With Kappa, the change disappeared when reflections
  were disabled. With reflections restored, it could also be reproduced by
  turning the camera while standing still. This suggests a view-dependent
  reflection effect, but does not establish whether the mod is responsible.
- **Next investigation:** Compare the same reflection behavior without the
  mod using a resource pack that can load within the GPU's atlas size limit.
  If it occurs only with the mod, investigate the relevant shader and material
  rendering paths.

### Occasional delayed texture sharpening after rapid movement

- **Status:** Deferred; monitor for recurrence.
- **Observed:** After moving quickly, a nearby terrain texture occasionally
  remains reduced-resolution before sharpening. One earlier occurrence took
  roughly five seconds; the latest captured occurrence affected one texture for
  about one second. Stationary texture flickering had stopped in the user's
  tests.
- **Test conditions:** Shaders off, Patrix resource pack, 2 GiB
  high-resolution cache, 32-block high-resolution distance, 16-block
  hysteresis, and 1:8 downscaling.
- **Diagnostic evidence:** During the latest `/atlasstream item-debug` recording,
  the cache was full, pending tile requests rose to roughly 7,900, and
  evictions and uploads continued during movement. The monitored held mossy
  cobblestone tiles stayed resident; the recording did not identify the
  delayed terrain sprite or its specific bottleneck.
- **Next investigation:** If the delay becomes disruptive, capture the
  affected terrain sprite and measure its progress through request planning,
  queueing, tile building, and upload. Distinguish nearby demand from prefetch,
  and check for stationary flicker when testing changes.
