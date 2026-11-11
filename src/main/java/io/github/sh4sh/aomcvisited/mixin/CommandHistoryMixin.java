package io.github.sh4sh.aomcvisited.mixin;

import net.minecraft.client.CommandHistory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the webmap token out of command_history.txt, which Minecraft saves in the game folder. */
@Mixin(CommandHistory.class)
public class CommandHistoryMixin {
	@Inject(method = "addCommand", at = @At("HEAD"), cancellable = true)
	private void aomcVisited$skipToken(String command, CallbackInfo ci) {
		String typed = command.startsWith("/") ? command.substring(1) : command;
		if (typed.startsWith("visited token ") || typed.startsWith("webmapper token ")) {
			ci.cancel();
		}
	}
}
