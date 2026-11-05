package io.github.sh4sh.aomcvisited;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

import java.util.List;
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
		long reloading = System.currentTimeMillis();
		client.reloadResourcePacks().thenRun(() -> client.execute(() -> {
			LOGGER.info("[AOMC Visited] export: packs off, reload took {} ms", System.currentTimeMillis() - reloading);
			whileOff.accept(() -> restore(client, original));
		}));
	}

	private static void restore(Minecraft client, List<String> original) {
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
}
