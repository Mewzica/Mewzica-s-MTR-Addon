package org.mtr.signaldashboard;

import org.mtr.mapping.holder.Item;
import org.mtr.mapping.registry.ItemRegistryObject;
import org.mtr.signaldashboard.item.ItemSignalDashboard;

/**
 * 物品注册。
 *
 * <p>本模组只注册一个物品：信号调度板。它与原版 MTR 的铁路仪表板
 * 是并列关系——铁路仪表板管理"线路/车站/车厂"这类运营数据，
 * 而信号调度板专注"轨道节点之间的信号连接"以及由此构成的闭塞区间。
 *
 * <p>注意这里使用的是<b>本模组自己的</b> {@link MTRSignalDashboard#REGISTRY}，
 * 而不是 {@code org.mtr.mod.Init.REGISTRY}——原因见主类注释里的踩坑记录。
 *
 * <p>物品挂到 MTR 已有的创造模式页签 {@code org.mtr.mod.CreativeModeTabs.CORE} 下，
 * 这样玩家能在熟悉的页签里找到它，也符合 MTR 附属模组的惯例。
 *
 * @author Mewzica
 */
public final class Items {

	/** 信号调度板物品。 */
	public static final ItemRegistryObject SIGNAL_DASHBOARD = MTRSignalDashboard.REGISTRY.registerItem(
			MTRSignalDashboard.identifier("signal_dashboard"),
			itemSettings -> new Item(new ItemSignalDashboard(itemSettings.maxCount(1))),
			org.mtr.mod.CreativeModeTabs.CORE
	);

	private Items() {
	}

	public static void init() {
		// 触发类加载，完成静态字段中的注册（实际落库由 REGISTRY.init() 完成）
		MTRSignalDashboard.LOGGER.info("Registered item: signal_dashboard");
	}
}
