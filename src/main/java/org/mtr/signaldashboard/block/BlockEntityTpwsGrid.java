package org.mtr.signaldashboard.block;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mod.Init;
import org.mtr.signaldashboard.BlockEntityTypes;
import org.mtr.signaldashboard.MTRSignalDashboard;
import org.mtr.signaldashboard.data.TpwsCoilConfig;
import org.mtr.signaldashboard.data.TpwsCoilHolder;
import org.mtr.signaldashboard.data.TpwsCoilRegistry;

/**
 * TPWS 线圈的方块实体 —— 只干三件事：<b>存配置</b>、<b>登记到全局索引</b>、<b>给 GUI 提供读写口</b>。
 *
 * <h2>为什么配置存在方块实体里，而不是存在 {@link TpwsCoilRegistry} 里</h2>
 * 索引只是「模拟线程能读的一份投影」，真正的权威数据必须跟着存档走。
 * 方块实体的 NBT 天然随区块保存，挖掉方块就跟着没了，不用额外维护一份持久化文件。
 * 索引在方块实体<b>每次被读取 NBT（= 区块加载）</b>和<b>每 tick 首次访问</b>时重新登记，
 * 所以两者不会长期不一致。
 *
 * <h2>为什么用 {@link #blockEntityTick()} 登记</h2>
 * MTR 的映射层没有暴露方块实体的「加载完成」回调。而 {@link #readCompoundTag} 只在新放置
 * （带 NBT 同步）和区块反序列化时才会被调用 —— 前者在纯放置场景下未必有 NBT。
 * 好在 {@code BlockWithEntity} 接口默认会给出一个每 tick 调用 {@code blockEntityTick()} 的
 * {@code BlockEntityTicker}，用它做「首次登记」最稳：那一刻世界对象一定已经绑好了。
 *
 * @author Mewzica
 */
public class BlockEntityTpwsGrid extends BlockEntityExtension implements TpwsCoilHolder {

	/** 本线圈的配置。索引里存的就是<b>同一个对象</b>，所以 GUI 改完立刻对模拟线程可见。 */
	private final TpwsCoilConfig config = new TpwsCoilConfig();

	/** 是否已经往全局索引登记过（区块卸载重载后会重新走一遍，重复登记是幂等的）。 */
	private boolean indexed = false;

	public BlockEntityTpwsGrid(BlockPos blockPos, BlockState blockState) {
		super(BlockEntityTypes.TPWS_GRID.get(), blockPos, blockState);
	}

	/** 供 GUI / 数据包读写。 */
	@Override
	public TpwsCoilConfig getConfig() {
		return config;
	}

	/**
	 * 覆盖整份配置（服务端收到玩家提交时用）。
	 *
	 * <p>这里<b>不</b>换对象引用，而是逐字段拷贝：索引里持有的是 {@link #config} 这个实例，
	 * 换引用会让索引指向旧对象，刹车逻辑就再也看不到新配置了。
	 */
	@Override
	public void applyConfig(TpwsCoilConfig source) {
		config.setEnabled(source.isEnabled());
		config.setMode(source.getMode());
		config.setSpeedMode(source.getSpeedMode());
		config.setSpeedLimitKmh(source.getSpeedLimitKmh());
		config.setMarginKmh(source.getMarginKmh());
		config.setDirection(source.getDirection());
		if (source.hasSignal()) {
			config.setSignal(source.getSignalPos(), source.getSignalColor());
		} else {
			config.clearSignal();
		}
		markConfigChanged();
	}

	/**
	 * 配置被就地改动后调用：落盘 + 刷新索引。
	 *
	 * <p>「就地改动」指的是直接拿 {@link #getConfig()} 改字段（比如 TPWS 连接器
	 * 绑信号机），没有经过 {@link #applyConfig} 的整份覆盖路径。
	 */
	@Override
	public void markConfigChanged() {
		markDirty2();
		registerToIndex();
	}

	@Override
	public void blockEntityTick() {
		if (!indexed) {
			registerToIndex();
		}
	}

	@Override
	public void readCompoundTag(CompoundTag compoundTag) {
		config.readCompoundTag(compoundTag);
		registerToIndex();
	}

	@Override
	public void writeCompoundTag(CompoundTag compoundTag) {
		config.writeCompoundTag(compoundTag);
	}

	/** 方块被移除时由 {@link BlockTpwsGrid#onStateReplaced2} 调用。 */
	public void unregisterFromIndex() {
		final World world = getWorld2();
		if (world != null) {
			TpwsCoilRegistry.remove(Init.getWorldId(world), getPos2().asLong());
		}
		indexed = false;
	}

	/**
	 * 把本线圈登记进全局索引。
	 *
	 * <p>客户端也要登记 —— 声音是在客户端播的（每个客户端自己预测列车位置，
	 * 本地播才能贴着车厢响），所以客户端也得有这张表。
	 */
	private void registerToIndex() {
		final World world = getWorld2();
		if (world == null) {
			return;
		}
		TpwsCoilRegistry.put(Init.getWorldId(world), getPos2().asLong(), config);
		if (!indexed) {
			indexed = true;
			MTRSignalDashboard.LOGGER.debug("TPWS coil indexed at {} ({})", getPos2(), Init.getWorldId(world));
		}
	}
}
