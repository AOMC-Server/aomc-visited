package io.github.sh4sh.aomcvisited;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;

import static io.github.sh4sh.aomcvisited.AomcVisitedClient.SETTINGS;
import static io.github.sh4sh.aomcvisited.AomcVisitedClient.VISITED;
import static io.github.sh4sh.aomcvisited.AomcVisitedClient.xaeroInstalled;

final class VisitedCommands {
	/** The export starts a tick after the command, once the chat screen has closed. */
	private static PendingExport pending;

	private record PendingExport(ExportTarget target, XaeroExport.Mode mode) {
	}

	private VisitedCommands() {
	}

	static void register(CommandDispatcher<Object> dispatcher) {
		dispatcher.register(rootCommand("visited"));
		dispatcher.register(rootCommand("webmapper"));
	}

	/** {@code /webmapper} is the same command under the mod's name; messages and buttons use {@code /visited}. */
	private static LiteralArgumentBuilder<Object> rootCommand(String name) {
		return literal(name)
				.executes(context -> showCounts())
				.then(exportCommand())
				.then(uploadCommand())
				.then(clearCommand())
				.then(markUploadedCommand())
				.then(literal("on").executes(context -> setEnabled(true)))
				.then(literal("off").executes(context -> setEnabled(false)))
				.then(overlayCommand());
	}

	private static LiteralArgumentBuilder<Object> exportCommand() {
		return exportOptions(literal("export"), context -> ExportTarget.EVERYTHING)
				.then(exportOptions(literal("overworld"), context -> ExportTarget.ofDimension("minecraft:overworld")))
				.then(exportOptions(literal("the_nether"), context -> ExportTarget.ofDimension("minecraft:the_nether")))
				.then(exportOptions(literal("the_end"), context -> ExportTarget.ofDimension("minecraft:the_end")))
				.then(literal("record").then(exportOptions(recordArgument(), context -> ExportTarget.ofRecord(record(context)))));
	}

	private static LiteralArgumentBuilder<Object> uploadCommand() {
		return uploadOptions(literal("upload"), context -> ExportTarget.EVERYTHING)
				.then(uploadOptions(literal("overworld"), context -> ExportTarget.ofDimension("minecraft:overworld")))
				.then(uploadOptions(literal("the_nether"), context -> ExportTarget.ofDimension("minecraft:the_nether")))
				.then(uploadOptions(literal("the_end"), context -> ExportTarget.ofDimension("minecraft:the_end")))
				.then(literal("record").then(uploadOptions(recordArgument(), context -> ExportTarget.ofRecord(record(context)))))
				.then(confirmable(exportArgument(),
						(context, confirmed) -> uploadExport(exportNumber(context), confirmed)));
	}

	private static LiteralArgumentBuilder<Object> clearCommand() {
		return confirmable(literal("clear"), (context, confirmed) -> clear(confirmed))
				.then(confirmable(literal("all"), (context, confirmed) -> clearAll(confirmed)))
				.then(literal("others").then(confirmable(recordArgument(),
						(context, confirmed) -> clearOthers(record(context), confirmed))));
	}

	private static LiteralArgumentBuilder<Object> markUploadedCommand() {
		return literal("mark-uploaded")
				.executes(context -> markUploaded(VISITED.latestExport()))
				.then(exportArgument().executes(context -> markUploaded(exportNumber(context))));
	}

	private static LiteralArgumentBuilder<Object> overlayCommand() {
		return literal("overlay")
				.executes(context -> setShow(!SETTINGS.showOnMap()))
				.then(literal("on").executes(context -> setShow(true)))
				.then(literal("off").executes(context -> setShow(false)));
	}

	private static boolean unavailable() {
		if (VISITED.server() != null) {
			return false;
		}
		reply(Minecraft.getInstance().getConnection() == null
				? Chat.error("Not connected to a world.") : WebmapUpload.offHere());
		return true;
	}

	/** Replies go to chat, so no command reads its source: the same tree works for both loaders' source types. */
	private static LiteralArgumentBuilder<Object> literal(String name) {
		return LiteralArgumentBuilder.literal(name);
	}

	private static <T> RequiredArgumentBuilder<Object, T> argument(String name, ArgumentType<T> type) {
		return RequiredArgumentBuilder.argument(name, type);
	}

	private static void reply(Component message) {
		Chat.send(Minecraft.getInstance(), message);
	}

