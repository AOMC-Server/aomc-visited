package io.github.sh4sh.aomcvisited.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.sh4sh.aomcvisited.XaeroExport;
import net.minecraft.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.MapProcessor;

import java.io.File;

/** See {@code XaeroExport.TASK_END_FALLBACK_MS}. We open our own folder at the end. */
@Mixin(targets = "xaero.map.file.MapSaveLoad$1", remap = false)
public abstract class XaeroExportTaskMixin {
	@Inject(method = "run", at = @At("RETURN"))
	private void aomcVisited$taskEnded(MapProcessor processor, CallbackInfo ci) {
		XaeroExport.taskEnded();
	}

	@WrapOperation(method = "lambda$run$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/Util$OS;openFile(Ljava/io/File;)V"))
	private void aomcVisited$dontOpenFolder(Util.OS os, File file, Operation<Void> original) {
		if (!XaeroExport.active()) {
			original.call(os, file);
		}
	}
}
