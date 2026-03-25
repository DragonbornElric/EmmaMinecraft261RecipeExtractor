package com.emma.endinv.autopick;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**Reserved
 * Upgraded autopick config.
 * @param enabled general toggler that controls the whole autopick utility
 * @param mobLoot Grab entity drops
 * @param blockLoot Grab block drops
 * @param itemPickInto insert picked item (entity) to EndInv
 */
public record AutopickConfig(boolean enabled,
                             SpecifiedConfig mobLoot, SpecifiedConfig blockLoot, boolean itemPickInto
                             ) {
    public static final Codec<AutopickConfig> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.BOOL.fieldOf("enable").forGetter(AutopickConfig::enabled),
                    SpecifiedConfig.CODEC.fieldOf("mob_loot").forGetter(AutopickConfig::mobLoot),
                    SpecifiedConfig.CODEC.fieldOf("block_drop").forGetter(AutopickConfig::blockLoot),
                    Codec.BOOL.fieldOf("item_pick_into").forGetter(AutopickConfig::itemPickInto)
            ).apply(instance, AutopickConfig::new)
    );

    public record SpecifiedConfig(boolean gatherItems, boolean gatherExp) {

        public static final Codec<SpecifiedConfig> CODEC = RecordCodecBuilder.create(
                instance -> instance.group(
                        Codec.BOOL.fieldOf("gather_items").forGetter(SpecifiedConfig::gatherItems),
                        Codec.BOOL.fieldOf("gather_exp").forGetter(SpecifiedConfig::gatherExp)
                ).apply(instance, SpecifiedConfig::new)
        );
    }
}