	static void tick(Minecraft client) {
		if (pending != null) {
			PendingExport export = pending;
			pending = null;
			Component error = XaeroExport.start(client, export.target(), export.mode(), null);
			if (error != null) {
				Chat.send(client, error);
			}
		}
	}

	private static int showCounts() {
		if (unavailable()) {
			return 0;
		}
		String server = VISITED.server();
		Map<String, Integer> counts = VISITED.counts();
		counts.values().removeIf(count -> count == 0);
		MutableComponent message = Chat.info("Chunks recorded on " + server.substring(server.indexOf('/') + 1) + ":");
		if (counts.isEmpty()) {
			message.append(Chat.line("none yet"));
		}
		counts.forEach((key, count) -> {
			String export = recordCommand("export", key), upload = recordCommand("upload", key);
			MutableComponent line = Component.literal(VisitedChunks.displayName(key) + ": " + count + "  ")
					.append(Chat.button("Export", ChatFormatting.GREEN, export, export));
			if (WebmapUpload.webmapName(key) != null) {
				line.append(" ").append(Chat.button("Upload", ChatFormatting.GREEN, upload, upload + " (asks to confirm first)"));
			}
			message.append(Chat.line(line));
		});
		reply(message);
		return 1;
	}

	private static int clear(boolean confirm) {
		if (unavailable() || Minecraft.getInstance().level == null) {
			return 0;
		}
		ResourceKey<Level> dimension = Minecraft.getInstance().level.dimension();
		String id = Platform.id(dimension);
		if (!confirm) {
			reply(Chat.warning("This will forget the " + VISITED.count(dimension) + " chunks"
					+ " recorded in " + id + ", so the next export starts from scratch.")
					.append(Chat.buttons(Chat.button("Confirm", ChatFormatting.RED, "/visited clear confirm",
							"Forget the record for " + id))));
			return 1;
		}
		int forgotten = VISITED.clear(dimension);
		refreshOverlay();
		reply(Chat.success("Forgot " + forgotten + " chunks recorded in " + id + ".")
				.append(Chat.line("Zoomed-out world map views may show the old tint until you zoom in on them once.")));
		return 1;
	}

	private static int clearOthers(String record, boolean confirm) {
		if (unavailable()) {
			return 0;
		}
		List<String> others = XaeroExport.sameMap(record);
		String map = WebmapUpload.webmapName(record);
		if (others.isEmpty()) {
			reply(Chat.info("Nothing else recorded goes to the " + map + " map."));
			return 1;
		}
		String names = VisitedChunks.displayNames(others);
		if (!confirm) {
			int total = others.stream().mapToInt(VISITED::size).sum();
			reply(Chat.warning("This will forget the " + total + " chunks recorded in " + names + ".")
					.append(Chat.buttons(Chat.button("Confirm", ChatFormatting.RED, "/visited clear others "
							+ VisitedChunks.shortName(record) + " confirm", "Forget " + names))));
			return 1;
		}
		int forgotten = others.stream().mapToInt(VISITED::clearRecord).sum();
		refreshOverlay();
		reply(Chat.success("Forgot " + forgotten + " chunks recorded in " + names + "."));
		return 1;
	}

	private static int clearAll(boolean confirm) {
		if (unavailable()) {
			return 0;
		}
		if (!confirm) {
			int total = VISITED.counts().values().stream().mapToInt(Integer::intValue).sum();
			reply(Chat.warning("This will forget all " + total + " chunks recorded on this server,"
					+ " in every dimension, so the next export starts from scratch.")
					.append(Chat.buttons(Chat.button("Confirm", ChatFormatting.RED, "/visited clear all confirm",
							"Forget the record in every dimension"))));
			return 1;
		}
		int forgotten = VISITED.clearAll();
		refreshOverlay();
		reply(Chat.success("Forgot " + forgotten + " chunks in every dimension.")
				.append(Chat.line("Zoomed-out world map views may show the old tint until you zoom in on them once.")));
		return 1;
	}

	private static int markUploaded(int export) {
		if (unavailable()) {
			return 0;
		}
		if (export < 0) {
			reply(Chat.error("There's no export to mark as uploaded. To start over, use /visited"
					+ " clear."));
			return 0;
		}
		int forgotten = VISITED.markUploaded(export);
		if (forgotten < 0) {
			reply(Chat.error("That export was already marked as uploaded, or its record was cleared"
					+ " since. To start over, use /visited clear."));
			return 0;
		}
		refreshOverlay();
		reply(Chat.success("Marked as uploaded: forgot " + forgotten + " chunks.")
				.append(Chat.line("Anything that changed since that export is kept for the next one.")));
		return 1;
	}

