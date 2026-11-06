package io.github.sh4sh.aomcvisited;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.net.URI;
import java.nio.file.Path;

/** Everything that differs between Fabric 26.3 and NeoForge 1.21.1. The rest of the code is the same on both. */
public final class Platform implements ClientModInitializer {
	@Override
	@SuppressWarnings("unchecked")
	public void onInitializeClient() {
		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> AomcVisitedClient.joined(client));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> AomcVisitedClient.left());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> AomcVisitedClient.stopping());
		ClientTickEvents.END_CLIENT_TICK.register(AomcVisitedClient::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
				VisitedCommands.register((CommandDispatcher<Object>) (CommandDispatcher<?>) dispatcher));
		if (AomcVisitedClient.xaeroInstalled()) {
			ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
				if (VisitedHighlighter.exportScreenOpened(screen)) {
					ScreenEvents.remove(screen).register(removed -> VisitedHighlighter.exportScreenClosed());
				}
			});
		}
		AomcVisitedClient.LOGGER.info("AOMC Webmapper loaded");
	}

	static Path gameDir() {
		return FabricLoader.getInstance().getGameDir();
	}

	static boolean isLoaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	static String modVersion() {
		return FabricLoader.getInstance().getModContainer(AomcVisitedClient.MOD_ID)
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
	}

	static String id(ResourceKey<Level> dimension) {
		return dimension.identifier().toString();
	}

	static ResourceKey<Level> dimension(String id) {
		return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
	}

	static long chunk(int x, int z) {
		return ChunkPos.pack(x, z);
	}

	static ClickEvent runCommand(String command) {
		return new ClickEvent.RunCommand(command);
	}

	static ClickEvent openFile(Path path) {
		return new ClickEvent.OpenFile(path);
	}

	static ClickEvent openUrl(URI uri) {
		return new ClickEvent.OpenUrl(uri);
	}

	static HoverEvent showText(String text) {
		return new HoverEvent.ShowText(Component.literal(text));
	}

	static void showStatus(LocalPlayer player, Component text) {
		player.sendOverlayMessage(text);
	}

	static void openFolder(Path folder) {
		Blaze3D.openPath(folder);
	}

	static Screen screen(Minecraft client) {
		return client.gui.screen();
	}

	static void setScreen(Minecraft client, Screen screen) {
		client.gui.setScreen(screen);
	}
}
