package io.github.sh4sh.aomcvisited;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

import java.net.URI;
import java.nio.file.Path;

/** Everything that differs between Fabric 26.3 and NeoForge 1.21.1. The rest of the code is the same on both. */
@Mod(value = AomcVisitedClient.MOD_ID, dist = Dist.CLIENT)
public final class Platform {
	private static Screen exportScreen;

	@SuppressWarnings("unchecked")
	public Platform() {
		NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class,
				event -> AomcVisitedClient.joined(Minecraft.getInstance()));
		NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> AomcVisitedClient.left());
		NeoForge.EVENT_BUS.addListener(GameShuttingDownEvent.class, event -> AomcVisitedClient.stopping());
		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> AomcVisitedClient.tick(Minecraft.getInstance()));
		NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, event ->
				VisitedCommands.register((CommandDispatcher<Object>) (CommandDispatcher<?>) event.getDispatcher()));
		NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, event -> {
			if (VisitedHighlighter.exportScreenOpened(event.getScreen())) {
				exportScreen = event.getScreen();
			}
		});
		NeoForge.EVENT_BUS.addListener(ScreenEvent.Closing.class, event -> {
			if (event.getScreen() == exportScreen) {
				exportScreen = null;
				VisitedHighlighter.exportScreenClosed();
			}
		});
		AomcVisitedClient.LOGGER.info("AOMC Webmapper loaded");
	}

	static Path gameDir() {
		return FMLPaths.GAMEDIR.get();
	}

	static String modVersion() {
		return ModList.get().getModContainerById(AomcVisitedClient.MOD_ID)
				.map(mod -> mod.getModInfo().getVersion().toString()).orElse("unknown");
	}

	static String id(ResourceKey<Level> dimension) {
		return dimension.location().toString();
	}

	static ResourceKey<Level> dimension(String id) {
		return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id));
	}

	static long chunk(int x, int z) {
		return ChunkPos.asLong(x, z);
	}

	static ClickEvent runCommand(String command) {
		return new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
	}

	static ClickEvent openFile(Path path) {
		return new ClickEvent(ClickEvent.Action.OPEN_FILE, path.toString());
	}

	static ClickEvent openUrl(URI uri) {
		return new ClickEvent(ClickEvent.Action.OPEN_URL, uri.toString());
	}

	static HoverEvent showText(String text) {
		return new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(text));
	}

	static void showStatus(LocalPlayer player, Component text) {
		player.displayClientMessage(text, true);
	}

	static void openFolder(Path folder) {
		Util.getPlatform().openFile(folder.toFile());
	}

	static Screen screen(Minecraft client) {
		return client.screen;
	}

	static void setScreen(Minecraft client, Screen screen) {
		client.setScreen(screen);
	}
}
