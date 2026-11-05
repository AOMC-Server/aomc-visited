package io.github.sh4sh.aomcvisited;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static io.github.sh4sh.aomcvisited.AomcVisitedClient.LOGGER;

final class ResourcePacks {
	private ResourcePacks() {
	}

	static List<String> playerPacks(Minecraft client) {
		// The player's own packs are "file/<name>"; their names can contain colour codes.
		return client.getResourcePackRepository().getSelectedIds().stream()
				.filter(id -> id.startsWith("file/"))
				.map(id -> ChatFormatting.stripFormatting(id.substring("file/".length()))).toList();
	}

	static void turnOff(Minecraft client, Consumer<Runnable> whileOff) {
		PackRepository repository = client.getResourcePackRepository();
		// In order: the list's order is the packs' priority, and Minecraft keeps the order it's given.
		List<String> original = List.copyOf(repository.getSelectedIds());
		repository.setSelected(original.stream().filter(id -> !id.startsWith("file/")).toList());
		AtomicBoolean restored = new AtomicBoolean();
		Runnable restoreOnce = () -> {
			if (restored.compareAndSet(false, true)) {
				restore(client, original);
			}
		};
		long reloading = System.currentTimeMillis();
		client.reloadResourcePacks().whenComplete((ignored, failure) -> client.execute(() -> {
			if (failure != null) {
				LOGGER.error("[AOMC Webmapper] export: reloading without packs failed", failure);
				Chat.send(client, Chat.error("Minecraft couldn't reload without your resource packs, so nothing was"
						+ " exported."));
				restoreOnce.run();
				return;
			}
			LOGGER.info("[AOMC Webmapper] export: packs off, reload took {} ms", System.currentTimeMillis() - reloading);
			whileOff.accept(restoreOnce);
		}));
	}

	private static void restore(Minecraft client, List<String> original) {
		PackRepository repository = client.getResourcePackRepository();
		repository.setSelected(original);
		long reloading = System.currentTimeMillis();
		client.reloadResourcePacks().whenComplete((ignored, failure) -> client.execute(() -> {
			if (failure != null) {
				LOGGER.error("[AOMC Webmapper] export: reloading with packs back on failed", failure);
				Chat.send(client, Chat.warning("Your resource packs are back on, but Minecraft couldn't reload them:"
						+ " please check Options > Resource Packs."));
				return;
			}
			LOGGER.info("[AOMC Webmapper] export: packs back on, reload took {} ms", System.currentTimeMillis() - reloading);
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
}
