package com.emma.endinv.client.gui.bg;

import com.emma.endinv.client.ClientModInfo;
import com.emma.endinv.client.gui.ScreenFramework;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

public abstract class SFBgRendererImpl implements SFBgRenderer {

    protected final ScreenFramework frameWork;

    protected final int menuLeft;
    protected final int menuTop;
    protected final int pageLeft;
    protected final int pageTop;
    protected final AbstractContainerScreen<?> screen;
    public IRectangleParam pageSwitchTabParam;
    protected final int imageWidth;

    protected final int rows;
    protected final int columns;

    public SFBgRendererImpl(ScreenFramework frameWork){
        this.frameWork = frameWork;
        this.screen = frameWork.screen;
        // Use the actual page/container width computed by the ScreenFramework
        // instead of the source texture sheet size (256). The width here
        // represents how wide the UI area should be drawn on screen.
        this.imageWidth = frameWork.imageWidth;
        this.rows = this.frameWork.rows();
        this.columns = this.frameWork.columns();
        this.menuLeft = ClientModInfo.containerScreenHelper.getGuiLeft(screen);
        this.menuTop = ClientModInfo.containerScreenHelper.getGuiTop(screen);
        this.pageLeft = frameWork.leftPos;
        this.pageTop = frameWork.topPos;
    }

    @Override
    public IRectangleParam pageSwitchBarParam() {
        return pageSwitchTabParam;
    }

    @Override
    public ScreenFramework getScreenFrameWork(){
        return frameWork;
    }
}
