package org.mtr.signaldashboard.data;

/**
 * 「身上挂着一份 TPWS 线圈配置」的方块实体。
 *
 * <h2>为什么需要这个接口</h2>
 * TPWS 现在有两种载体：
 * <ul>
 *     <li>{@code mewsmtr:tpws_grid} —— 独立的线圈方块；</li>
 *     <li>{@code mtr:rail} —— MTR 的轨道节点，用 mixin 给它挂了方块实体。</li>
 * </ul>
 * 两者的配置语义完全一样（超速阈值 / 信号联锁 / 触发方向 / 是否启用），
 * 配置界面、提交数据包、信号反查也全都一样。唯一的差别是「谁把配置存起来」。
 *
 * <p>所以把这些共同操作抽成一个接口，界面和数据包只认接口、不认具体方块类 ——
 * 以后再加第三种载体（比如道岔、应答器）时，数据包一行都不用改。
 *
 * @author Mewzica
 */
public interface TpwsCoilHolder {

	/** 当前配置。索引里存的就是<b>同一个对象</b>，改完立刻对模拟线程可见。 */
	TpwsCoilConfig getConfig();

	/**
	 * 覆盖整份配置（服务端收到玩家提交时用）。
	 *
	 * <p>实现方必须<b>逐字段拷贝</b>而不是换对象引用：全局索引持有的是
	 * {@link #getConfig()} 那个实例，换引用会让索引指向旧对象。
	 */
	void applyConfig(TpwsCoilConfig source);

	/** 配置被就地改动后调用：落盘 + 刷新索引。 */
	void markConfigChanged();
}
