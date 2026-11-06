package io.github.sh4sh.aomcvisited;

import java.util.Set;

/** The server and webmap this build is for. */
final class Target {
	static final String HOST = "create.diorite.xyz";
	static final String SERVER_NAME = "AOMC Create";
	/** Record folders of the survival server the webmap shows; its creative server is on the same host. */
	static final Set<String> SERVERS = Set.of("server/create.diorite.xyz", "server/gw.algot.net_25037");
	static final Set<String> SURFACE_ONLY = Set.of();

	private Target() {
	}
}
