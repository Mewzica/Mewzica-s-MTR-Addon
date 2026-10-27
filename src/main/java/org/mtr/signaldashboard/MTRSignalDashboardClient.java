package org.mtr.signaldashboard;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.registry.RegistryClient;
import org.mtr.signaldashboard.client.DriveInput;
import org.mtr.signaldashboard.data.TpwsCoilConfig;
import org.mtr.signaldashboard.packet.PacketOpenSignalDashboard;
import org.mtr.signaldashboard.packet.PacketTpwsConfig;
import org.mtr.signaldashboard.packet.PacketUpdateSignalLink;

/**
 * 客户端初始化与出站网络调用。
 *
 * <p>与主类对应，客户端也需要一个<b>绑定到本模组 Registry</b> 的
 * {@link RegistryClient}。它负责：
 * <ol>
 *     <li>注册本模组通道的客户端接收器（{@code setupPackets}）；</li>
 *     <li>把本模组包发送到服务端（{@code sendPacketToServer}）。</li>
 * </ol>
 *
 * <p>注意必须调用 {@code init()} 来 flush 客户端侧的延迟注册，
 * 否则任何客户端渲染器/事件注册都不会生效。
 *
 * @author Mewzica
 */
public final class MTRSignalDashboardClient {

	/** 绑定本模组 Registry 的客户端注册表。 */
	public static final RegistryClient REGISTRY_CLIENT = new RegistryClient(MTRSignalDashboard.REGISTRY);

	private MTRSignalDashboardClient() {
	}

	public static void init() {
		// 注册本模组通道的客户端接收器
		REGISTRY_CLIENT.setupPackets(MTRSignalDashboard.identifier("packet"));

		// 注册驾驶按键与客户端 tick 回调。
		// ⚠️ 必须在 init() 之前：registerKeyBinding 是延迟注册的，
		//    排在 flush 之后就永远不会生效（MTR 自己也是先 KeyBindings.init() 再 flush）。
		DriveInput.init();

		// flush 客户端延迟注册
		REGISTRY_CLIENT.init();

		MTRSignalDashboard.LOGGER.info("Mewzica's MTR Addon client ready");
	}

	/**
	 * 提交一条信号连接编辑到服务端。
	 */
	public static void sendSignalUpdate(BlockPos pos1, BlockPos pos2, int color, int action) {
		REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateSignalLink(pos1, pos2, color, action));
	}

	/**
	 * 请求服务端重发轨道数据。
	 */
	public static void requestRefresh() {
		REGISTRY_CLIENT.sendPacketToServer(new PacketOpenSignalDashboard());
	}

	/**
	 * 提交 TPWS 线圈配置。
	 *
	 * @param action {@link PacketTpwsConfig#ACTION_SAVE} /
	 *               {@link PacketTpwsConfig#ACTION_AUTO_LINK} /
	 *               {@link PacketTpwsConfig#ACTION_UNLINK}
	 */
	public static void sendTpwsConfig(BlockPos blockPos, TpwsCoilConfig config, int action) {
		REGISTRY_CLIENT.sendPacketToServer(new PacketTpwsConfig(blockPos, config, action));
	}
}
