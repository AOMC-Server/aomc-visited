## Functionality

- **Recording** (`XaeroMapWriterMixin`, `VisitedChunks`): records chunks that Xaero's World Map writes on its
  map, per server, dimension (overworld, Nether and End only) and cave mode, since Xaero keeps a map per cave
  mode. Marked "changed" if its map data was updated. Saved once per minute and when leaving the server.
- **Exporting** (`/visited export [dimension|record <name>] [without-packs|not-for-webmap]`, `XaeroExport`):
  exports each record in the cave mode it was recorded in (`XaeroPNGExporterMixin`), in compact areas
  (`ExportAreas`), blacks out unrecorded chunks (`TileMasker`), into `map exports/<server>/`. Records that would
  go to the same webmap map get a button each instead. Offers to turn resource packs off.
- **Uploading** (`/visited upload [dimension]`, or `/visited upload <n>` from an export's [Upload] button,
  `WebmapUpload`): uploads each image, one after another, to `https://map.diorite.xyz/api/map/upload/<dim>`
  (the webmap calls the Nether `nether`, and the overworld in cave mode `overworld_caves`), then runs
  `/api/map/process` and reads its streamed progress. Only if every image was accepted and processing finished
  without an error naming one of them is the export marked as uploaded. Nothing is sent unless the player asks.
  Only the survival server's exports are uploaded (`WebmapUpload.SERVERS`); the creative server shares its host.
  Exports made with resource packs on (`not-for-webmap`) are never offered for upload. A unique prefix on uploaded filenames could tell our
  images apart in the shared processing log, if errors ever get mixed up.
- **Mark as uploaded** (`/visited mark-uploaded <n>`, a button on the export message): forgets the chunks that
  export contained, except ones Xaero has changed since. Works after a restart (`exports.txt`).
  Similar to `/visited clear`, which removes all old exports/records (not just the marked upload) in a given
  dimension.
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

- This branch (`neoforge-1.21.1`): Minecraft 1.21.1, NeoForge (ModDevGradle), Java 21, for the Create server and
  its webmap at `create.diorite.xyz`. `main` is the Fabric 26.3 version. Gradle downloads Java 21 if it isn't
  installed; it can run on a newer one.
- `./gradlew build` makes `build/libs/aomc-visited-<version>.jar`; versions on this branch end in
  `+neoforge-1.21.1`. Gradle fetches Xaero's World Map from Modrinth's Maven repository (`xaero_worldmap_version` in `gradle.properties`) and compiles against it.
- **Bump `version` in `gradle.properties` for every build handed out for testing**, so the jar name and
  the mod list show which build is installed.
- `./gradlew runClient` needs a desktop session.

## Branches, CI and releases

- Work on a feature branch and open a pull request.
- CI (`.github/workflows/build.yml`) builds every pull request and every push to `main` (on this branch:
  `neoforge-1.21.1`), and attaches the jar to the run as an artifact.
- Releases: increment version in `gradle.properties`, push to `main`, CI tags the
  commit `v<version>` and publishes a GitHub release with the jar.
- On the `neoforge-1.21.1` branch the same happens when `version` changes there, as a release that is never
  marked "Latest" (that stays the Fabric release for the main server).

