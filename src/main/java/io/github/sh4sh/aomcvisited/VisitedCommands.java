package io.github.sh4sh.aomcvisited;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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

	private record PendingExport(String only, String record, XaeroExport.Mode mode) {
	}

	private VisitedCommands() {
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
			Commands.literal("visited")
					.executes(context -> showCounts(context.getSource()))
					.then(exportCommand(Commands.literal("export"), null)
							.then(exportCommand(Commands.literal("overworld"), "minecraft:overworld"))
							.then(exportCommand(Commands.literal("the_nether"), "minecraft:the_nether"))
							.then(exportCommand(Commands.literal("the_end"), "minecraft:the_end"))
							.then(Commands.literal("record").then(exportCommand(recordArgument(), RECORD))))
					.then(Commands.literal("clear")
							.executes(context -> clear(context.getSource(), false))
							.then(Commands.literal("confirm").executes(context -> clear(context.getSource(), true)))
							.then(Commands.literal("all")
									.executes(context -> clearAll(context.getSource(), false))
									.then(Commands.literal("confirm").executes(context -> clearAll(context.getSource(), true))))
							.then(Commands.literal("others").then(recordArgument()
									.executes(context -> clearOthers(context.getSource(), record(context), false))
									.then(Commands.literal("confirm").executes(context ->
											clearOthers(context.getSource(), record(context), true))))))
					.then(uploadCommand(Commands.literal("upload"), null)
							.then(uploadCommand(Commands.literal("overworld"), "minecraft:overworld"))
							.then(uploadCommand(Commands.literal("the_nether"), "minecraft:the_nether"))
							.then(uploadCommand(Commands.literal("the_end"), "minecraft:the_end"))
							.then(Commands.literal("record").then(uploadCommand(recordArgument(), RECORD)))
							.then(Commands.argument("export", IntegerArgumentType.integer(1))
									.executes(context -> uploadExport(context.getSource(),
											IntegerArgumentType.getInteger(context, "export"), false))
									.then(Commands.literal("confirm").executes(context -> uploadExport(
											context.getSource(), IntegerArgumentType.getInteger(context, "export"), true)))))
					.then(Commands.literal("mark-uploaded")
							.executes(context -> markUploaded(context.getSource(), VISITED.latestExport()))
							.then(Commands.argument("export", IntegerArgumentType.integer(1)).executes(context ->
									markUploaded(context.getSource(), IntegerArgumentType.getInteger(context, "export")))))
					.then(Commands.literal("on").executes(context -> setEnabled(context.getSource(), true)))
					.then(Commands.literal("off").executes(context -> setEnabled(context.getSource(), false)))
					.then(Commands.literal("overlay")
							.executes(context -> setShow(context.getSource(), !SETTINGS.showOnMap()))
							.then(Commands.literal("on").executes(context -> setShow(context.getSource(), true)))
							.then(Commands.literal("off").executes(context -> setShow(context.getSource(), false)))));
	}

	static void tick(Minecraft client) {
		if (pending != null) {
			PendingExport export = pending;
			pending = null;
			Component error = XaeroExport.start(client, export.only(), export.record(), export.mode(), null);
			if (error != null) {
				Chat.send(client, error);
			}
		}
	}

	private static int showCounts(CommandSourceStack source) {
		String server = VISITED.server();
		if (server == null) {
			source.sendSystemMessage(Chat.error("Not connected to a world."));
			return 0;
		}
		Map<String, Integer> counts = VISITED.counts();
		counts.values().removeIf(count -> count == 0);
		MutableComponent message = Chat.info("Chunks recorded on " + server.substring(server.indexOf('/') + 1) + ":");
		if (counts.isEmpty()) {
			message.append(Chat.line("none yet"));
		}
		counts.forEach((key, count) -> message.append(Chat.line(VisitedChunks.displayName(key) + ": " + count
				+ (VisitedChunks.isCaveMode(key) ? " (" + VisitedChunks.shortName(key) + ")" : ""))));
		source.sendSystemMessage(message);
		return 1;
	}

	private static int clear(CommandSourceStack source, boolean confirm) {
		if (VISITED.server() == null || Minecraft.getInstance().level == null) {
			source.sendSystemMessage(Chat.error("Not connected to a world."));
			return 0;
		}
		ResourceKey<Level> dimension = Minecraft.getInstance().level.dimension();
		String id = dimension.location().toString();
		if (!confirm) {
			source.sendSystemMessage(Chat.warning("This will forget the " + VISITED.count(dimension) + " chunks"
					+ " recorded in " + id + ", so the next export starts from scratch.")
					.append(Chat.buttons(Chat.button("Confirm", ChatFormatting.RED, "/visited clear confirm",
							"Forget the record for " + id))));
			return 1;
		}
		int forgotten = VISITED.clear(dimension);
		refreshOverlay();
		source.sendSystemMessage(Chat.success("Forgot " + forgotten + " chunks recorded in " + id + ".")
				.append(Chat.line("Zoomed-out world map views may show the old tint until you zoom in on them once.")));
		return 1;
	}

	private static int clearOthers(CommandSourceStack source, String record, boolean confirm) {
		if (VISITED.server() == null) {
			source.sendSystemMessage(Chat.error("Not connected to a world."));
			return 0;
		}
		List<String> others = XaeroExport.sameMap(record);
		String map = WebmapUpload.webmapName(record);
		if (others.isEmpty()) {
			source.sendSystemMessage(Chat.info("Nothing else recorded goes to the " + map + " map."));
			return 1;
		}
		String names = String.join(", ", others.stream().map(VisitedChunks::displayName).toList());
		if (!confirm) {
			int total = others.stream().mapToInt(key -> VISITED.snapshot(key).size()).sum();
			source.sendSystemMessage(Chat.warning("This will forget the " + total + " chunks recorded in " + names + ".")
					.append(Chat.buttons(Chat.button("Confirm", ChatFormatting.RED, "/visited clear others "
							+ VisitedChunks.shortName(record) + " confirm", "Forget " + names))));
			return 1;
		}
		int forgotten = others.stream().mapToInt(VISITED::clearRecord).sum();
		refreshOverlay();
		source.sendSystemMessage(Chat.success("Forgot " + forgotten + " chunks recorded in " + names + "."));
		return 1;
	}

	private static int clearAll(CommandSourceStack source, boolean confirm) {
		if (VISITED.server() == null) {
			source.sendSystemMessage(Chat.error("Not connected to a world."));
			return 0;
		}
		if (!confirm) {
			int total = VISITED.counts().values().stream().mapToInt(Integer::intValue).sum();
			source.sendSystemMessage(Chat.warning("This will forget all " + total + " chunks recorded on this server,"
					+ " in every dimension, so the next export starts from scratch.")
					.append(Chat.buttons(Chat.button("Confirm", ChatFormatting.RED, "/visited clear all confirm",
							"Forget the record in every dimension"))));
			return 1;
		}
		int forgotten = VISITED.clearAll();
		refreshOverlay();
		source.sendSystemMessage(Chat.success("Forgot " + forgotten + " chunks in every dimension.")
				.append(Chat.line("Zoomed-out world map views may show the old tint until you zoom in on them once.")));
		return 1;
	}

	private static int markUploaded(CommandSourceStack source, int export) {
		if (export < 0) {
			source.sendSystemMessage(Chat.error("There's no export to mark as uploaded. To start over, use /visited"
					+ " clear."));
			return 0;
		}
		int forgotten = VISITED.markUploaded(export);
		if (forgotten < 0) {
			source.sendSystemMessage(Chat.error("That export was already marked as uploaded, or its record was cleared"
					+ " since. To start over, use /visited clear."));
			return 0;
		}
		refreshOverlay();
		source.sendSystemMessage(Chat.success("Marked as uploaded: forgot " + forgotten + " chunks.")
				.append(Chat.line("Anything that changed since that export is kept for the next one.")));
		return 1;
	}

	private static int setEnabled(CommandSourceStack source, boolean enabled) {
		SETTINGS.setEnabled(enabled);
		refreshOverlay();
		source.sendSystemMessage(Chat.info(enabled
				? "AOMC Visited is on: recording what Xaero's World Map maps. Turn it off with /visited off."
				: "AOMC Visited is off: nothing is recorded or shown, and what's already recorded is kept. Turn it"
						+ " back on with /visited on."));
		return 1;
	}

	private static int setShow(CommandSourceStack source, boolean show) {
		SETTINGS.setShowOnMap(show);
		refreshOverlay();
		source.sendSystemMessage(Chat.info(show
				? "Showing what the next export will keep on Xaero's World Map. Hide it with /visited overlay off."
				: "No longer showing the record on Xaero's World Map. Show it again with /visited overlay on."));
		return 1;
	}

	/** Marks commands under {@code record <name>}, whose record comes from the argument. */
	private static final String RECORD = "record";

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> recordArgument() {
		return Commands.argument("name", StringArgumentType.word());
	}

	private static String record(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String name = StringArgumentType.getString(context, "name");
		String key = VisitedChunks.keyOf(name);
		if (key == null) {
			throw new SimpleCommandExceptionType(Component.literal("No record called " + name + "; see /visited")).create();
		}
		return key;
	}

	private static String only(CommandContext<CommandSourceStack> context, String only) {
		return RECORD.equals(only) ? null : only;
	}

	private static String record(CommandContext<CommandSourceStack> context, String only) throws CommandSyntaxException {
		return RECORD.equals(only) ? record(context) : null;
	}

	private static <S extends com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, S>> S exportCommand(
			S command, String only) {
		return command.executes(context -> requestExport(context.getSource(), only(context, only), record(context, only), false))
				.then(Commands.literal("not-for-webmap").executes(context ->
						requestExport(context.getSource(), only(context, only), record(context, only), true)))
				.then(Commands.literal("without-packs").executes(context ->
						exportWithoutPacks(context.getSource(), only(context, only), record(context, only), false)));
	}

	private static <S extends com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, S>> S uploadCommand(
			S command, String only) {
		return command.executes(context -> requestUpload(context.getSource(), only(context, only), record(context, only), false))
				.then(Commands.literal("confirm").executes(context ->
						requestUpload(context.getSource(), only(context, only), record(context, only), true)))
				.then(Commands.literal("without-packs").executes(context ->
						exportWithoutPacks(context.getSource(), only(context, only), record(context, only), true)));
	}

	private static String target(String only, String record) {
		return record != null ? " record " + VisitedChunks.shortName(record) : only != null ? " " + VisitedChunks.shortName(only) : "";
	}

	private static String what(String only, String record) {
		return record != null ? VisitedChunks.displayName(record) : only != null ? VisitedChunks.shortName(only)
				: "everything recorded";
	}

	/** {@code /visited upload [<dimension>]}: exports, then uploads to the webmap and marks as uploaded. */
	private static int requestUpload(CommandSourceStack source, String only, String record, boolean confirmed) {
		if (!xaeroInstalled()) {
			source.sendSystemMessage(Chat.error("Uploading needs Xaero's World Map."));
			return 0;
		}
		if (WebmapUpload.running()) {
			source.sendSystemMessage(Chat.error("An upload is already running."));
			return 0;
		}
		List<String> packs = ResourcePacks.playerPacks(Minecraft.getInstance());
		String command = "/visited upload" + target(only, record);
		String what = what(only, record);
		if (!packs.isEmpty()) {
			source.sendSystemMessage(Chat.warning("Resource packs change the colours of the map, so they have to be off for"
					+ " an upload. These are on:")
					.append(Chat.line(String.join(", ", packs)))
					.append(Chat.buttons(Chat.button("Turn packs off and upload", ChatFormatting.GREEN,
							command + " without-packs", "Turns your resource packs off, exports " + what + ", turns them"
									+ " back on, then uploads to " + WebmapUpload.HOST + " and marks it as uploaded"))));
			return 0;
		}
		if (!confirmed) {
			source.sendSystemMessage(Chat.info("Export and upload " + what + " to " + WebmapUpload.HOST + "? It's marked as"
					+ " uploaded once the webmap has processed it.")
					.append(Chat.buttons(Chat.button("Confirm", ChatFormatting.GREEN, command + " confirm",
							"Export and upload " + what))));
			return 1;
		}
		pending = new PendingExport(only, record, XaeroExport.Mode.UPLOAD);
		return 1;
	}

	/** {@code /visited upload <export>}: the [Upload] button on an export made this session. */
	private static int uploadExport(CommandSourceStack source, int number, boolean confirmed) {
		WebmapUpload.Dimension export = WebmapUpload.export(number);
		if (export == null) {
			source.sendSystemMessage(Chat.error("That export's images aren't known any more: it's from an earlier session,"
					+ " or a newer export replaced it. Use /visited upload to export and upload again."));
			return 0;
		}
		if (WebmapUpload.running()) {
			source.sendSystemMessage(Chat.error("An upload is already running."));
			return 0;
		}
		String what = export.images().size() + " images to the " + export.map() + " map";
		if (!confirmed) {
			source.sendSystemMessage(Chat.info("Upload " + what + " on " + WebmapUpload.HOST + "? They're marked as uploaded once"
					+ " the webmap has processed them.")
					.append(Chat.buttons(Chat.button("Confirm", ChatFormatting.GREEN, "/visited upload " + number
							+ " confirm", "Upload " + what))));
			return 1;
		}
		if (!export.images().stream().allMatch(java.nio.file.Files::isRegularFile)) {
			source.sendSystemMessage(Chat.error("Some of that export's images were moved or deleted. Use /visited upload"
					+ " to export and upload again."));
			return 0;
		}
		WebmapUpload.start(Minecraft.getInstance(), List.of(export));
		return 1;
	}

	private static int requestExport(CommandSourceStack source, String only, String record, boolean withPacks) {
		if (!xaeroInstalled()) {
			source.sendSystemMessage(Chat.error("Exporting needs Xaero's World Map."));
			return 0;
		}
		List<String> packs = ResourcePacks.playerPacks(Minecraft.getInstance());
		String command = "/visited export" + target(only, record);
		if (!packs.isEmpty() && !withPacks) {
			source.sendSystemMessage(Chat.warning("Resource packs change the colours of the exported map. These are on:")
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
		pending = new PendingExport(only, record, withPacks ? XaeroExport.Mode.NOT_FOR_WEBMAP : XaeroExport.Mode.EXPORT);
		return 1;
	}

	private static int exportWithoutPacks(CommandSourceStack source, String only, String record, boolean upload) {
		if (!xaeroInstalled()) {
			source.sendSystemMessage(Chat.error("Exporting needs Xaero's World Map."));
			return 0;
		}
		if (XaeroExport.active()) {
			source.sendSystemMessage(Chat.error("An export is already running."));
			return 0;
		}
		if (upload && WebmapUpload.running()) {
			source.sendSystemMessage(Chat.error("An upload is already running."));
			return 0;
		}
		Minecraft client = Minecraft.getInstance();
		XaeroExport.Mode mode = upload ? XaeroExport.Mode.UPLOAD : XaeroExport.Mode.EXPORT;
		if (ResourcePacks.playerPacks(client).isEmpty()) {
			pending = new PendingExport(only, record, mode);
			return 1;
		}
		source.sendSystemMessage(Chat.info("Turning your resource packs off for the export. They'll come back on"
				+ " afterwards, in the same order.")
				.append(Chat.line("Right after turning packs off, Xaero can take up to a minute to export, and the"
						+ " game may freeze meanwhile.")));
		ResourcePacks.turnOff(client, restore -> {
			Component error = XaeroExport.start(client, only, record, mode, restore);
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
