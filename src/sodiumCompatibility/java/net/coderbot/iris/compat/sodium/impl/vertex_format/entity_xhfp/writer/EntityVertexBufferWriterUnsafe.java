package net.coderbot.iris.compat.sodium.impl.vertex_format.entity_xhfp.writer;

import me.jellysquid.mods.sodium.client.model.vertex.VanillaVertexTypes;
import me.jellysquid.mods.sodium.client.model.vertex.buffer.VertexBufferView;
import me.jellysquid.mods.sodium.client.model.vertex.buffer.VertexBufferWriterUnsafe;
import me.jellysquid.mods.sodium.client.model.vertex.formats.quad.QuadVertexSink;
import net.coderbot.iris.compat.sodium.impl.vertex_format.entity_xhfp.QuadViewEntity;
import net.coderbot.iris.vendored.joml.Vector3f;
import net.coderbot.iris.vertices.IrisVertexFormats;
import net.coderbot.iris.vertices.NormalHelper;
import org.lwjgl.system.MemoryUtil;

public class EntityVertexBufferWriterUnsafe extends VertexBufferWriterUnsafe implements QuadVertexSink {
	private final QuadViewEntity.QuadViewEntityUnsafe quad = new QuadViewEntity.QuadViewEntityUnsafe();
	private static final int STRIDE = IrisVertexFormats.ENTITY.getVertexSize();
	float midU = 0;
	float midV = 0;
	private int vertexCount;
	private Vector3f normal = new Vector3f();

	public EntityVertexBufferWriterUnsafe(VertexBufferView backingBuffer) {
		super(backingBuffer, VanillaVertexTypes.QUADS);
	}

	@Override
	public void writeQuad(float x, float y, float z, int color, float u, float v, int light, int overlay, int normal) {
		long i = this.writePointer;

		vertexCount++;
		midU += u;
		midV += v;

		MemoryUtil.memPutFloat(i, x);
		MemoryUtil.memPutFloat(i + 4, y);
		MemoryUtil.memPutFloat(i + 8, z);
		MemoryUtil.memPutInt(i + 12, color);
		MemoryUtil.memPutFloat(i + 16, u);
		MemoryUtil.memPutFloat(i + 20, v);
		MemoryUtil.memPutInt(i + 24, overlay);
		MemoryUtil.memPutInt(i + 28, light);
		MemoryUtil.memPutShort(i + 36, (short) -1);
		MemoryUtil.memPutShort(i + 38, (short) -1);

		this.advance();

		if (vertexCount == 4) {
			this.endQuad(vertexCount);
		}
	}

	public void endQuad(int length) {
		long i = this.writePointer;

		quad.setup(writePointer, STRIDE);

		NormalHelper.computeFaceNormal(normal, quad);
		int packedNormal = NormalHelper.packNormal(normal, 0.0f);

		for (long vertex = 0; vertex < length; vertex++) {
			MemoryUtil.memPutInt(i + 32 - STRIDE * vertex, packedNormal);
		}

		int tangent = quad.computeTangent(normal.x, normal.y, normal.z);

		for (long vertex = 0; vertex < length; vertex++) {
			MemoryUtil.memPutInt(i - 4 - STRIDE * vertex, tangent);
		}

		midU *= 0.25;
		midV *= 0.25;

		for (long vertex = 0; vertex < length; vertex++) {
			MemoryUtil.memPutFloat(i - 12 - STRIDE * vertex, midU);
			MemoryUtil.memPutFloat(i - 8 - STRIDE * vertex, midV);
		}

		midU = 0;
		midV = 0;
		vertexCount = 0;
	}
}
