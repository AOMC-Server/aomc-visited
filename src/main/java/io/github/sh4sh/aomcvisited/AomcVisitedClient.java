package io.github.sh4sh.aomcvisited;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AomcVisitedClient {
	public static final String MOD_ID = "aomc_visited";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int AUTOSAVE_TICKS = 60 * 20;

	public static final VisitedChunks VISITED =
			new VisitedChunks(Platform.gameDir().resolve(MOD_ID));
	public static final Settings SETTINGS =
			new Settings(Platform.gameDir().resolve(MOD_ID).resolve("settings.properties"));

	private static int ticksSinceSave;
	private static int reminderTicks;

	private static final int REMINDER_DELAY_TICKS = 5 * 20;

	private AomcVisitedClient() {
	}

	static void joined(Minecraft client) {
		String server = VisitedChunks.serverKey(client);
		if (WebmapUpload.forWebmap(server)) {
			VISITED.open(server);
		}
		reminderTicks = REMINDER_DELAY_TICKS;
	}

	static void left() {
		VISITED.close();
		if (xaeroInstalled()) {
			XaeroExport.reset();
		}
	}

	static void stopping() {
		VISITED.close();
	}

	static void tick(Minecraft client) {
		if (xaeroInstalled()) {
			XaeroExport.tick();
			if (ticksSinceSave % 20 == 0) {
				VisitedHighlighter.refreshChanged();
			}
		}
		VisitedCommands.tick(client);
		if (reminderTicks > 0 && --reminderTicks == 0) {
			greet(client);
		}
		if (++ticksSinceSave >= AUTOSAVE_TICKS) {
			ticksSinceSave = 0;
			VISITED.save();
		}
	}

	/** Servers already told this launch that the mod is off there. */
	private static final Set<String> TOLD_OFF = new HashSet<>();

	private static void greet(Minecraft client) {
		if (VISITED.server() != null) {
			remindOfEarlierGround(client);
			return;
		}
		if (client.player == null || !SETTINGS.enabled()) {
			return;
		}
		String server = VisitedChunks.serverKey(client);
		if (!WebmapUpload.known(server) && TOLD_OFF.add(server)) {
			Chat.send(client, WebmapUpload.offHere()
					.append(Chat.line("To use it on another server, get in touch on GitHub."))
					.append(Chat.buttons(Chat.linkButton("Open GitHub", WebmapUpload.ISSUES))));
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
		message.append(Chat.line("If you keep playing, your next upload includes it."));
		Chat.send(client, message.append(Chat.buttons(
				Chat.button("Upload it now", ChatFormatting.GREEN, "/webmapper upload",
						"Upload it now, or later: it stays in the record either way (asks to confirm first)"),
				Chat.button("Clear old session", ChatFormatting.RED, "/webmapper clear all",
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
		return Platform.isLoaded("xaeroworldmap");
	}
}