	private static int setEnabled(boolean enabled) {
		SETTINGS.setEnabled(enabled);
		refreshOverlay();
		reply(Chat.info(enabled
				? "AOMC Webmapper is on: recording what Xaero's World Map maps. Turn it off with /visited off."
				: "AOMC Webmapper is off: nothing is recorded or shown, and what's already recorded is kept. Turn it"
						+ " back on with /visited on."));
		return 1;
	}

	private static int setShow(boolean show) {
		SETTINGS.setShowOnMap(show);
		refreshOverlay();
		reply(Chat.info(show
				? "Showing what the next export will keep on Xaero's World Map. Hide it with /visited overlay off."
				: "No longer showing the record on Xaero's World Map. Show it again with /visited overlay on."));
		return 1;
	}

	private interface TargetArgument {
		ExportTarget get(CommandContext<Object> context) throws CommandSyntaxException;
	}

	/** A command that asks first, and runs under {@code confirm}. */
	private interface Confirmable {
		int run(CommandContext<Object> context, boolean confirmed) throws CommandSyntaxException;
	}

	private static <S extends ArgumentBuilder<Object, S>> S confirmable(S command, Confirmable action) {
		return command.executes(context -> action.run(context, false))
				.then(literal("confirm").executes(context -> action.run(context, true)));
	}

	static String recordCommand(String verb, String key) {
		return "/visited " + verb + " record " + VisitedChunks.shortName(key);
	}

	private static RequiredArgumentBuilder<Object, String> recordArgument() {
		return argument("name", StringArgumentType.word());
	}

	private static String record(CommandContext<Object> context) throws CommandSyntaxException {
		String name = StringArgumentType.getString(context, "name");
		String key = VisitedChunks.keyOf(name);
		if (key == null) {
			throw new SimpleCommandExceptionType(Component.literal("No record called " + name + "; see /visited")).create();
		}
		return key;
	}

	private static RequiredArgumentBuilder<Object, Integer> exportArgument() {
		return argument("export", IntegerArgumentType.integer(1));
	}

	private static int exportNumber(CommandContext<Object> context) {
		return IntegerArgumentType.getInteger(context, "export");
	}

	private static <S extends ArgumentBuilder<Object, S>> S exportOptions(
			S command, TargetArgument target) {
		return command.executes(context -> requestExport(target.get(context), false))
				.then(literal("not-for-webmap").executes(context ->
						requestExport(target.get(context), true)))
				.then(literal("without-packs").executes(context ->
						exportWithoutPacks(target.get(context), false)));
	}

	private static <S extends ArgumentBuilder<Object, S>> S uploadOptions(
			S command, TargetArgument target) {
		return confirmable(command,
				(context, confirmed) -> requestUpload(target.get(context), confirmed))
				.then(literal("without-packs").executes(context ->
						exportWithoutPacks(target.get(context), true)));
	}

	/** {@code /visited upload [<dimension>|record <name>]}: exports, then uploads to the webmap and marks as uploaded. */
	private static int requestUpload(ExportTarget target, boolean confirmed) {
		if (unavailable()) {
			return 0;
		}
		if (!xaeroInstalled()) {
			reply(Chat.error("Uploading needs Xaero's World Map."));
			return 0;
		}
		if (WebmapUpload.running()) {
			reply(Chat.error("An upload is already running."));
			return 0;
		}
		List<String> packs = ResourcePacks.playerPacks(Minecraft.getInstance());
		String command = "/visited upload" + target.commandSuffix();
		String what = target.description();
		if (!packs.isEmpty()) {
			reply(Chat.warning("Resource packs change the colours of the map, so they have to be off for"
					+ " an upload. These are on:")
					.append(Chat.line(String.join(", ", packs)))
					.append(Chat.buttons(Chat.button("Turn packs off and upload", ChatFormatting.GREEN,
							command + " without-packs", "Turns your resource packs off, exports " + what + ", turns them"
									+ " back on, then uploads to " + WebmapUpload.HOST + " and marks it as uploaded"))));
			return 0;
		}
		if (!confirmed) {
			reply(Chat.info("Export and upload " + what + " to " + WebmapUpload.HOST + "? It's marked as"
					+ " uploaded once the webmap has processed it.")
					.append(Chat.buttons(Chat.button("Confirm", ChatFormatting.GREEN, command + " confirm",
							"Export and upload " + what))));
			return 1;
		}
		pending = new PendingExport(target, XaeroExport.Mode.UPLOAD);
		return 1;
	}

