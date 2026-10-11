import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Vector;

/**
 * 实测 RailMath.getPosition 的坐标约定：
 * 返回的是「绝对世界坐标」还是「相对 position1 的坐标」？
 */
public class RailMathProbe {

	public static void main(String[] args) {
		// 一条东西向直轨：从 (100,64,200) 到 (110,64,200)
		final Position p1 = new Position(100, 64, 200);
		final Position p2 = new Position(110, 64, 200);
		probe("直轨 E->W", p1, Angle.E, p2, Angle.W, Rail.Shape.QUADRATIC, 0);

		// 一条带弯曲的轨道（QUADRATIC 会按角度自动算半径）
		probe("弯轨 E->S", p1, Angle.E, new Position(110, 64, 210), Angle.S, Rail.Shape.QUADRATIC, 0);

		// 换一个远一点的起点，看结果是否整体平移
		probe("平移后的直轨", new Position(0, 64, 0), Angle.E, new Position(10, 64, 0), Angle.W, Rail.Shape.QUADRATIC, 0);
	}

	private static void probe(String name, Position p1, Angle a1, Position p2, Angle a2, Rail.Shape shape, double radius) {
		System.out.println("=== " + name + " ===");
		System.out.println("  p1=" + p1.getX() + "," + p1.getY() + "," + p1.getZ()
				+ "  p2=" + p2.getX() + "," + p2.getY() + "," + p2.getZ());
		try {
			final RailMath rm = new RailMath(p1, a1, p2, a2, shape, radius);
			final double len = rm.getLength();
			System.out.println("  length=" + len);
			System.out.println("  shape=" + rm.getShape());
			final double[] samples = {0, len * 0.25, len * 0.5, len * 0.75, len};
			for (final double s : samples) {
				final Vector v = rm.getPosition(s, false);
				System.out.printf("  s=%-8.3f -> (%.3f, %.3f, %.3f)%n", s, v.x, v.y, v.z);
			}
			final Vector vr = rm.getPosition(0, true);
			System.out.printf("  reverse s=0 -> (%.3f, %.3f, %.3f)%n", vr.x, vr.y, vr.z);
			final Vector vr2 = rm.getPosition(len, true);
			System.out.printf("  reverse s=len -> (%.3f, %.3f, %.3f)%n", vr2.x, vr2.y, vr2.z);
		} catch (Throwable t) {
			System.out.println("  ERROR: " + t);
		}
		System.out.println();
	}
}
