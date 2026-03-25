package com.emma.endinv.client.events;

import com.emma.endinv.client.event.AutoPickTipper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

public final class PickingUpTip {

    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath("endless_inventory", "picking_up_tip");

    private PickingUpTip() {
    }

    public static void register() {
        HudElementRegistry.addLast(HUD_ID, new HudElement() {
            @Override
            public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
                AutoPickTipper.onRenderGui(graphics);
            }
        });
    }
}
