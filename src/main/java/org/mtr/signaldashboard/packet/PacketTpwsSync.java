package org.mtr.signaldashboard.packet;

import org.mtr.mapping.holder.BlockEntity;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.signaldashboard.MTRSignalDashboard;
import org.mtr.signaldashboard.data.TpwsCoilConfig;
import org.mtr.signaldashboard.data.TpwsCoilHolder;

/**
 * 把一份 TPWS 配置同步给附近的客户端。
 *
 * <p>方向：服务端 → 客户端。不打开任何界面，只是「把这份配置盖到本地那份投影上」。
 *
 * <h2>为什么非要自己发这个包</h2>
 * 原版方块实体有两种同步机制：{@code toUpdatePacket}（改完立刻推）
 * 和 {@code toInitialChunkDataNbt}（跟着区块发）。MTR 的
 * {@code BlockEntityExtension} 把 {@code toUpdatePacket} 实现成了
 * {@code final} 且<b>恒定返回 null</b>，也就是说这个方块实体<b>永远不会</b>
 * 主动同步给客户端 —— 客户端手里那份配置只在区块加载时从 NBT 里读一次。
 *
 * <p>结果就是：玩家在服务端把 TPWS 打开，客户端那边的方块实体还是「关闭」，
 * 轨道节点上的线圈模型<b>根本不会出现</b>，要等重进区块才看得到。
 * 单人都这样（集成服务端和客户端是两个世界对象），多人更不用说。
 *
 * <p>所以改动落盘之后主动推一份给周围的玩家。客户端收到后直接盖到本地
 * 方块实体上，顺带刷新 {@code TpwsCoilRegistry} —— 信号机接管、声音预测
 * 全靠客户端那张表，一起就都对了。
 *
 * @author Mewzica
 */
public class PacketTpwsSync extends PacketHandler {

	/** 同步半径（方块）。比渲染距离略大，保证「看得到就同步得到」。 */
	private static final double SYNC_RANGE = 96.0D;

	private final BlockPos blockPos;
	private final TpwsCoilConfig config;

	/** 服务端发送用。 */
	public PacketTpwsSync(BlockPos blockPos, TpwsCoilConfig config) {
		this.blockPos = blockPos;
		// 必须拷贝：客户端之后会拿着这份数据去改本地投影，不能和服务端共用同一个实例
		this.config = config.copy();
	}

	/** 客户端接收用。 */
	public PacketTpwsSync(PacketBufferReceiver packetBufferReceiver) {
		this.blockPos = BlockPos.fromLong(packetBufferReceiver.readLong());
		this.config = TpwsCoilConfig.read(packetBufferReceiver);
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeLong(blockPos.asLong());
		config.write(packetBufferSender);
	}

	@Override
	public void runClient() {
		final MinecraftClient client = MinecraftClient.getInstance();
		client.execute(() -> {
			final ClientWorld world = client.getWorldMapped();
			if (world == null) {
				return;
			}
			final BlockEntity blockEntity = world.getBlockEntity(blockPos);
			if (blockEntity != null && blockEntity.data instanceof TpwsCoilHolder coil) {
				coil.applyConfig(config);
			}
		});
	}

	/**
	 * 服务端调用：把配置推给坐标附近的全部玩家。
	 *
	 * <p>用 {@code ServerWorld.getPlayers()} 而不是「所有在线玩家」：
	 * 一条线路可能有上百枚线圈，每次改动都全服广播纯属浪费。
	 */
	public static void broadcast(World world, BlockPos blockPos, TpwsCoilConfig config) {
		if (!(world.data instanceof net.minecraft.server.world.ServerWorld serverWorld)) {
			return;
		}

		for (final net.minecraft.server.network.ServerPlayerEntity player : serverWorld.getPlayers()) {
			if (player.getBlockPos().isWithinDistance(blockPos.data, SYNC_RANGE)) {
				MTRSignalDashboard.REGISTRY.sendPacketToClient(
						new ServerPlayerEntity(player),
						new PacketTpwsSync(blockPos, config)
				);
			}
		}
	}
}
