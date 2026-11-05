package io.github.sh4sh.aomcvisited;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.file.export.PNGExportResult;
import xaero.map.file.export.PNGExportResultType;
import xaero.map.gui.ExportScreen;
import xaero.map.gui.MapTileSelection;
import xaero.map.world.MapWorld;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.github.sh4sh.aomcvisited.AomcVisitedClient.VISITED;

public final class XaeroExport {
	private static final Pattern EXPORT_FOLDER = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}");
	private static final Pattern SINGLE_IMAGE =
			Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}_x(-?\\d+)_z(-?\\d+)(?:_\\d+)?\\.png");
	private static final DateTimeFormatter FOLDER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

	private static final long STUCK_MS = 5 * 60 * 1000;
	/**
	 * Xaero's task polls until its "exporting" flag clears; starting the next area before it returns sets the
	 * flag again and both wait forever. Used only if {@code XaeroExportTaskMixin} can't report the return.
	 */
	private static final long TASK_END_FALLBACK_MS = 1000;
	private static final long SWITCH_TIMEOUT_MS = 30_000;
	private static final long SETTLE_MS = 1000;
	private static final int NOT_PREPARED_RETRIES = 20;
	private static final int LARGE_EXPORT_TILES = 25;

	private static volatile Job job;
	private static volatile long lastProgress;
	private static volatile boolean taskEnded;

	private XaeroExport() {
	}

	/** Xaero exports the cave mode it's showing; ours exports the one the record was made in. */
	public static int layer() {
		Job current = job;
		return current == null || current.dimension >= current.keys.size() ? VisitedChunks.SURFACE_LAYER
				: VisitedChunks.layerOf(current.keys.get(current.dimension));
	}

	/** If Xaero's exporter throws it never reports back, so a stuck job stops counting. */
	public static boolean active() {
		return job != null && System.currentTimeMillis() - lastProgress < STUCK_MS;
	}

	/** A stuck job never runs its whenDone, so it's dropped here, which puts the resource packs back. */
	private static void dropStuck() {
		if (job != null && !active()) {
			log("dropping an export with no progress for {} minutes", STUCK_MS / 60_000);
			reset();
			Chat.send(Minecraft.getInstance(), Chat.warning("The export stopped making progress, so it was stopped."
					+ " If your resource packs were turned off for it, they're being turned back on."));
		}
	}

	static void reset() {
		Job current = job;
		job = null;
		if (current != null && current.whenDone != null) {
			current.whenDone.run();
		}
	}

	public static void taskEnded() {
		taskEnded = true;
	}

	static void tick() {
		dropStuck();
		Job current = job;
		if (current != null) {
			current.tick();
		}
	}

	/** {@code NOT_FOR_WEBMAP} exports have resource packs on, so they're never offered for upload. */
	enum Mode { EXPORT, NOT_FOR_WEBMAP, UPLOAD }

	/**
	 * Records of the target that would go to the same webmap map are left out, with a button each, since uploading
	 * both would mix cave modes. A target naming one record always exports it.
	 */
	static Component start(Minecraft client, ExportTarget target, Mode mode, Runnable whenDone) {
		if (!AomcVisitedClient.SETTINGS.enabled()) {
			return Chat.error("AOMC Webmapper is off. Turn it on with /visited on.");
		}
		if (active()) {
			return Chat.error("An export is already running.");
		}
		WorldMapSession session = WorldMapSession.getCurrentSession();
		MapProcessor processor = session == null ? null : session.getMapProcessor();
		if (processor == null || !processor.isMapWorldUsable() || processor.getMapWorld() == null) {
			return Chat.error("Xaero's World Map isn't ready yet. Open the world map once, then try again.");
		}
		List<String> keys = new ArrayList<>();
		MutableComponent choices = Component.empty();
		if (target.record() != null) {
			if (VISITED.size(target.record()) == 0) {
				return Chat.error("Nothing recorded in " + target.description() + ".");
			}
			keys.add(target.record());
		} else {
			List<String> dimensions = VISITED.dimensions();
			if (target.dimension() != null) {
				if (!dimensions.contains(target.dimension())) {
					return Chat.error("Nothing recorded in " + target.description() + " yet.");
				}
				dimensions = List.of(target.dimension());
			}
			if (dimensions.isEmpty()) {
				return Chat.error("Nothing recorded yet. If Xaero has been mapping and this stays empty, this version of"
						+ " Xaero's World Map may not be supported.");
			}
			for (String dimension : dimensions) {
				byMap(dimension).forEach((map, records) -> {
					if (records.size() == 1) {
						keys.add(records.getFirst());
					} else {
						choices.append(choice(map, records, mode));
					}
				});
			}
		}
		if (keys.isEmpty()) {
			return Chat.warning("Nothing exported: pick which cave mode to export.").append(choices);
		}
		List<String> names = keys.stream().map(VisitedChunks::displayName).toList();
		int tiles = keys.stream().flatMap(key -> plan(key).stream()).mapToInt(ExportAreas.Box::tiles).sum();
		ResourceKey<Level> showing = processor.getMapWorld().getCurrentDimensionId();
		boolean switches = keys.stream().map(VisitedChunks::dimensionOf).distinct().count() > 1 || showing == null
				|| !VisitedChunks.dimensionOf(keys.getFirst()).equals(showing.location().toString());
		String switching = switches ? " Xaero's map switches dimension while it works, and the" : " The";
		String freeze = tiles > LARGE_EXPORT_TILES
				? " game may freeze for a while: this is a large export, up to " + tiles + " images."
				: " game may freeze for a few seconds.";
		Chat.send(client, Chat.info("Exporting " + String.join(", ", names) + ". Progress shows above your hotbar."
				+ switching + freeze));
		log("{}, up to {} images", String.join(", ", names), tiles);
		job = new Job(client, processor, keys, mode, whenDone);
		job.report.append(choices);
		lastProgress = System.currentTimeMillis();
		job.startDimension();
		return null;
	}

	/** The dimension's records, grouped by the webmap map they go to (records with no map share "null"). */
	private static Map<String, List<String>> byMap(String dimension) {
		Map<String, List<String>> byMap = new LinkedHashMap<>();
		for (String key : VISITED.recordKeys(dimension)) {
			byMap.computeIfAbsent(String.valueOf(WebmapUpload.webmapName(key)), k -> new ArrayList<>()).add(key);
		}
		return byMap;
	}

	static List<String> sameMap(String record) {
		return byMap(VisitedChunks.dimensionOf(record))
				.getOrDefault(String.valueOf(WebmapUpload.webmapName(record)), List.of()).stream()
				.filter(key -> !key.equals(record)).toList();
	}

	private static final int CHOICE_BUTTONS = 3;

	private static Component choice(String map, List<String> records, Mode mode) {
		List<String> bySize = records.stream()
				.sorted(Comparator.comparingInt(VISITED::size).reversed()).toList();
		String verb = mode == Mode.UPLOAD ? "upload" : "export";
		String suffix = mode == Mode.NOT_FOR_WEBMAP ? " not-for-webmap" : "";
		List<Component> buttons = new ArrayList<>();
		for (String key : bySize.subList(0, Math.min(CHOICE_BUTTONS, bySize.size()))) {
			String caves = VisitedChunks.caveModeName(key);
			String label = (caves == null ? "cave mode off" : caves) + ": " + VISITED.size(key) + " chunks";
			String command = VisitedCommands.recordCommand(verb, key) + suffix;
			String hover = (mode == Mode.UPLOAD ? "Export and upload" : "Export") + " only what was recorded in "
					+ VisitedChunks.displayName(key);
			buttons.add(Chat.button(label, ChatFormatting.GREEN, command, hover));
		}
		int more = bySize.size() - buttons.size();
		// /visited's buttons make webmap exports, so they'd lose not-for-webmap.
		boolean moreButton = more > 0 && mode != Mode.NOT_FOR_WEBMAP;
		if (moreButton) {
			buttons.add(Chat.button(more + " more", ChatFormatting.AQUA, "/visited", "Lists every record, each with its"
					+ " own buttons"));
		}
		String dimension = VisitedChunks.shortName(VisitedChunks.dimensionOf(records.getFirst()));
		String unlisted = more > 0 && !moreButton ? " (" + more + " more: see /visited)" : "";
		MutableComponent line = Chat.line(Component.literal(dimension + ": recorded in " + records.size()
				+ " cave modes, which all go to the " + map + " map. Pick one" + unlisted + ":")
				.withStyle(ChatFormatting.GOLD));
		return line.append(Chat.buttons(buttons.toArray(Component[]::new)));
	}

	private static void appendButtons(MutableComponent message, List<Component> buttons) {
		if (!buttons.isEmpty()) {
			message.append(Chat.buttons(buttons.toArray(Component[]::new)));
		}
	}

	private static final class Job {
		private enum Stage { SWITCHING, SETTLING, EXPORTING, AREA_DONE, RETRY }

		private final Minecraft client;
		private final MapProcessor processor;
		private final List<String> keys;
		private final Mode mode;
		private final Runnable whenDone;
		private final ResourceKey<Level> previousCustom;
		private final long writesAtStart = VISITED.writes();
		private final MutableComponent report = Component.empty();
		private final long started = System.currentTimeMillis();
		private final List<Component> buttons = new ArrayList<>();
		private final List<Component> markButtons = new ArrayList<>();
		private final List<Component> clearButtons = new ArrayList<>();
		private final List<WebmapUpload.Dimension> uploads = new ArrayList<>();
		private int ticks;
		private int dimension;
		private Path root;
		private boolean anyImages;
		private Stage stage;
		private long stageSince;
		private ResourceKey<Level> target;
		private Run run;

		Job(Minecraft client, MapProcessor processor, List<String> keys, Mode mode, Runnable whenDone) {
			this.client = client;
			this.processor = processor;
			this.keys = keys;
			this.mode = mode;
			this.whenDone = whenDone;
			this.previousCustom = processor.getMapWorld().getCustomDimensionId();
		}

		private void stage(Stage next) {
			stage = next;
			stageSince = System.currentTimeMillis();
			lastProgress = stageSince;
		}

		private long inStage() {
			return System.currentTimeMillis() - stageSince;
		}

		void startDimension() {
			if (dimension == keys.size()) {
				finish();
				return;
			}
			target = ResourceKey.create(Registries.DIMENSION,
					ResourceLocation.parse(VisitedChunks.dimensionOf(keys.get(dimension))));
			processor.getMapWorld().setCustomDimensionId(target);
			processor.checkForWorldUpdate();
			stage(Stage.SWITCHING);
		}

		private void nextDimension() {
			dimension++;
			startDimension();
		}

		void tick() {
			if (++ticks % 20 == 1) {
				showProgress();
			}
			switch (stage) {
				case SWITCHING -> {
					MapWorld world = processor.getMapWorld();
					if (!processor.isWaitingForWorldUpdate() && processor.isMapWorldUsable() && world != null
							&& target.equals(world.getCurrentDimensionId())) {
						log("{}: Xaero's map switched after {} ms", VisitedChunks.displayName(keys.get(dimension)), inStage());
						stage(Stage.SETTLING);
					} else if (inStage() > SWITCH_TIMEOUT_MS) {
						log("{}: Xaero's map didn't switch after {} ms, skipping", VisitedChunks.displayName(keys.get(dimension)),
								inStage());
						line(red(VisitedChunks.displayName(keys.get(dimension)) + ": Xaero didn't switch its map to it in time,"
								+ " so it was skipped. Try again."));
						nextDimension();
					}
				}
				case SETTLING -> {
					if (inStage() > SETTLE_MS) {
						beginDimensionExport();
					}
				}
				case AREA_DONE -> {
					if (taskEnded || inStage() > TASK_END_FALLBACK_MS) {
						run.continueAfterArea();
					}
				}
				case RETRY -> {
					if (inStage() > SETTLE_MS && (taskEnded || inStage() > SETTLE_MS + TASK_END_FALLBACK_MS)) {
						run.exportArea();
					}
				}
				case EXPORTING -> {
					// Waiting for Xaero to call onExportDone.
				}
			}
		}

		private void showProgress() {
			String name = VisitedChunks.displayName(keys.get(dimension));
			String of = keys.size() > 1 ? " (" + (dimension + 1) + " of " + keys.size() + ")" : "";
			String what = stage == Stage.SWITCHING || stage == Stage.SETTLING || run == null
					? "switching Xaero's map to " + name + of
					: "exporting " + name + of + ", area " + Math.min(run.index + 1, run.areas.size()) + " of "
							+ run.areas.size();
			Chat.status(client, what + ".".repeat(1 + ticks / 20 % 3), ChatFormatting.YELLOW);
		}

		private void beginDimensionExport() {
			String key = keys.get(dimension);
			run = new Run(this, key, plan(key));
			log("{}: {} area(s), up to {} images", VisitedChunks.shortName(key), run.areas.size(),
					run.areas.stream().mapToInt(ExportAreas.Box::tiles).sum());
			showProgress();
			run.exportArea();
		}

		void line(Component text) {
			report.append(Chat.line(text));
		}

		private void finish() {
			log("finished in {} ms", System.currentTimeMillis() - started);
			MapWorld world = processor.getMapWorld();
			if (world != null) {
				world.setCustomDimensionId(previousCustom);
				processor.checkForWorldUpdate();
			}
			job = null;
			VISITED.save();
			MutableComponent message;
			String shown = root == null ? "" : root.getParent().getFileName() + "/" + root.getFileName();
			if (anyImages && mode == Mode.UPLOAD) {
				message = Chat.info("Export cleaned: map exports/" + shown + ". Uploading it to"
						+ " " + WebmapUpload.HOST + " now. It's marked as uploaded once the webmap has processed it.");
				message.append(report).append(Chat.buttons(Chat.openButton("Open folder", root)));
				appendButtons(message, clearButtons);
				if (!uploads.isEmpty()) {
					WebmapUpload.start(client, List.copyOf(uploads));
				}
			} else if (anyImages) {
				Util.getPlatform().openFile(root.toFile());
				message = Chat.success("Export cleaned and ready to upload: map exports/" + shown
						+ (keys.size() == 1 && run != null ? ". Upload it to the " + WebmapUpload.webmapName(run.key) + " map."
								: ". Upload each dimension's folder to its own map."));
				buttons.addFirst(Chat.openButton("Open folder", root));
				appendButtons(message.append(report), buttons);
				appendButtons(message, markButtons);
				appendButtons(message, clearButtons);
				Chat.status(client, "export done, see chat.", ChatFormatting.GREEN);
			} else {
				deleteIfEmpty(root);
				message = Chat.warning("Nothing to upload.").append(report);
				Chat.status(client, "nothing to upload.", ChatFormatting.GOLD);
			}
			Chat.send(client, message);
			if (whenDone != null) {
				whenDone.run();
			}
		}
	}

	private static final class Run {
		private final Job job;
		private final String key;
		private final List<ExportAreas.Box> areas;
		private final LongOpenHashSet exported = new LongOpenHashSet();
		private int index;
		private int retries;
		private Path folder;
		private TileMasker.Result total = new TileMasker.Result(0, 0, 0, 0);
		private ExportScreen doneScreen;
		private long areaStarted;
		private Component doneProblem;

		Run(Job job, String key, List<ExportAreas.Box> areas) {
			this.job = job;
			this.key = key;
			this.areas = areas;
		}

		void exportArea() {
			if (index == areas.size()) {
				finishDimension();
				return;
			}
			ExportAreas.Box area = areas.get(index);
			MapTileSelection selection = new MapTileSelection(area.minX(), area.minZ());
			selection.setEnd(area.maxX(), area.maxZ());
			// Xaero only needs the screen to report the result; it shows it afterwards, and we close it.
			ExportScreen screen = new ExportScreen(null, null, job.processor, selection) {
				@Override
				public void onExportDone(PNGExportResult result) {
					super.onExportDone(result);
					done(this, area, result);
				}
			};
			taskEnded = false;
			areaStarted = System.currentTimeMillis();
			log("{}: area {} of {} ({} x {} chunks) handed to Xaero", VisitedChunks.shortName(key), index + 1, areas.size(),
					area.maxX() - area.minX() + 1, area.maxZ() - area.minZ() + 1);
			if (!job.processor.getMapSaveLoad().exportPNG(screen, selection)) {
				fail(red("Xaero's World Map is already exporting."));
				return;
			}
			job.stage(Job.Stage.EXPORTING);
			// Xaero runs the export a moment later, so this gets drawn before the game freezes.
			job.showProgress();
		}

		/** Runs inside Xaero's task, which shows {@code screen} afterwards (see {@link #TASK_END_FALLBACK_MS}). */
		private void done(ExportScreen screen, ExportAreas.Box area, PNGExportResult result) {
			doneScreen = screen;
			PNGExportResultType type = result.getType();
			log("{}: Xaero answered {} after {} ms", VisitedChunks.shortName(key), type,
					System.currentTimeMillis() - areaStarted);
			if (type == PNGExportResultType.NOT_PREPARED && retries < NOT_PREPARED_RETRIES) {
				retries++;
				log("{}: Xaero's map not prepared yet, retry {} of {}", VisitedChunks.shortName(key), retries,
						NOT_PREPARED_RETRIES);
				doneProblem = null;
				job.stage(Job.Stage.RETRY);
				closeScreenLater();
				return;
			}
			if (type == PNGExportResultType.EMPTY) {
				doneProblem = null;
			} else if (type.isSuccess() && result.getFolderToOpen() != null) {
				long cleaning = System.currentTimeMillis();
				doneProblem = clean(result.getFolderToOpen(), area);
				log("{}: cleaned in {} ms", VisitedChunks.shortName(key), System.currentTimeMillis() - cleaning);
			} else {
				doneProblem = red("Xaero couldn't export area " + (index + 1) + " of " + areas.size() + ": ")
						.append(result.getMessage());
			}
			job.stage(Job.Stage.AREA_DONE);
		}

		/** For a retry: Xaero shows the screen after this task, so close it once the task is over. */
		private void closeScreenLater() {
			ExportScreen screen = doneScreen;
			job.client.execute(() -> {
				if (job.client.screen == screen) {
					job.client.setScreen(null);
				}
			});
		}

		void continueAfterArea() {
			if (job.client.screen == doneScreen) {
				job.client.setScreen(null);
			}
			if (doneProblem != null) {
				fail(doneProblem);
				return;
			}
			index++;
			retries = 0;
			exportArea();
		}

		/**
		 * {@code written} is a timestamped folder of tiles, or, when the area fits in one tile, {@code map
		 * exports} itself with a single image in it. Returns an error, or null.
		 */
		private Component clean(Path written, ExportAreas.Box area) {
			// Xaero's writer runs on this thread, so this is exactly what the export rendered.
			LongOpenHashSet chunks = VISITED.snapshot(key);
			for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
				long pos = it.nextLong();
				int x = ChunkPos.getX(pos), z = ChunkPos.getZ(pos);
				if (x >= area.minX() && x <= area.maxX() && z >= area.minZ() && z <= area.maxZ()) {
					exported.add(pos);
				}
			}
			TileMasker.Recorded recorded = (x, z) -> chunks.contains(ChunkPos.asLong(x, z));
			boolean tiles = EXPORT_FOLDER.matcher(written.getFileName().toString()).matches();
			Path image = null;
			try {
				if (job.root == null) {
					Path mapExports = tiles ? written.getParent() : written;
					String dimension = job.keys.size() == 1 ? VisitedChunks.shortName(key) + "-" : "";
					String server = VISITED.server();
					// Grouped by server, like the records, so one server's exports can't be mistaken for another's.
					String serverFolder = server == null ? "unknown" : server.substring(server.indexOf('/') + 1);
					String exportFolder = "aomc-webmapper-" + dimension + LocalDateTime.now().format(FOLDER_TIME);
					job.root = mapExports.resolve(serverFolder).resolve(exportFolder);
				}
				if (folder == null) {
					folder = job.keys.size() == 1 ? job.root : job.root.resolve(VisitedChunks.shortName(key));
					Files.createDirectories(folder);
				}
				if (tiles) {
					total = total.plus(TileMasker.mask(written, recorded));
					moveImages(written);
					Files.deleteIfExists(written);
				} else {
					int blockX = area.minX() * 16, blockZ = area.minZ() * 16;
					image = findSingleImage(written, blockX, blockZ);
					if (image == null) {
						return red("Couldn't find the exported image in " + written + ", so it wasn't cleaned."
								+ " Don't upload it.");
					}
					BufferedImage read = ImageIO.read(image.toFile());
					int width = (area.maxX() - area.minX() + 1) * 16, height = (area.maxZ() - area.minZ() + 1) * 16;
					if (read == null || read.getWidth() != width || read.getHeight() != height) {
						return red("Xaero scaled the export down, so it can't be cleaned. Don't upload " + image + ".");
					}
					TileMasker.Result result = TileMasker.maskImage(image, blockX, blockZ, recorded);
					total = total.plus(result);
					if (result.tilesDeleted() == 0) {
						Files.move(image, folder.resolve(image.getFileName()));
					}
				}
				return null;
			} catch (IOException | RuntimeException e) {
				Path broken = image != null ? image : written;
				AomcVisitedClient.LOGGER.error("Could not clean the export in {}", broken, e);
				return red("Could not clean the export (" + e.getMessage() + "). Don't upload it: it may contain"
						+ " older map data. " + markNotCleaned(broken));
			}
		}

		private void moveImages(Path tileFolder) throws IOException {
			try (Stream<Path> files = Files.list(tileFolder)) {
				for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".png"))::iterator) {
					Files.move(file, folder.resolve(file.getFileName()));
				}
			}
		}

		private void finishDimension() {
			int left = total.tiles() - total.tilesDeleted();
			if (left == 0) {
				deleteIfEmpty(folder);
				job.line(Component.literal(VisitedChunks.shortName(key) + ": nothing to upload."));
				job.nextDimension();
				return;
			}
			job.anyImages = true;
			int changed = 0;
			for (LongIterator it = exported.iterator(); it.hasNext(); ) {
				long pos = it.nextLong();
				if (VISITED.isChanged(key, ChunkPos.getX(pos), ChunkPos.getZ(pos))) {
					changed++;
				}
			}
			int number = VISITED.rememberExport(key, exported, job.writesAtStart);
			List<Path> images;
			try (Stream<Path> files = Files.list(folder)) {
				images = files.filter(p -> p.toString().endsWith(".png")).sorted().toList();
			} catch (IOException e) {
				AomcVisitedClient.LOGGER.error("Could not list {}", folder, e);
				images = List.of();
			}
			WebmapUpload.Dimension upload = new WebmapUpload.Dimension(VISITED.server(), key,
					number, images);
			if (job.mode == Mode.UPLOAD) {
				job.uploads.add(upload);
			} else {
				if (job.mode == Mode.EXPORT && WebmapUpload.forWebmap(upload.server())) {
					WebmapUpload.remember(upload);
					job.buttons.add(Chat.button("Upload " + VisitedChunks.shortName(key), ChatFormatting.GREEN,
							"/visited upload " + number, "Uploads " + where() + " to " + WebmapUpload.HOST
									+ ", then marks it as uploaded (asks to confirm first)"));
				}
				job.markButtons.add(Chat.button("Mark " + VisitedChunks.shortName(key) + " as uploaded",
						ChatFormatting.DARK_GREEN, "/visited mark-uploaded " + number, "Click once " + where()
								+ " has been uploaded by hand: forgets what it contained, so the next export only has"
								+ " areas mapped since. Works after a restart too."));
			}
			List<String> others = sameMap(key);
			if (!others.isEmpty()) {
				String hover = "Forget what else was recorded for the " + WebmapUpload.webmapName(key) + " map, in "
						+ VisitedChunks.displayNames(others) + " (asks to confirm first)";
				job.clearButtons.add(Chat.button("Clear the rest", ChatFormatting.RED,
						"/visited clear others " + VisitedChunks.shortName(key), hover));
			}
			String folderNote = job.keys.size() == 1 ? "" : " (" + VisitedChunks.shortName(key) + "/)";
			String imageCount = left + (left == 1 ? " image" : " images");
			String blackedOut = total.chunksBlackedOut() > 0
					? ", " + total.chunksBlackedOut() + " older chunks blacked out" : "";
			job.line(Component.literal(VisitedChunks.displayName(key) + folderNote + ": " + total.chunksKept()
					+ " chunks (" + changed + " new or changed), " + imageCount + blackedOut + "."));
			job.nextDimension();
		}

		private String where() {
			return job.keys.size() == 1 ? "the export" : VisitedChunks.shortName(key) + "/";
		}

		private void fail(Component problem) {
			MutableComponent text = Component.literal(VisitedChunks.shortName(key) + ": ").append(problem);
			if (total.tiles() > total.tilesDeleted()) {
				text.append(" The areas before it are cleaned, but " + where() + " is incomplete.");
				job.anyImages = true;
			} else {
				deleteIfEmpty(folder);
			}
			job.line(text.withStyle(ChatFormatting.RED));
			job.nextDimension();
		}
	}

	private static void log(String message, Object... args) {
		AomcVisitedClient.LOGGER.info("[AOMC Webmapper] export: " + message, args);
	}

	private static List<ExportAreas.Box> plan(String key) {
		LongOpenHashSet chunks = VISITED.snapshot(key);
		List<int[]> list = new ArrayList<>(chunks.size());
		for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
			long pos = it.nextLong();
			list.add(new int[] {ChunkPos.getX(pos), ChunkPos.getZ(pos)});
		}
		return ExportAreas.plan(list);
	}

	private static void deleteIfEmpty(Path folder) {
		if (folder != null) {
			try {
				Files.deleteIfExists(folder);
			} catch (IOException e) {
				// Not empty, or gone: leave it.
			}
		}
	}

	private static Path findSingleImage(Path folder, int blockX, int blockZ) throws IOException {
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter(p -> {
				Matcher m = SINGLE_IMAGE.matcher(p.getFileName().toString());
				return m.matches() && Integer.parseInt(m.group(1)) == blockX && Integer.parseInt(m.group(2)) == blockZ;
			}).max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
		}
	}

	private static String markNotCleaned(Path written) {
		String name = written.getFileName().toString();
		Path marked;
		if (EXPORT_FOLDER.matcher(name).matches()) {
			marked = written.resolveSibling(name + "-NOT-CLEANED");
		} else if (SINGLE_IMAGE.matcher(name).matches()) {
			marked = written.resolveSibling(name + ".NOT-CLEANED");
		} else {
			return "It's in " + written + ".";
		}
		try {
			Files.move(written, marked);
			return "It's been renamed to " + marked + ".";
		} catch (IOException e) {
			AomcVisitedClient.LOGGER.error("Could not rename {}", written, e);
			return "It's in " + written + ".";
		}
	}

	private static MutableComponent red(String text) {
		return Component.literal(text).withStyle(ChatFormatting.RED);
	}
}
