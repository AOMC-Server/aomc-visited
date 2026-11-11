package io.github.sh4sh.aomcvisited;

import java.util.Set;

/** The server and webmap this build is for. */
final class Target {
	static final String HOST = "aomc-map.algot.net";
	static final String SERVER_NAME = "AOMC Vanilla";
	static final Set<String> SERVERS = Set.of("server/mc.aboutoliver.com", "server/mc.diorite.xyz",
			"server/161.129.180.145_25565", "server/161.129.180.145");
	/** Dimensions the webmap only has with cave mode off: AOMC Vanilla's Nether map is the roof. */
	static final Set<String> SURFACE_ONLY = Set.of("minecraft:the_nether");

	private Target() {
	}
}
