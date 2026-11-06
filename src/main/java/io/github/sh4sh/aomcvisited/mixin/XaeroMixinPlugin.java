package io.github.sh4sh.aomcvisited.mixin;

import io.github.sh4sh.aomcvisited.XaeroHooks;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tells {@link XaeroHooks} which hooks didn't go in. Mixin can't be asked whether an injection succeeded, so this
 * looks for a call to each handler in the patched class. Errors in these mixins are only logged: the game still
 * starts, with the mod off.
 */
public class XaeroMixinPlugin implements IMixinConfigPlugin, IMixinErrorHandler {
	private static final String CONFIG = "aomc_visited.mixins.json";

	@Override
	public void onLoad(String mixinPackage) {
		Mixins.registerErrorHandlerClass(XaeroMixinPlugin.class.getName());
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		XaeroHooks.expect(mixinClassName, targetClassName);
		return true;
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		XaeroHooks.applied(mixinClassName);
		for (MethodNode handler : mixinInfo.getClassNode(0).methods) {
			if (isInjector(handler) && !called(targetClass, handler.name)) {
				XaeroHooks.broken(mixinInfo.getName() + "." + handler.name);
			}
		}
	}

	private static boolean isInjector(MethodNode method) {
		List<AnnotationNode> annotations = new ArrayList<>();
		if (method.visibleAnnotations != null) {
			annotations.addAll(method.visibleAnnotations);
		}
		if (method.invisibleAnnotations != null) {
			annotations.addAll(method.invisibleAnnotations);
		}
		return annotations.stream().anyMatch(annotation -> annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
				|| annotation.desc.startsWith("Lcom/llamalad7/mixinextras/injector/"));
	}

	/** Mixin renames handlers when it merges them, keeping the name as a suffix. */
	private static boolean called(ClassNode targetClass, String handler) {
		for (MethodNode method : targetClass.methods) {
			if (matches(method.name, handler)) {
				continue;
			}
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call && matches(call.name, handler)) {
					return true;
				}
				if (instruction instanceof InvokeDynamicInsnNode dynamic) {
					for (Object argument : dynamic.bsmArgs) {
						if (argument instanceof Handle target && matches(target.getName(), handler)) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	private static boolean matches(String name, String handler) {
		return name.equals(handler) || name.endsWith("$" + handler);
	}

	@Override
	public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
		return ours(config, mixin) ? ErrorAction.WARN : action;
	}

	@Override
	public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin, ErrorAction action) {
		return ours(mixin == null ? null : mixin.getConfig(), mixin) ? ErrorAction.WARN : action;
	}

	private static boolean ours(IMixinConfig config, IMixinInfo mixin) {
		if (config == null || mixin == null || !CONFIG.equals(config.getName())) {
			return false;
		}
		XaeroHooks.broken(mixin.getName());
		return true;
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
