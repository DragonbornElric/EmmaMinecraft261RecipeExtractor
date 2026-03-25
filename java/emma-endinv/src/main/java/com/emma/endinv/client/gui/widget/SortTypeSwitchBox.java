package com.emma.endinv.client.gui.widget;

import com.emma.endinv.client.gui.ScreenFramework;
import com.emma.endinv.client.gui.bg.IRectangleParam;
import com.emma.endinv.util.SortType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

public class SortTypeSwitchBox extends AbstractWidget {

    public ScreenFramework framework;
    private final int singleBoxHeight;
    private boolean isOpen;


    public SortTypeSwitchBox(ScreenFramework framework, int x, int y, int width, int height){
        super(x,y,width,height, Component.empty());
        this.framework = framework;
        this.singleBoxHeight = height;
    }

    public SortTypeSwitchBox(ScreenFramework framework, IRectangleParam sortTypeSwitchBoxParam){
        this(framework,
                sortTypeSwitchBoxParam.x(),
                sortTypeSwitchBoxParam.y(),
                sortTypeSwitchBoxParam.width(),
                sortTypeSwitchBoxParam.height()
        );
    }

    public boolean isOpen() {
        return isOpen;
    }

    public void setOpen(boolean open) {
        isOpen = open;
        this.height = open ? singleBoxHeight*(1+ SortType.values().length) : singleBoxHeight;
    }

    public void onClick(MouseButtonEvent event, boolean pre){
        if(!isOpen){
            setOpen(true);
        }else {
            int y1 = getY()+singleBoxHeight;
            int mouseY = (int) event.y();
            for(SortType type : SortType.values()){
                if(isHoveringOnSingleBox(mouseY,y1)){
                    framework.switchSortTypeTo(type);
                    return;
                }
                y1+= singleBoxHeight;
            }
            setOpen(false);
        }
    }
    /**
     * Called when a mouse button is clicked within the GUI element.
     * @return {@code true} if the event is consumed, {@code false} otherwise.
     */
    public boolean mouseClicked(MouseButtonEvent event, boolean pre){
        if(super.mouseClicked(event, pre)){
            return true;
        }else if(isOpen){
            setOpen(false);
            return true;
        }
        return false;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.pose().pushMatrix();
        guiGraphics.fill(getX(), getY(), getX() + width, getY() + singleBoxHeight, 0xff888888);
        guiGraphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + singleBoxHeight - 1, 0xff000000);
        if (isHoveringOnSingleBox(mouseY, getY()))
            guiGraphics.fillGradient(getX(), getY(), getX() + width, getY() + singleBoxHeight, 0x80ffffff, 0x80ffffff);
        SortType sortType = framework.sortType();
        String s = sortType.toString();
        guiGraphics.text(Minecraft.getInstance().font, s,getX()+2, getY() +2,0xffffffff);
        if(isOpen){
            guiGraphics.nextStratum();
            int y1 = getY() +singleBoxHeight;
            for (SortType type : SortType.values()) {
                guiGraphics.fill(getX(), y1, getX() + width, y1 + singleBoxHeight, 0xff888888);
                guiGraphics.fill(getX() + 1, y1 + 1, getX() + width - 1, y1 + singleBoxHeight - 1, 0xff000000);
                if (isHoveringOnSingleBox(mouseY, y1)) {
                    guiGraphics.fillGradient(getX(), y1, getX() + width, y1 + singleBoxHeight, 0x80ffffff, 0x80ffffff);
                    guiGraphics.tooltip(
                            Minecraft.getInstance().font,
                            List.of(
                                    ClientTooltipComponent.create(
                                            Component.translatable(type.translationKey).getVisualOrderText()
                                    )
                            ),
                            mouseX,
                            mouseY,
                            DefaultTooltipPositioner.INSTANCE,
                            null
                    );
                }
                s = type.toString();
                guiGraphics.text(Minecraft.getInstance().font, s,getX()+2,y1+2,0xffffffff);
                y1+=singleBoxHeight;
            }
        }
        guiGraphics.pose().popMatrix();
    }
    private boolean isHoveringOnSingleBox(int mouseY,int minY){
        return mouseY>=minY && mouseY<=minY+singleBoxHeight && isHovered;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        this.defaultButtonNarrationText(narrationElementOutput);
    }
}
