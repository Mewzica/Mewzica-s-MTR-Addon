package org.mtr.signaldashboard;

import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric 客户端模组入口。
 *
 * @author Mewzica
 */
public final class MTRSignalDashboardFabricClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		MTRSignalDashboardClient.init();
	}
}
