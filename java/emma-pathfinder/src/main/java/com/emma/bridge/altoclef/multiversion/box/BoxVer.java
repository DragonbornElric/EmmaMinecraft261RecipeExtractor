package adris.altoclef.multiversion.box;

import adris.altoclef.multiversion.Pattern;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class BoxVer {


   @Pattern
    public Box of(Vec3d center, double x, double y, double z) {
       return Box.of(center, x, y, z);
   }


}