	/** {@code /visited upload <export>}: the [Upload] button on an export made this session. */
	private static int uploadExport(int number, boolean confirmed) {
		if (unavailable()) {
			return 0;
		}
		WebmapUpload.Dimension export = WebmapUpload.export(number);
		if (export == null) {
			reply(Chat.error("That export's images aren't known any more: it's from an earlier session,"
					+ " or a newer export replaced it. Use /visited upload to export and upload again."));
			return 0;
		}
		if (WebmapUpload.running()) {
			reply(Chat.error("An upload is already running."));
			return 0;
		}
		String what = export.images().size() + " images to the " + export.map() + " map";
		if (!confirmed) {
			reply(Chat.info("Upload " + what + " on " + WebmapUpload.HOST + "? They're marked as uploaded once"
					+ " the webmap has processed them.")
					.append(Chat.buttons(Chat.button("Confirm", ChatFormatting.GREEN, "/visited upload " + number
							+ " confirm", "Upload " + what))));
			return 1;
		}
		if (!export.images().stream().allMatch(java.nio.file.Files::isRegularFile)) {
			reply(Chat.error("Some of that export's images were moved or deleted. Use /visited upload"
					+ " to export and upload again."));
			return 0;
		}
		WebmapUpload.start(Minecraft.getInstance(), List.of(export));
		return 1;
	}

	private static int requestExport(ExportTarget target, boolean withPacks) {
		if (unavailable()) {
			return 0;
		}
		if (!xaeroInstalled()) {
			reply(Chat.error("Exporting needs Xaero's World Map."));
			return 0;
		}
		List<String> packs = ResourcePacks.playerPacks(Minecraft.getInstance());
		String command = "/visited export" + target.commandSuffix();
		if (!packs.isEmpty() && !withPacks) {
			reply(Chat.warning("Resource packs change the colours of the exported map. These are on:")
					.append(Chat.line(String.join(", ", packs)))
					.append(Chat.buttons(
							Chat.button("Turn packs off for the export", ChatFormatting.GREEN, command + " without-packs",
									"Turns your resource packs off, exports, then turns them back on in the same"
											+ " order. Minecraft reloads twice."),
							Chat.button("Export with packs (not for the webmap)", ChatFormatting.GOLD,
									command + " not-for-webmap",
									"Export with your resource packs on, e.g. for yourself: don't upload it to the webmap,"
											+ " its colours may be off"))));
			return 0;
		}
		pending = new PendingExport(target, withPacks ? XaeroExport.Mode.NOT_FOR_WEBMAP : XaeroExport.Mode.EXPORT);
		return 1;
	}

	private static int exportWithoutPacks(ExportTarget target, boolean upload) {
		if (unavailable()) {
			return 0;
		}
		if (!xaeroInstalled()) {
			reply(Chat.error("Exporting needs Xaero's World Map."));
			return 0;
		}
		if (XaeroExport.active()) {
			reply(Chat.error("An export is already running."));
			return 0;
		}
		if (upload && WebmapUpload.running()) {
			reply(Chat.error("An upload is already running."));
			return 0;
		}
		Minecraft client = Minecraft.getInstance();
		XaeroExport.Mode mode = upload ? XaeroExport.Mode.UPLOAD : XaeroExport.Mode.EXPORT;
		if (ResourcePacks.playerPacks(client).isEmpty()) {
			pending = new PendingExport(target, mode);
			return 1;
		}
		reply(Chat.info("Turning your resource packs off for the export. They'll come back on"
				+ " afterwards, in the same order.")
				.append(Chat.line("Right after turning packs off, Xaero can take up to a minute to export, and the"
						+ " game may freeze meanwhile.")));
		ResourcePacks.turnOff(client, restore -> {
			Component error = XaeroExport.start(client, target, mode, restore);
			if (error != null) {
				Chat.send(client, error);
				restore.run();
			}
		});
		return 1;
	}

	private static void refreshOverlay() {
		if (xaeroInstalled()) {
			VisitedHighlighter.refreshAll();
		}
	}
}
