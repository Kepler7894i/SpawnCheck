package dev.spawncheck.net;

import dev.spawncheck.SpawnCheckMod;
import dev.spawncheck.analysis.Cell;
import dev.spawncheck.analysis.CapInfo;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

public final class Payloads {
	private Payloads() {
	}

	private static final StreamCodec<RegistryFriendlyByteBuf, Cell> CELL_CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, Cell::pos,
		ByteBufCodecs.BYTE, Cell::status,
		Cell::new
	);

	private static final StreamCodec<RegistryFriendlyByteBuf, CapInfo> CAP_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, CapInfo::category,
		ByteBufCodecs.VAR_INT, CapInfo::global,
		ByteBufCodecs.VAR_INT, CapInfo::globalMax,
		ByteBufCodecs.VAR_INT, CapInfo::local,
		ByteBufCodecs.VAR_INT, CapInfo::localMax,
		CapInfo::new
	);

	/** Client -> server: "scan around me for this". */
	public record ScanRequest(byte mode, String mob, int radius, int vRadius, boolean hasCenter, BlockPos center) implements CustomPacketPayload {
		public static final Type<ScanRequest> TYPE = new Type<>(SpawnCheckMod.id("scan_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ScanRequest> CODEC = StreamCodec.composite(
			ByteBufCodecs.BYTE, ScanRequest::mode,
			ByteBufCodecs.STRING_UTF8, ScanRequest::mob,
			ByteBufCodecs.VAR_INT, ScanRequest::radius,
			ByteBufCodecs.VAR_INT, ScanRequest::vRadius,
			ByteBufCodecs.BOOL, ScanRequest::hasCenter,
			BlockPos.STREAM_CODEC, ScanRequest::center,
			ScanRequest::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: "tell me in chat why this position does/doesn't work". */
	public record ExplainRequest(BlockPos pos, byte mode, String mob) implements CustomPacketPayload {
		public static final Type<ExplainRequest> TYPE = new Type<>(SpawnCheckMod.id("explain_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ExplainRequest> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ExplainRequest::pos,
			ByteBufCodecs.BYTE, ExplainRequest::mode,
			ByteBufCodecs.STRING_UTF8, ExplainRequest::mob,
			ExplainRequest::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: place or remove stand-in players. {@code name} is only used by {@link #REMOVE_ONE}. */
	public record FakeRequest(byte action, boolean hasPos, BlockPos pos, String name) implements CustomPacketPayload {
		public static final byte SPAWN = 0;
		public static final byte REMOVE_ALL = 1;
		public static final byte REMOVE_ONE = 2;
		public static final Type<FakeRequest> TYPE = new Type<>(SpawnCheckMod.id("fake_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, FakeRequest> CODEC = StreamCodec.composite(
			ByteBufCodecs.BYTE, FakeRequest::action,
			ByteBufCodecs.BOOL, FakeRequest::hasPos,
			BlockPos.STREAM_CODEC, FakeRequest::pos,
			ByteBufCodecs.STRING_UTF8, FakeRequest::name,
			FakeRequest::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> client: where the stand-in players in the client's dimension are, so despawn spheres can be drawn round them. */
	public record FakePositions(List<Vec3> positions) implements CustomPacketPayload {
		public static final Type<FakePositions> TYPE = new Type<>(SpawnCheckMod.id("fake_positions"));
		public static final StreamCodec<RegistryFriendlyByteBuf, FakePositions> CODEC = StreamCodec.composite(
			Vec3.STREAM_CODEC.apply(ByteBufCodecs.list()), FakePositions::positions,
			FakePositions::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> client: highlighted cells plus the level-wide status shown on the HUD. */
	public record ScanResult(boolean denied, String error, List<String> issues, List<CapInfo> caps, List<Cell> cells) implements CustomPacketPayload {
		public static final Type<ScanResult> TYPE = new Type<>(SpawnCheckMod.id("scan_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ScanResult> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, ScanResult::denied,
			ByteBufCodecs.STRING_UTF8, ScanResult::error,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ScanResult::issues,
			CAP_CODEC.apply(ByteBufCodecs.list()), ScanResult::caps,
			CELL_CODEC.apply(ByteBufCodecs.list(SpawnCheckMod.MAX_CELLS)), ScanResult::cells,
			ScanResult::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
