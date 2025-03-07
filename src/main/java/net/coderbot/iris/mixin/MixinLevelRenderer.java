package net.coderbot.iris.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;
import net.coderbot.iris.Iris;
import net.coderbot.iris.fantastic.WrappingMultiBufferSource;
import net.coderbot.iris.gl.program.Program;
import net.coderbot.iris.layer.IsOutlineRenderStateShard;
import net.coderbot.iris.layer.OuterWrappedRenderType;
import net.coderbot.iris.pipeline.HandRenderer;
import net.coderbot.iris.pipeline.WorldRenderingPhase;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.coderbot.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
	private static final String RENDER_LEVEL = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lcom/mojang/math/Matrix4f;)V";
	private static final String CLEAR = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V";
	private static final String RENDER_SKY = "Lnet/minecraft/client/renderer/LevelRenderer;renderSky(Lcom/mojang/blaze3d/vertex/PoseStack;F)V";
	private static final String RENDER_LAYER = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDD)V";
	private static final String RENDER_CLOUDS = "Lnet/minecraft/client/renderer/LevelRenderer;renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;FDDD)V";
	private static final String RENDER_WEATHER = "Lnet/minecraft/client/renderer/LevelRenderer;renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V";
	private static final String RENDER_WORLD_BOUNDS = "Lnet/minecraft/client/renderer/LevelRenderer;renderWorldBounds(Lnet/minecraft/client/Camera;)V";

	@Unique
	private WorldRenderingPipeline pipeline;

	@Shadow
	private RenderBuffers renderBuffers;

	// Begin shader rendering after buffers have been cleared.
	// At this point we've ensured that Minecraft's main framebuffer is cleared.
	// This is important or else very odd issues will happen with shaders that have a final pass that doesn't write to
	// all pixels.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = CLEAR, shift = At.Shift.AFTER))
	private void iris$beginLevelRender(PoseStack poseStack, float tickDelta, long startTime, boolean renderBlockOutline,
									   Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
									   Matrix4f projection, CallbackInfo callback) {
		if (Iris.isSodiumInvalid()) {
			throw new IllegalStateException("An invalid version of Sodium is installed, and the warning screen somehow" +
				" didn't work. This is a bug! Please report it to the Iris developers.");
		}

		CapturedRenderingState.INSTANCE.setGbufferModelView(poseStack.last().pose());
		CapturedRenderingState.INSTANCE.setGbufferProjection(projection);
		CapturedRenderingState.INSTANCE.setTickDelta(tickDelta);
		SystemTimeUniforms.COUNTER.beginFrame();
		SystemTimeUniforms.TIMER.beginFrame(startTime);

		Program.unbind();

		pipeline = Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimension());

		pipeline.beginLevelRendering();
	}

	// Inject a bit early so that we can end our rendering before mods like VoxelMap (which inject at RETURN)
	// render their waypoint beams.
	@Inject(method = RENDER_LEVEL, at = @At(value = "RETURN", shift = At.Shift.BEFORE))
	private void iris$endLevelRender(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		HandRenderer.INSTANCE.renderTranslucent(poseStack, tickDelta, camera, gameRenderer, pipeline);
		Minecraft.getInstance().getProfiler().popPush("iris_final");
		pipeline.finalizeLevelRendering();
		pipeline = null;
		Program.unbind();
	}

	// Setup shadow terrain & render shadows before the main terrain setup. We need to do things in this order to
	// avoid breaking other mods such as Light Overlay: https://github.com/IrisShaders/Iris/issues/1356
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "net/minecraft/client/renderer/LevelRenderer.setupRender (Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZIZ)V"))
	private void iris$renderTerrainShadows(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		pipeline.renderShadows((LevelRendererAccessor) this, camera);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_SKY))
	private void iris$beginSky(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		// Use CUSTOM_SKY until levelFogColor is called as a heuristic to catch FabricSkyboxes.
		pipeline.setPhase(WorldRenderingPhase.CUSTOM_SKY);
	}

	@Redirect(method = "renderLevel", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;renderDistance:I"),
		slice = @Slice(from = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V")))
	private int iris$alwaysRenderSky(Options options) {
		return Math.max(options.renderDistance, 4);
	}

	@Inject(method = "renderSky",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FogRenderer;levelFogColor()V"))
	private void iris$renderSky$beginNormalSky(PoseStack poseStack, float tickDelta, CallbackInfo callback) {
		// None of the vanilla sky is rendered until after this call, so if anything is rendered before, it's
		// CUSTOM_SKY.
		pipeline.setPhase(WorldRenderingPhase.SKY);
	}

	@Inject(method = "renderSky", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;SUN_LOCATION:Lnet/minecraft/resources/ResourceLocation;"))
	private void iris$setSunRenderStage(PoseStack poseStack, float f, CallbackInfo ci) {
		pipeline.setPhase(WorldRenderingPhase.SUN);
	}

	@Inject(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;getSunriseColor(FF)[F"))
	private void iris$setSunsetRenderStage(PoseStack poseStack, float f, CallbackInfo ci) {
		pipeline.setPhase(WorldRenderingPhase.SUNSET);
	}

	@Inject(method = "renderSky", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;MOON_LOCATION:Lnet/minecraft/resources/ResourceLocation;"))
	private void iris$setMoonRenderStage(PoseStack poseStack, float f, CallbackInfo ci) {
		pipeline.setPhase(WorldRenderingPhase.MOON);
	}

	@Inject(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getStarBrightness(F)F"))
	private void iris$setStarRenderStage(PoseStack poseStack, float f, CallbackInfo ci) {
		pipeline.setPhase(WorldRenderingPhase.STARS);
	}

	@Inject(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"))
	private void iris$setVoidRenderStage(PoseStack poseStack, float f, CallbackInfo ci) {
		pipeline.setPhase(WorldRenderingPhase.VOID);
	}

	@Inject(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getTimeOfDay(F)F"),
		slice = @Slice(from = @At(value = "FIELD", target = "com/mojang/math/Vector3f.YP : Lcom/mojang/math/Vector3f;")))
	private void iris$renderSky$tiltSun(PoseStack poseStack, float tickDelta, CallbackInfo callback) {
		poseStack.mulPose(Vector3f.ZP.rotationDegrees(pipeline.getSunPathRotation()));
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_SKY, shift = At.Shift.AFTER))
	private void iris$endSky(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo ci) {
		pipeline.setPhase(WorldRenderingPhase.NONE);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_CLOUDS))
	private void iris$beginClouds(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		pipeline.setPhase(WorldRenderingPhase.CLOUDS);
	}

	@Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
	private void iris$maybeRemoveClouds(PoseStack poseStack, float tickDelta, double cameraX, double cameraY, double cameraZ, CallbackInfo ci) {
		if (!pipeline.shouldRenderClouds()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_CLOUDS, shift = At.Shift.AFTER))
	private void iris$endClouds(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		pipeline.setPhase(WorldRenderingPhase.NONE);
	}


	@Inject(method = RENDER_LAYER, at = @At("HEAD"))
	private void iris$beginTerrainLayer(RenderType renderType, PoseStack poseStack, double cameraX, double cameraY, double cameraZ, CallbackInfo callback) {
		pipeline.setPhase(WorldRenderingPhase.fromTerrainRenderType(renderType));
	}

	@Inject(method = RENDER_LAYER, at = @At("RETURN"))
	private void iris$endTerrainLayer(RenderType renderType, PoseStack poseStack, double cameraX, double cameraY, double cameraZ, CallbackInfo callback) {
		pipeline.setPhase(WorldRenderingPhase.NONE);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_WEATHER))
	private void iris$beginWeather(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		pipeline.setPhase(WorldRenderingPhase.RAIN_SNOW);
	}

	@ModifyArg(method = RENDER_WEATHER, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;depthMask(Z)V", ordinal = 0))
	private boolean iris$writeRainAndSnowToDepthBuffer(boolean depthMaskEnabled) {
		if (pipeline.shouldWriteRainAndSnowToDepthBuffer()) {
			return true;
		}

		return depthMaskEnabled;
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_WEATHER, shift = At.Shift.AFTER))
	private void iris$endWeather(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		pipeline.setPhase(WorldRenderingPhase.NONE);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_WORLD_BOUNDS))
	private void iris$beginWorldBorder(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		pipeline.setPhase(WorldRenderingPhase.WORLD_BORDER);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_WORLD_BOUNDS, shift = At.Shift.AFTER))
	private void iris$endWorldBorder(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		pipeline.setPhase(WorldRenderingPhase.NONE);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/debug/DebugRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD)V"))
	private void iris$setDebugRenderStage(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci) {
		pipeline.setPhase(WorldRenderingPhase.DEBUG);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/debug/DebugRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD)V", shift = At.Shift.AFTER))
	private void iris$resetDebugRenderStage(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci) {
		pipeline.setPhase(WorldRenderingPhase.NONE);
	}

	@Inject(method = "renderLevel",
		at = @At(value = "INVOKE", target = "net/minecraft/client/renderer/MultiBufferSource$BufferSource.getBuffer (Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
		slice = @Slice(
			from = @At(value = "CONSTANT", args = "stringValue=outline"),
			to = @At(value = "INVOKE", target = "net/minecraft/client/renderer/LevelRenderer.renderHitOutline (Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V")
		))
	private void iris$beginBlockOutline(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci) {
		MultiBufferSource.BufferSource bufferSource = renderBuffers.bufferSource();

		if (!(bufferSource instanceof WrappingMultiBufferSource)) {
			return;
		}

		((WrappingMultiBufferSource) bufferSource).pushWrappingFunction(type ->
			new OuterWrappedRenderType("iris:is_outline", type, IsOutlineRenderStateShard.INSTANCE));
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "net/minecraft/client/renderer/LevelRenderer.renderHitOutline (Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"))
	private void iris$endBlockOutline(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci) {
		MultiBufferSource.BufferSource bufferSource = renderBuffers.bufferSource();

		if (!(bufferSource instanceof WrappingMultiBufferSource)) {
			return;
		}

		((WrappingMultiBufferSource) bufferSource).popWrappingFunction();
	}

	@Inject(method = "renderLevel", at = @At(value = "CONSTANT", args = "stringValue=translucent"))
	private void iris$beginTranslucents(PoseStack poseStack, float tickDelta, long limitTime,
										boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
										LightTexture lightTexture, Matrix4f projection,
										CallbackInfo ci) {
		pipeline.beginHand();
		HandRenderer.INSTANCE.renderSolid(poseStack, tickDelta, camera, gameRenderer, pipeline);
		Minecraft.getInstance().getProfiler().popPush("iris_pre_translucent");
		pipeline.beginTranslucents();
	}
}
