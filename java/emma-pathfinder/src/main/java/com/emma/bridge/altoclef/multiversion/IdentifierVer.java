package adris.altoclef.multiversion;

import net.minecraft.util.Identifier;

public class IdentifierVer {


    @Pattern
    private static Identifier newCreation(String str) {
        return Identifier.of(str);
    }


}
