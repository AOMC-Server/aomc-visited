## Functionality

- **Recording** (`XaeroMapWriterMixin`, `VisitedChunks`): records chunks that Xaero's World Map writes on its 
  map, per server, dimension (only dimensions named overworld, Nether and End are supported) and cave mode, since
  Xaero keeps a separate map for each cave mode (and each cave start in Layered mode). Marked "changed" if its map
  data was updated. Saved once per minute and when leaving the server.
- **Exporting** (`/visited export [dimension|record <name>] [without-packs|not-for-webmap]`, `XaeroExport`):
  exports each record in the cave mode it was recorded in (`XaeroPNGExporterMixin`), in compact areas
  (`ExportAreas`), blacks out unrecorded chunks (`TileMasker`), into `map exports/<server>/`. Records that would
  go to the same webmap map get a button each instead. `/visited` lists every record with [Export] and
  [Upload] buttons. The Nether is only recorded with cave mode off (`VisitedChunks.SURFACE_ONLY`): AOMC's Nether
  map is the roof. Offers to turn resource packs off.
- **Uploading** (`/visited upload [dimension]`, or `/visited upload <n>` from an export's [Upload] button,
  `WebmapUpload`): uploads each image, one after another, to `https://map.diorite.xyz/api/map/upload/<dim>`
  (the webmap calls the Nether `nether`; an overworld export made in cave mode goes to `overworld_caves`), then runs `/api/map/process` and reads its streamed progress. Only
  if every image was accepted and processing finished without an error naming one of them is the export
  marked as uploaded. Nothing is sent unless the player asks. Exports made with resource packs on
  (`not-for-webmap`) are never offered for upload.
- **Mark as uploaded** (`/visited mark-uploaded <n>`, a button on the export message): forgets the chunks that
  export contained, except ones Xaero has changed since. Works after a restart (`exports.txt`).
- **Old map data**: the record is kept until the player exports it or clears it. On joining after more than
  six hours offline (`OUT_OF_DATE_MS`), a reminder warns that the data may be out of date and offers
  [Export it now] and [Clear old session] (`/visited clear all`).
- **Map overlay** (`VisitedHighlighter`, `/visited overlay on|off`): green fill on new or changed areas, an
  outline around everything the next export keeps.

## Files the mod writes

Under `<game dir>/aomc_visited/`:

- `server/<address>/<namespace>/<dimension>.txt`: a record, one chunk `x,z` per line, `x,z,c` if changed.
  Cave-mode records are `<dimension>/caves.txt` (Full) and `<dimension>/caves-y<height>.txt` (Layered).
- `server/<address>/exports.txt`: the latest export of each dimension, until it's marked as uploaded.
- `settings.properties`: whether the mod is on (`/visited on|off`) and whether the overlay is shown in Xaero.

## Building

- Minecraft 26.3, Fabric, Java 25 (set `JAVA_HOME` if Gradle picks an older one).
- `./gradlew build` makes `build/libs/aomc-visited-<version>.jar`. Gradle fetches Xaero's World Map
  from Modrinth's Maven repository (`xaero_worldmap_version` in `gradle.properties`) and compiles against it.
- **Bump `version` in `gradle.properties` for every build handed out for testing**, so the jar name and
  Fabric's mod list show which build is installed.

## Releases

Increment version in `gradle.properties`, push to `main`, CI tags the commit `v<version>` and publishes a
GitHub release with the jar.

