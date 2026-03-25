package com.emma.endinv.client.gui.page.slotView;

import com.emma.endinv.client.gui.page.ItemEntryDisplay;
import com.emma.endinv.util.ItemKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jetbrains.annotations.Nullable;

public class ItemEntryPageSlotView extends ItemPageSlotView{

    public ItemEntryPageSlotView(EntryPageViewContainer container, @Nullable ItemKey key, int slot, int x, int y) {
        super(container, key, slot, x, y, container.page.framework.columns() * 18, 18);
    }

    /**
     * Renders the graphical user interface (GUI) element.
     *
     * @param guiGraphics the GuiGraphicsExtractor object used for rendering.
     * @param mouseX      the x-coordinate of the mouse cursor.
     * @param mouseY      the y-coordinate of the mouse cursor.
     * @param partialTick the partial tick time.
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
        var view = ((EntryPageViewContainer)container);
        guiGraphics.text(
                Minecraft.getInstance().font,
                view.entryProvider.apply((ItemEntryDisplay) view.page, get()),
                x + 18, y + 5, 0xFFFFFFFF, true
        );

    }
    /*    public void toggleJmpItemName(boolean jmpTooltip1st){
        this.jmpTooltip1st = jmpTooltip1st;
    }
     *     private static final int TOOLTIP_X_SEP = 5;
    @Override
    public void render(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTicks) {
        int rowIndex = 0;
        int columnIndex = 0;
        for(StandardItemPageSlotView pointer : viewContainer.slots()){
            ItemStack stack = pointer.get();
            guiGraphics.renderItem(stack,leftPos,topPos+rowIndex*18+1,columnIndex+rowIndex<<8);
            if(!stack.isEmpty())
                renderItemEntry(stack,leftPos+18,topPos+rowIndex*18+5,guiGraphics);
            if(!isHiddenBySortBox(rowIndex,columnIndex))
                guiGraphics.renderItemDecorations(Minecraft.getInstance().font, stack, leftPos,topPos+rowIndex*18+1, getDisplayAmount(stack));
            rowIndex++;
            if(rowIndex>= framework.rows()) break;
        }
    }

    private void renderItemEntry(ItemStack item, int x, int y, GuiGraphicsExtractor graphics){
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        List<Component> tooltips = AbstractContainerScreen.getTooltipFromItem(mc,item);
        int strX = x;
        boolean jmp = jmpTooltip1st;
        for(var tip : tooltips){
            if(jmp){
                jmp = false;
                continue;
            }
            Component tip1 = Component.literal(tip.getString());
            int strX1 = strX + font.width(tip.getVisualOrderText());
            if(strX1 >= x + framework.columns()*18 -18-3){
                graphics.drawString(font,Component.literal("..."),strX,y,0xFFFFFF00);
                break;
            }
            graphics.drawString(font,tip1,strX,y,0xFFFFFF00);
            strX = strX1 + TOOLTIP_X_SEP;
        }
    }

     */

}
