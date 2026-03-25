package com.emma.endinv.item;

import com.emma.endinv.client.ClientModInfo;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

public class TestEndInv extends Item {

    public TestEndInv(Properties properties){
        super(properties);
    }

    @Override
    public @NotNull InteractionResult use(Level level, @NotNull Player player, @NotNull InteractionHand hand) {
        if(level.isClientSide()) {
            ClientModInfo.sendOpenMenu();
        }
        return InteractionResult.SUCCESS;
    }
}
