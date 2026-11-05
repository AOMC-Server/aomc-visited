package io.github.sh4sh.aomcvisited;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.gui.ExportScreen;
import xaero.map.highlight.ChunkHighlighter;
import xaero.map.highlight.HighlighterRegistry;
import xaero.map.world.MapDimension;

import java.util.List;
import java.util.Map;

public final class VisitedHighlighter extends ChunkHighlighter {
	/** Colours are 0xRRGGBBAA, as in Xaero's claims highlighter. */
	private static final int CHANGED = 0x40FF4060;
	private static final int UNCHANGED = 0x00000000;
	private static final int EDGE = 0x40FF40C0;
	private static final Component CHANGED_TOOLTIP = Component.literal("New or changed: will be exported");
	private static final Component UNCHANGED_TOOLTIP = Component.literal("Unchanged since you last mapped it: will be exported");

	/** Xaero's "Include Active Highlights" would bake our tint into exactly the chunks we keep. */
	private static volatile boolean exportScreenOpen;

	private VisitedHighlighter() {
		super(false);
	}

	public static void register(HighlighterRegistry registry) {
		registry.register(new VisitedHighlighter());
	}

	static void registerEvents() {
		NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, event -> {
			// Our own export turns highlights off in the exporter: hiding them would make Xaero reload every region.
			if (event.getScreen() instanceof ExportScreen && !XaeroExport.active()) {
				exportScreenOpen = true;
				refreshAll();
			}
		});
		NeoForge.EVENT_BUS.addListener(ScreenEvent.Closing.class, event -> {
			if (event.getScreen() instanceof ExportScreen && exportScreenOpen) {
				exportScreenOpen = false;
				refreshAll();
			}
		});
	}

	private static int shownLayer = VisitedChunks.SURFACE_LAYER;

	static void refreshAll() {
		MapProcessor processor = processor();
		if (processor != null && processor.getMapWorld() != null) {
			processor.getMapWorld().clearAllCachedHighlightHashes();
		}
	}

	/** Called once a second, not per chunk, so a flight doesn't keep restarting Xaero's redraw. */
	static void refreshChanged() {
		Map<String, LongOpenHashSet> changed = AomcVisitedClient.VISITED.takeChangedRegions();
		MapProcessor processor = processor();
		if (processor == null || processor.getMapWorld() == null) {
			return;
		}
		// Xaero caches highlight hashes per region, not per cave mode.
		int layer = processor.getCurrentCaveLayer();
		if (layer != shownLayer) {
			shownLayer = layer;
			processor.getMapWorld().clearAllCachedHighlightHashes();
			return;
		}
		if (changed.isEmpty()) {
			return;
		}
		changed.forEach((key, regions) -> {
			MapDimension mapDimension = processor.getMapWorld()
					.getDimension(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(VisitedChunks.dimensionOf(key))));
			if (mapDimension != null) {
				regions.forEach(region -> mapDimension.getHighlightHandler()
						.clearCachedHash(ChunkPos.getX(region), ChunkPos.getZ(region)));
			}
		});
	}

	private static MapProcessor processor() {
		WorldMapSession session = WorldMapSession.getCurrentSession();
		return session == null ? null : session.getMapProcessor();
	}

	private static boolean active() {
		return !exportScreenOpen && AomcVisitedClient.SETTINGS.enabled() && AomcVisitedClient.SETTINGS.showOnMap();
	}

	private static ResourceKey<Level> keyDimension;
	private static int keyLayer;
	private static String key;

	/** Called several times per chunk while Xaero draws, so the last key is reused. */
	private static String key(ResourceKey<Level> dimension) {
		if (dimension != keyDimension || shownLayer != keyLayer) {
			keyDimension = dimension;
			keyLayer = shownLayer;
			key = VisitedChunks.recordKey(dimension.location().toString(), shownLayer);
		}
		return key;
	}

	private static boolean recorded(ResourceKey<Level> dimension, int x, int z) {
		return AomcVisitedClient.VISITED.contains(key(dimension), x, z);
	}

	@Override
	public int calculateRegionHash(ResourceKey<Level> dimension, int regionX, int regionZ) {
		return regionHasHighlights(dimension, regionX, regionZ)
				? AomcVisitedClient.VISITED.regionHash(key(dimension), regionX, regionZ) : 0;
	}

	@Override
	public boolean regionHasHighlights(ResourceKey<Level> dimension, int regionX, int regionZ) {
		return active() && AomcVisitedClient.VISITED.regionCount(key(dimension), regionX, regionZ) > 0;
	}

	@Override
	public boolean chunkIsHighlit(ResourceKey<Level> dimension, int x, int z) {
		return active() && recorded(dimension, x, z);
	}

	/** Centre, then the top (north), right (east), bottom (south) and left (west) edges. */
	@Override
	protected int[] getColors(ResourceKey<Level> dimension, int x, int z) {
		if (!chunkIsHighlit(dimension, x, z)) {
			return null;
		}
		int fill = AomcVisitedClient.VISITED.isChanged(key(dimension), x, z) ? CHANGED : UNCHANGED;
		return new int[] {
				fill,
				recorded(dimension, x, z - 1) ? fill : EDGE,
				recorded(dimension, x + 1, z) ? fill : EDGE,
				recorded(dimension, x, z + 1) ? fill : EDGE,
				recorded(dimension, x - 1, z) ? fill : EDGE,
		};
	}

	@Override
	public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> dimension, int x, int z) {
		if (!chunkIsHighlit(dimension, x, z)) {
			return null;
		}
		return AomcVisitedClient.VISITED.isChanged(key(dimension), x, z) ? CHANGED_TOOLTIP : UNCHANGED_TOOLTIP;
	}

	@Override
	public Component getChunkHighlightBluntTooltip(ResourceKey<Level> dimension, int x, int z) {
		return null;
	}

	@Override
	public void addMinimapBlockHighlightTooltips(List<Component> list, ResourceKey<Level> dimension, int blockX, int blockZ, int width) {
	}
}
