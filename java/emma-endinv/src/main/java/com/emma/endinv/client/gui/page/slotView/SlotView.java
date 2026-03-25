package com.emma.endinv.client.gui.page.slotView;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;

public interface SlotView extends Renderable {

    void renderSlotHighlightBack(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick);

    void renderSlotHighlightFront(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick);
}
