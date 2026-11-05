package io.github.sh4sh.aomcvisited;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.MutableComponent;
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
		NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class,
				event -> VisitedCommands.register(event.getDispatcher()));
		if (xaeroInstalled()) {
			VisitedHighlighter.registerEvents();
		}

		LOGGER.info("AOMC Webmapper loaded");
	}

	private void tick(Minecraft client) {
		if (xaeroInstalled()) {
			XaeroExport.tick();
			if (ticksSinceSave % 20 == 0) {
				VisitedHighlighter.refreshChanged();
			}
		}
		VisitedCommands.tick(client);
		if (reminderTicks > 0 && --reminderTicks == 0) {
			remindOfEarlierGround(client);
		}
		if (++ticksSinceSave >= AUTOSAVE_TICKS) {
			ticksSinceSave = 0;
			VISITED.save();
		}
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

	/** Classes that use Xaero ({@link XaeroExport}, {@link VisitedHighlighter}) may only load when this is true. */
	static boolean xaeroInstalled() {
		return ModList.get().isLoaded("xaeroworldmap");
	}
}
