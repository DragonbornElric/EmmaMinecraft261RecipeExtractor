package adris.altoclef.multiversion.versionedfields;

import net.minecraft.block.Block;

/**
 * A helper class implementing blocks that are not yet supported in certain versions
 * Using these in non-supported versions might lead to strange bugs and/or crashes...
 * Please see {@link VersionedFieldHelper#isSupported(Object)}
 */
public abstract class Blocks extends net.minecraft.block.Blocks {

    public static final Block UNSUPPORTED = VersionedFieldHelper.createUnsafeUnsupportedBlock();



}
