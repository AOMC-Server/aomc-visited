package io.github.sh4sh.aomcvisited;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@Mod(value = AomcVisitedClient.MOD_ID, dist = Dist.CLIENT)
public class AomcVisitedClient {
	public static final String MOD_ID = "aomc_visited";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int AUTOSAVE_TICKS = 60 * 20;

	public static final VisitedChunks VISITED =
			new VisitedChunks(FMLPaths.GAMEDIR.get().resolve(MOD_ID));
	public static final Settings SETTINGS =
			new Settings(FMLPaths.GAMEDIR.get().resolve(MOD_ID).resolve("settings.properties"));

	private int ticksSinceSave;
	/** The export starts a tick after the command, once the chat screen has closed. */
	private boolean exportNextTick;
	private String exportOnly;
	private boolean exportWithPacks;
	private boolean exportForUpload;
	private int reminderTicks;

	private static final int REMINDER_DELAY_TICKS = 5 * 20;

	public AomcVisitedClient() {
		NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, event -> {
			VISITED.open(VisitedChunks.serverKey(Minecraft.getInstance()));
			reminderTicks = REMINDER_DELAY_TICKS;
		});
		NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> {
			VISITED.close();
			if (xaeroInstalled()) {
				XaeroExport.reset();
			}
		});
		NeoForge.EVENT_BUS.addListener(GameShuttingDownEvent.class, event -> VISITED.close());
		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick(Minecraft.getInstance()));
		NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, event -> event.getDispatcher().register(
				Commands.literal("visited")
						.executes(context -> showCounts(context.getSource()))
						.then(exportCommand(Commands.literal("export"), null)
								.then(exportCommand(Commands.literal("overworld"), "minecraft:overworld"))
								.then(exportCommand(Commands.literal("the_nether"), "minecraft:the_nether"))
								.then(exportCommand(Commands.literal("the_end"), "minecraft:the_end")))
						.then(Commands.literal("clear")
								.executes(context -> clear(context.getSource(), false))
								.then(Commands.literal("confirm").executes(context -> clear(context.getSource(), true)))
								.then(Commands.literal("all")
										.executes(context -> clearAll(context.getSource(), false))
										.then(Commands.literal("confirm").executes(context -> clearAll(context.getSource(), true)))))
						.then(uploadCommand(Commands.literal("upload"), null)
								.then(uploadCommand(Commands.literal("overworld"), "minecraft:overworld"))
								.then(uploadCommand(Commands.literal("the_nether"), "minecraft:the_nether"))
								.then(uploadCommand(Commands.literal("the_end"), "minecraft:the_end"))
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
								.then(Commands.literal("off").executes(context -> setShow(context.getSource(), false))))));
		if (xaeroInstalled()) {
			VisitedHighlighter.registerEvents();
		}

		LOGGER.info("AOMC Visited loaded");
	}

	private void tick(Minecraft client) {
		if (xaeroInstalled()) {
			XaeroExport.tick();
			if (ticksSinceSave % 20 == 0) {
				VisitedHighlighter.refreshChanged();
			}
		}
		if (exportNextTick) {
			exportNextTick = false;
			Component error = XaeroExport.start(client, exportOnly, exportForUpload ? XaeroExport.Mode.UPLOAD
					: exportWithPacks ? XaeroExport.Mode.NOT_FOR_WEBMAP : XaeroExport.Mode.EXPORT, null);
			if (error != null && client.player != null) {
				client.player.sendSystemMessage(error);
			}
		}
		if (reminderTicks > 0 && --reminderTicks == 0) {
			remindOfEarlierGround(client);
		}
		if (++ticksSinceSave >= AUTOSAVE_TICKS) {
			ticksSinceSave = 0;
			VISITED.save();
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
		counts.forEach((key, count) -> message.append(Chat.line(VisitedChunks.shortName(key) + ": " + count)));
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
		if (xaeroInstalled()) {
			VisitedHighlighter.refreshAll();
		}
		source.sendSystemMessage(Chat.success("Forgot " + forgotten + " chunks recorded in " + id + ".")
				.append(Chat.line("Zoomed-out world map views may show the old tint until you zoom in on them once.")));
		return 1;
	}

	private static void remindOfEarlierGround(Minecraft client) {
		if (client.player == null || !SETTINGS.enabled()) {
			return;
		}
		List<VisitedChunks.Earlier> earlier = VISITED.earlier();
		long away = System.currentTimeMillis() - VISITED.lastHere();
		if (earlier.isEmpty() || away <= VisitedChunks.OUT_OF_DATE_MS) {
			return;
		}
		MutableComponent message = Chat.warning("Map data from " + ago(away)
				+ " isn't uploaded yet, and may be out of date:");
		for (VisitedChunks.Earlier record : earlier) {
			message.append(Chat.line(VisitedChunks.shortName(record.key()) + ": " + record.chunks() + " chunks"));
		}
		message.append(Chat.line("If you keep playing, your next export includes it."));
		Chat.send(client, message.append(Chat.buttons(
				Chat.button("Export it now", ChatFormatting.GREEN, "/visited export",
						"Export it now, or later: it stays in the record either way"),
				Chat.button("Clear old session", ChatFormatting.RED, "/visited clear all",
						"Forget it in every dimension (asks to confirm)"))));
	}

	private static String ago(long ms) {
		long minutes = Math.max(1, ms / 60_000);
		if (minutes < 60) {
			return minutes == 1 ? "a minute ago" : minutes + " minutes ago";
		}
		long hours = minutes / 60;
		if (hours < 24) {
			return hours == 1 ? "an hour ago" : hours + " hours ago";
		}
		long days = hours / 24;
		return days == 1 ? "a day ago" : days + " days ago";
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
		if (xaeroInstalled()) {
			VisitedHighlighter.refreshAll();
		}
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
		if (xaeroInstalled()) {
			VisitedHighlighter.refreshAll();
		}
		source.sendSystemMessage(Chat.success("Marked as uploaded: forgot " + forgotten + " chunks.")
				.append(Chat.line("Anything that changed since that export is kept for the next one.")));
		return 1;
	}

	private static int setEnabled(CommandSourceStack source, boolean enabled) {
		SETTINGS.setEnabled(enabled);
		if (xaeroInstalled()) {
			VisitedHighlighter.refreshAll();
		}
		source.sendSystemMessage(Chat.info(enabled
				? "AOMC Visited is on: recording what Xaero's World Map maps. Turn it off with /visited off."
				: "AOMC Visited is off: nothing is recorded or shown, and what's already recorded is kept. Turn it"
						+ " back on with /visited on."));
		return 1;
	}

	private static int setShow(CommandSourceStack source, boolean show) {
		SETTINGS.setShowOnMap(show);
		if (xaeroInstalled()) {
			VisitedHighlighter.refreshAll();
		}
		source.sendSystemMessage(Chat.info(show
				? "Showing what the next export will keep on Xaero's World Map. Hide it with /visited overlay off."
				: "No longer showing the record on Xaero's World Map. Show it again with /visited overlay on."));
		return 1;
	}

	private <S extends com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, S>> S exportCommand(
			S command, String only) {
		return command.executes(context -> requestExport(context.getSource(), only, false))
				.then(Commands.literal("not-for-webmap").executes(context -> requestExport(context.getSource(), only, true)))
				.then(Commands.literal("without-packs")
						.executes(context -> exportWithoutPacks(context.getSource(), only, false)));
	}

	private <S extends com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, S>> S uploadCommand(
			S command, String only) {
		return command.executes(context -> requestUpload(context.getSource(), only, false))
				.then(Commands.literal("confirm").executes(context -> requestUpload(context.getSource(), only, true)))
				.then(Commands.literal("without-packs")
						.executes(context -> exportWithoutPacks(context.getSource(), only, true)));
	}

	/** {@code /visited upload [<dimension>]}: exports, then uploads to the webmap and marks as uploaded. */
	private int requestUpload(CommandSourceStack source, String only, boolean confirmed) {
		if (!xaeroInstalled()) {
			source.sendSystemMessage(Chat.error("Uploading needs Xaero's World Map."));
			return 0;
		}
		if (WebmapUpload.running()) {
			source.sendSystemMessage(Chat.error("An upload is already running."));
			return 0;
		}
		List<String> packs = playerPacks(Minecraft.getInstance());
		String command = "/visited upload" + (only == null ? "" : " " + VisitedChunks.shortName(only));
		String what = only == null ? "everything recorded" : VisitedChunks.shortName(only);
		if (!packs.isEmpty()) {
			source.sendSystemMessage(Chat.warning("Resource packs change the colours of the map, so they have to be off for"
					+ " an upload. These are on:")
					.append(Chat.line(String.join(", ", packs)))
					.append(Chat.buttons(Chat.button("Turn packs off and upload", ChatFormatting.GREEN,
							command + " without-packs", "Turns your resource packs off, exports " + what + ", turns them"
									+ " back on, then uploads to create.diorite.xyz and marks it as uploaded"))));
			return 0;
		}
		if (!confirmed) {
			source.sendSystemMessage(Chat.info("Export and upload " + what + " to create.diorite.xyz? It's marked as"
					+ " uploaded once the webmap has processed it.")
					.append(Chat.buttons(Chat.button("Confirm", ChatFormatting.GREEN, command + " confirm",
							"Export and upload " + what))));
			return 1;
		}
		exportNextTick = true;
		exportOnly = only;
		exportWithPacks = false;
		exportForUpload = true;
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
		String what = export.images().size() + " images of " + VisitedChunks.shortName(export.key());
		if (!confirmed) {
			source.sendSystemMessage(Chat.info("Upload " + what + " to create.diorite.xyz? They're marked as uploaded once"
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

	private int requestExport(CommandSourceStack source, String only, boolean withPacks) {
		if (!xaeroInstalled()) {
			source.sendSystemMessage(Chat.error("Exporting needs Xaero's World Map."));
			return 0;
		}
		List<String> packs = playerPacks(Minecraft.getInstance());
		String command = "/visited export" + (only == null ? "" : " " + VisitedChunks.shortName(only));
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
		exportNextTick = true;
		exportOnly = only;
		exportWithPacks = withPacks;
		exportForUpload = false;
		return 1;
	}

	private static List<String> playerPacks(Minecraft client) {
		// The player's own packs are "file/<name>"; their names can contain colour codes.
		return client.getResourcePackRepository().getSelectedIds().stream()
				.filter(id -> id.startsWith("file/"))
				.map(id -> ChatFormatting.stripFormatting(id.substring("file/".length()))).toList();
	}

	private static int exportWithoutPacks(CommandSourceStack source, String only, boolean upload) {
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
		PackRepository repository = client.getResourcePackRepository();
		// In order: the list's order is the packs' priority, and Minecraft keeps the order it's given.
		List<String> original = List.copyOf(repository.getSelectedIds());
		repository.setSelected(original.stream().filter(id -> !id.startsWith("file/")).toList());
		source.sendSystemMessage(Chat.info("Turning your resource packs off for the export. They'll come back on"
				+ " afterwards, in the same order.")
				.append(Chat.line("Right after turning packs off, Xaero can take up to a minute to export, and the"
						+ " game may freeze meanwhile.")));
		long reloading = System.currentTimeMillis();
		client.reloadResourcePacks().thenRun(() -> client.execute(() -> {
			LOGGER.info("[AOMC Visited] export: packs off, reload took {} ms", System.currentTimeMillis() - reloading);
			Component error = XaeroExport.start(client, only, upload ? XaeroExport.Mode.UPLOAD : XaeroExport.Mode.EXPORT,
					() -> restorePacks(client, original));
			if (error != null) {
				if (client.player != null) {
					client.player.sendSystemMessage(error);
				}
				restorePacks(client, original);
			}
		}));
		return 1;
	}

	private static void restorePacks(Minecraft client, List<String> original) {
		PackRepository repository = client.getResourcePackRepository();
		repository.setSelected(original);
		long reloading = System.currentTimeMillis();
		client.reloadResourcePacks().thenRun(() -> client.execute(() -> {
			LOGGER.info("[AOMC Visited] export: packs back on, reload took {} ms", System.currentTimeMillis() - reloading);
			boolean sameOrder = List.copyOf(repository.getSelectedIds()).equals(original);
			if (!sameOrder) {
				LOGGER.warn("Resource packs restored in a different order: {} instead of {}",
						repository.getSelectedIds(), original);
			}
			Chat.send(client, sameOrder
					? Chat.success("Your resource packs are back on.")
					: Chat.warning("Your resource packs are back on, but their order may have changed: please check"
							+ " Options > Resource Packs."));
		}));
	}

	/** Classes that use Xaero ({@link XaeroExport}, {@link VisitedHighlighter}) may only load when this is true. */
	private static boolean xaeroInstalled() {
		return ModList.get().isLoaded("xaeroworldmap");
	}
}
