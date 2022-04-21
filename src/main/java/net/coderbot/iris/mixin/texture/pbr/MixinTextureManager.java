package net.coderbot.iris.mixin.texture.pbr;

import net.coderbot.iris.texture.pbr.PBRTextureManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executor;

@Mixin(TextureManager.class)
public class MixinTextureManager {
	@Inject(method = "lambda$reload$4(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/concurrent/Executor;Ljava/lang/Void;)V", at = @At("TAIL"))
	private void onTailReloadLambda(ResourceManager resourceManager, Executor applyExecutor, Void void1, CallbackInfo ci) {
		PBRTextureManager.INSTANCE.clear();
	}

	@Inject(method = "close()V", at = @At("TAIL"), remap = false)
	private void onTailClose(CallbackInfo ci) {
		PBRTextureManager.INSTANCE.close();
	}
}
