package org.mtr.signaldashboard.client;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.mtr.mapping.holder.Block;
import org.mtr.signaldashboard.Blocks;
import org.mtr.signaldashboard.block.BlockEntityRailNode;

/**
 * 在启用了 TPWS 的轨道节点上叠加绘制线圈模型。
 *
 * <h2>为什么不用 blockstate 换模型</h2>
 * 「换模型」最直觉的做法是给 {@code mtr:rail} 加一个 {@code tpws} 方块属性，
 * 再在自己的 blockstate json 里写两套变体。但这条路走不通：
 * <ul>
 *     <li>{@code mtr:rail} 的 blockstate 归 MTR 的 {@code assets/mtr} 管，
 *         要覆盖它就得让本模组的资源包排在 MTR 之上 ——
 *         而 Fabric 的模组资源包顺序是按 mod 加载顺序来的，<b>没有任何保证</b>；</li>
 *     <li>方块属性一旦加进 {@code mtr:rail}，MTR 那份 blockstate 就没有对应变体了，
 *         结果是「模型丢失」而不是「回退到原模型」。</li>
 * </ul>
 * 换个思路：{@code mtr:rail} 的 blockstate 在 {@code is_connected=true} 时
 * <b>本来就不套任何模型</b>（见 {@code assets/mtr/blockstates/rail.json}，
 * 八个变体全都带 {@code is_connected=false} 条件）—— 也就是说铺好的轨道节点
 * 在游戏里是<b>完全不可见</b>的。所以我们只需要「加」一个模型上去，
 * 根本不需要「换」。
 *
 * <p>方块实体渲染器正好干这个：轨道节点有方块实体（见 {@code BlockNodeMixin}），
 * 在它上面叠一层 {@code mewsmtr:tpws_grid} 的模型即可。关掉 TPWS 就不画，
 * 视觉上和没装一模一样。
 *
 * <h2>为什么往下挪 1.25 格</h2>
 * {@code tpws_grid} 模型的几何范围是 y 1.25 ~ 2.25（那个方块的方块实体自己也是
 * 这么算碰撞箱的）。轨道节点就在地面这一层，把模型整体下移 1.25 格之后，
 * 感应板正好落在两根钢轨之间的轨枕高度上 —— 和现实里 TPWS 的安装位置一致。
 *
 * @author Mewzica
 */
public class RailNodeTpwsRenderer implements BlockEntityRenderer<BlockEntityRailNode> {

	/** {@code tpws_grid} 模型整体位于 y 1.25 ~ 2.25，下移这么多才能贴地。 */
	private static final double MODEL_Y_OFFSET = -1.25D;

	/** 让线圈模型在 32 格内可见（默认值是 64，这里略收一点，轨道节点密度高）。 */
	private static final int RENDER_DISTANCE = 48;

	@Override
	public void render(BlockEntityRailNode entity, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay) {
		if (!entity.isTpwsEnabled()) {
			return;
		}

		final Block tpwsGrid = Blocks.TPWS_GRID.get();
		if (tpwsGrid == null) {
			return;
		}

		// 用方块的默认状态取模型：tpws_grid 的四个 facing 变体只是绕 y 转 90°，
		// 而线圈本身前后对称，随便取一个都不会看错。
		final BlockState state = tpwsGrid.data.getDefaultState();

		matrices.push();
		matrices.translate(0.0D, MODEL_Y_OFFSET, 0.0D);
		// renderBlockAsEntity 内部会按方块自己声明的渲染层（RenderLayers.getEntityBlockLayer）
		// 选 buffer，所以半透明 / 镂空贴图都能正确显示，不用我们手动挑 RenderLayer。
		MinecraftClient.getInstance().getBlockRenderManager()
				.renderBlockAsEntity(state, matrices, vertexConsumers, light, overlay);
		matrices.pop();
	}

	@Override
	public int getRenderDistance() {
		return RENDER_DISTANCE;
	}
}
