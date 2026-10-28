package org.mtr.signaldashboard.block;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mod.Init;
import org.mtr.signaldashboard.BlockEntityTypes;
import org.mtr.signaldashboard.data.TpwsCoilConfig;
import org.mtr.signaldashboard.data.TpwsCoilHolder;
import org.mtr.signaldashboard.data.TpwsCoilRegistry;

/**
 * 挂在 MTR <b>轨道节点</b>（{@code mtr:rail}）上的方块实体。
 *
 * <h2>为什么要给别人的方块挂方块实体</h2>
 * {@code mtr:rail} 本身<b>没有</b>方块实体 —— MTR 只把它当成一个「线路拓扑点」，
 * 所有数据都放在自己的线路数据里。但 TPWS 线圈需要<b>按位置</b>保存一份配置
 * （阈值 / 信号联锁 / 触发方向），没有方块实体就没地方存。
 *
 * <p>做法是用 mixin 让 {@code BlockNode} 实现 {@code BlockWithEntity}
 * （见 {@code BlockNodeMixin}），再在这里注册一个绑定到 {@code mtr:rail} 的
 * 方块实体类型。MTR 自己的逻辑完全不受影响：它从不调用 {@code getBlockEntity}，
 * 我们也不去碰它的任何字段。
 *
 * <h2>和 {@code tpws_grid} 的区别</h2>
 * 只有两点：
 * <ol>
 *     <li>默认<b>不启用</b> —— 轨道节点是 MTR 的方块，随手铺轨不应该自动带上 TPWS，
 *         得玩家拿刷子显式打开；</li>
 *     <li>模型不是方块状态决定的，而是由 {@code RailNodeTpwsRenderer}
 *         在启用时叠加绘制线圈模型（{@code mtr:rail} 的 blockstate 归 MTR 管，
 *         我们没法给它加变体）。</li>
 * </ol>
 *
 * <h2>为什么也要注册进 {@link TpwsCoilRegistry}</h2>
 * 刹车判定发生在 MTR 的模拟线程里，那个线程只有坐标、拿不到世界对象。
 * 所以不管配置挂在哪种方块上，最终都要摊平进同一张全局表 ——
 * 这样 {@code TpwsProtection} 完全不需要知道「这是线圈还是轨道节点」。
 *
 * @author Mewzica
 */
public class BlockEntityRailNode extends BlockEntityExtension implements TpwsCoilHolder {

	/** 本节点的配置。索引里存的是同一个实例。 */
	private final TpwsCoilConfig config = new TpwsCoilConfig();

	/** 是否已经往全局索引登记过。 */
	private boolean indexed = false;

	public BlockEntityRailNode(BlockPos blockPos, BlockState blockState) {
		super(BlockEntityTypes.RAIL_NODE.get(), blockPos, blockState);
		// 轨道节点默认不带 TPWS 行为，等玩家用 Shift + 刷子打开
		config.setEnabled(false);
	}

	/** 客户端渲染器据此决定要不要叠加线圈模型。 */
	public boolean isTpwsEnabled() {
		return config.isEnabled();
	}

	@Override
	public TpwsCoilConfig getConfig() {
		return config;
	}

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
		// 旧存档里没有 Enabled 字段时，readCompoundTag 会保留当前值。
		// 但当前值是构造时设的 false —— 那就永远启不起来了，所以这里先按「有配置就启用」
		// 兜底：只要 tag 不是空的，就认为这是一枚曾经配置过的节点。
		if (!compoundTag.isEmpty() && !compoundTag.contains("Enabled")) {
			config.setEnabled(true);
		}
		config.readCompoundTag(compoundTag);
		registerToIndex();
	}

	@Override
	public void writeCompoundTag(CompoundTag compoundTag) {
		config.writeCompoundTag(compoundTag);
	}

	/**
	 * 方块实体被移除（方块被替换 / 挖掉）时把自己从索引里摘掉。
	 *
	 * <p>只在<b>方块真的不在了</b>的时候摘：区块卸载时 MTR 的轨道节点还在，
	 * 那一段的防护必须留着（列车在未加载的区块里照样跑）。
	 *
	 * <p>两端都要做：客户端那张表是给信号机显示和声音用的，
	 * 留着僵尸记录会让一个已经拆掉的信号联锁永远亮红。
	 */
	@Override
	public void markRemoved2() {
		final World world = getWorld2();
		if (world != null) {
			final BlockState currentState = world.getBlockState(getPos2());
			final org.mtr.mapping.holder.Block railNode = org.mtr.mod.Blocks.RAIL_NODE.get();
			if (railNode == null || currentState.getBlock().data != railNode.data) {
				TpwsCoilRegistry.remove(Init.getWorldId(world), getPos2().asLong());
			}
		}
		super.markRemoved2();
	}

	/**
	 * 把本节点登记进全局索引。
	 *
	 * <p>{@link TpwsCoilRegistry#put} 自己会处理「停用 → 摘除」，
	 * 所以这里无脑调用就行，配置一关掉这条记录就消失了。
	 */
	private void registerToIndex() {
		final World world = getWorld2();
		if (world == null) {
			return;
		}
		TpwsCoilRegistry.put(Init.getWorldId(world), getPos2().asLong(), config);
		// indexed 表示「已经跟索引对过一次账」，和启不启用无关。
		// 如果用 isEnabled() 当条件，停用的节点会每 tick 重来一次 put→remove，
		// 而每次 remove 都会把信号反查缓存打掉，白白让客户端每帧重建一次。
		indexed = true;
	}
}
