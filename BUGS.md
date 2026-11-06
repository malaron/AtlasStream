# Potential bugs and deferred investigations

This log tracks reported behavior that has not yet been resolved or confirmed
as a defect. Update entries as evidence is gathered or issues are resolved.

## Open

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
