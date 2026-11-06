# Potential bugs and deferred investigations

This log tracks reported behavior that has not yet been resolved or confirmed
as a defect. Update entries as evidence is gathered or issues are resolved.

## Open

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
- **Diagnostic evidence:** During the latest `/lods item-debug` recording,
  the cache was full, pending tile requests rose to roughly 7,900, and
  evictions and uploads continued during movement. The monitored held mossy
  cobblestone tiles stayed resident; the recording did not identify the
  delayed terrain sprite or its specific bottleneck.
- **Next investigation:** If the delay becomes disruptive, capture the
  affected terrain sprite and measure its progress through request planning,
  queueing, tile building, and upload. Distinguish nearby demand from prefetch,
  and check for stationary flicker when testing changes.
