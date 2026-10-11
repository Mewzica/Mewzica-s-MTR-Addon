package org.mtr.signaldashboard;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric 模组主入口。
 *
 * @author Mewzica
 */
public final class MTRSignalDashboardFabric implements ModInitializer {

	@Override
	public void onInitialize() {
		MTRSignalDashboard.init();
	}
}
