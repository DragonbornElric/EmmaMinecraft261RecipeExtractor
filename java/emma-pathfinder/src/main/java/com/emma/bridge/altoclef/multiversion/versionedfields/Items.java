package adris.altoclef.multiversion.versionedfields;


import net.minecraft.item.Item;

/**
 * A helper class implementing items that are not yet supported in certain versions
 * Using these in non-supported versions might lead to strange bugs and/or crashes...
 * Please see {@link VersionedFieldHelper#isSupported(Object)}
 */
public class Items extends net.minecraft.item.Items {


    public static final Item UNSUPPORTED = VersionedFieldHelper.createUnsafeUnsupportedItem();



}
