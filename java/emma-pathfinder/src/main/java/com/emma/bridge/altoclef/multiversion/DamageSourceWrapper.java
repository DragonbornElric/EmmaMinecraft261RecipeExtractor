package adris.altoclef.multiversion;

import net.minecraft.entity.damage.DamageSource;

public class DamageSourceWrapper {



    public static DamageSourceWrapper of(DamageSource source) {
        if (source == null) return null;

        return new DamageSourceWrapper(source);
    }


    private final DamageSource source;

    private DamageSourceWrapper(DamageSource source) {
        this.source = source;
    }

    public DamageSource getSource() {
        return source;
    }

    public boolean bypassesArmor() {
        return source.isIn(net.minecraft.registry.tag.DamageTypeTags.BYPASSES_ARMOR);
    }

    public boolean bypassesShield() {
        return source.isIn(net.minecraft.registry.tag.DamageTypeTags.BYPASSES_SHIELD);
    }

    public boolean isOutOfWorld() {
        return source.isOf(net.minecraft.entity.damage.DamageTypes.OUT_OF_WORLD);
    }

}
