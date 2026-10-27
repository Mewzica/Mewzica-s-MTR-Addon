package org.mtr.signaldashboard;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.registry.Registry;
import org.mtr.signaldashboard.packet.PacketOpenSignalDashboard;
import org.mtr.signaldashboard.packet.PacketOpenTpwsConfig;
import org.mtr.signaldashboard.packet.PacketRequestSignalBlocks;
import org.mtr.signaldashboard.packet.PacketReverser;
import org.mtr.signaldashboard.packet.PacketTpwsConfig;
import org.mtr.signaldashboard.packet.PacketUpdateSignalLink;

/**
 * Mewzica's MTR Addon — 信号调度板附属模组主初始化类。
 *
 * <p>本模组为 Minecraft Transit Railway (MTR) 的 Fabric 附属模组，提供：
 * <ul>
 *     <li>轨道节点之间的信号连接可视化编辑（选择两个节点 → 指定信号颜色）</li>
 *     <li>轨道连接关系的俯视示意图</li>
 *     <li>信号区间（闭塞）占用状态显示</li>
 * </ul>
 *
 * <h2>为什么必须自建 Registry（踩坑记录）</h2>
 * MTR 的映射层 {@code org.mtr.mapping.registry.Registry} 采用<b>延迟注册</b>机制：
 * {@code registerItem(...)} 等方法只是把一个 {@code Runnable} 塞进内部的
 * {@code objectsToRegister} 列表，真正的 {@code Registry.register(...)}
 * 要等到 {@link Registry#init()} 被调用时才统一执行。
 *
 * <p>MTR 本体在自己 {@code Init.init()} 的末尾已经调用过 {@code REGISTRY.init()}，
 * 而 Fabric 的模组初始化顺序保证 MTR 先于本模组加载。因此如果本模组复用
 * {@code Init.REGISTRY}，注册请求会永远留在队列里得不到执行，物品实际上从未
 * 进入 {@code Registries.ITEM}，最终在注册表冻结时报：
 * <pre>
 * IllegalStateException: Some intrusive holders were not registered: [Reference{null=air}]
 * </pre>
 *
 * <p>所以这里<b>自建一个独立的 {@code Registry}</b>，并在注册完成后自己调用
 * {@code init()}。MTR 的 Registry 支持多实例（每个实例持有独立的
 * {@code objectsToRegister} / {@code packets} / {@code eventRegistry}），
 * 数据包通道通过 {@code setupPackets} 使用本模组自己的命名空间，互不干扰。
 *
 * @author Mewzica
 */
public final class MTRSignalDashboard {

	public static final String MOD_ID = "mewsmtr";
	public static final Logger LOGGER = LogManager.getLogger("MTRSignalDashboard");

	/** 本模组专属的注册表（不复用 MTR 的，原因见类注释）。 */
	public static final Registry REGISTRY = new Registry();

	private static boolean initialized = false;

	public static void init() {
		if (initialized) {
			return;
		}
		initialized = true;

		LOGGER.info("Mewzica's MTR Addon initialising (author: Mewzica)");

		// 1. 建立本模组的数据包通道（命名空间独立，不会与 MTR 冲突）
		REGISTRY.setupPackets(identifier("packet"));

		// 2. 注册数据包类型
		REGISTRY.registerPacket(PacketOpenSignalDashboard.class, PacketOpenSignalDashboard::new);
		REGISTRY.registerPacket(PacketUpdateSignalLink.class, PacketUpdateSignalLink::new);
		REGISTRY.registerPacket(PacketRequestSignalBlocks.class, PacketRequestSignalBlocks::new);
		REGISTRY.registerPacket(PacketReverser.class, PacketReverser::new);
		REGISTRY.registerPacket(PacketOpenTpwsConfig.class, PacketOpenTpwsConfig::new);
		REGISTRY.registerPacket(PacketTpwsConfig.class, PacketTpwsConfig::new);

		// 3. 注册物品（这一步只是把注册请求入队）
		Items.init();

		// 4. 注册方块（同样只是入队）
		Blocks.init();

		// 4.5 注册方块实体类型。★ 必须排在 Blocks.init() 之后 ——
		//     registerBlockEntityType 会立即拿 Blocks.TPWS_GRID.get() 去建
		//     BlockEntityType，方块对象得先存在（它确实在 Blocks 的静态字段里就 new 好了）。
		BlockEntityTypes.init();

		// 4.6 注册音效（TPWS 起动音 / 超速 / 闯红灯）
		Sounds.init();

		// 5. ★ 关键：flush 所有延迟注册。漏掉这一步物品就永远注册不上。
		REGISTRY.init();

		LOGGER.info("Mewzica's MTR Addon ready");
	}

	public static Identifier identifier(String path) {
		return new Identifier(MOD_ID, path);
	}
}
