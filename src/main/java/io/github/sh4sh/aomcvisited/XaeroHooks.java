package io.github.sh4sh.aomcvisited;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The mixins' hooks into Xaero that didn't go in. A Xaero update can move the code they hook into; the mod then
 * stays off rather than half working.
 */
public final class XaeroHooks {
	private static final Set<String> TARGETS = ConcurrentHashMap.newKeySet();
	private static final Set<String> EXPECTED = ConcurrentHashMap.newKeySet();
	private static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
	private static final Set<String> BROKEN = ConcurrentHashMap.newKeySet();
	private static final Queue<Runnable> SCANS = new ConcurrentLinkedQueue<>();

	private XaeroHooks() {
	}

	public static void expect(String mixin, String target) {
		EXPECTED.add(simpleName(mixin));
		TARGETS.add(target.replace('/', '.'));
	}

	public static void applied(String mixin) {
		APPLIED.add(simpleName(mixin));
	}

	/** Mixin's error handlers only give the mixin's simple name. */
	private static String simpleName(String mixin) {
		return mixin.substring(mixin.lastIndexOf('.') + 1);
	}

	public static void broken(String hook) {
		BROKEN.add(hook);
	}

	/** MixinExtras adds some hooks after the mixin is applied, so the patched class is scanned once it has loaded. */
	public static void scanWhenLoaded(Runnable scan) {
		SCANS.add(scan);
	}

	/** Mixins apply when a class loads, so this loads each class they hook into before the player joins. */
	static void check() {
		for (String target : TARGETS) {
			try {
				Class.forName(target, false, XaeroHooks.class.getClassLoader());
			} catch (ClassNotFoundException | LinkageError e) {
				BROKEN.add(target);
			}
		}
		for (Runnable scan; (scan = SCANS.poll()) != null; ) {
			scan.run();
		}
		for (String mixin : EXPECTED) {
			if (!APPLIED.contains(mixin)) {
				BROKEN.add(mixin);
			}
		}
		if (!BROKEN.isEmpty()) {
			AomcVisitedClient.LOGGER.warn("Off: these hooks into Xaero's World Map didn't go in: {}", BROKEN);
		}
	}

	static boolean working() {
		return BROKEN.isEmpty();
	}
}
