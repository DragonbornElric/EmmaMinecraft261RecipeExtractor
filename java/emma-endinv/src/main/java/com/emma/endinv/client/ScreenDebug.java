package com.emma.endinv.client;

import com.emma.endinv.client.gui.EndlessInventoryScreen;
import com.emma.endinv.client.gui.ScreenFramework;
import com.emma.endinv.client.gui.page.ItemPage;
import com.emma.endinv.client.option.ClientConfigs;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

import java.util.Optional;

import static com.emma.endinv.client.ClientModInfo.containerScreenHelper;

public class ScreenDebug {

    protected static final String HidingText = "[F4]Menu screen rendering stopped!";

    public static int phase = 0;
    protected static boolean hideMenu = false;
    protected static Minecraft mc;

    public static void debugInfo(Screen screen, GuiGraphicsExtractor graphics, double mouseX, double mouseY){
        if(!ClientConfigs.SCREEN_DEBUG.get()) return;
        int width = screen.width;
        int height = screen.height;
        int imageWidth = 0;
        int imageHeight = 0;
        int guiLeft = 0;
        int guiTop = 0;
        mc = Optional.ofNullable(mc).orElseGet(Minecraft::getInstance);
        if(screen instanceof AbstractContainerScreen<?> ACS){
            imageWidth = containerScreenHelper.getGuiXSize(ACS);
            imageHeight = containerScreenHelper.getGuiYSize(ACS);
            guiLeft = containerScreenHelper.getGuiLeft(ACS);
            guiTop = containerScreenHelper.getGuiTop(ACS);
        }
        {
            int color = 0xFFFFFFFF; //Nontransparent white
            if (phase == 1) {//16
                for (int x = 0; x < width; x += 16) {
                    color = x % 512 == 0 ? 0xFF000088 : x % 256 == 0 ? 0xFF00AAFF : x % 64 == 0 ? 0xCC00FFFF : 0x2200FFFF;
                    //graphics.fill(x, 0, x + 1, height, color);
                    graphics.verticalLine(x, 0, height, color);
                }
                for (int y = 0; y < height; y += 16) {
                    color = y % 512 == 0 ? 0xFF000088 : y % 256 == 0 ? 0xFF00AAFF : y % 64 == 0 ? 0xCC00FFFF : 0x2200FFFF;
                    graphics.horizontalLine(0, width, y, color);
                }
            } else if (phase == 2) {
                for (int x = 0; x < width; x += 10) {
                    color = x % 200 == 0 ? 0xFF004400 : x % 100 == 0 ? 0xCC008855 : x % 50 == 0 ? 0xCC00FF00 : 0x2200FF00;
                    graphics.verticalLine(x, 0, height, color);
                }
                for (int y = 0; y < height; y += 10) {
                    color = y % 200 == 0 ? 0xFF004400 : y % 100 == 0 ? 0xCC008855 : y % 50 == 0 ? 0xCC00FF00 : 0x2200FF00;
                    graphics.horizontalLine(0, width, y, color);
                }
            }
            if (phase > 0) {
                graphics.horizontalLine(0, width, (int) mouseY, 0xff888822);
                graphics.verticalLine((int) mouseX, 0, height, 0xff888822);

                graphics.text(mc.font, Component.literal("[F3]Screen Debug enabled"),0,0,0xFFFFAA00);

                graphics.text(mc.font, Component.literal("Width: " + width), width - 128, 10, 0xFFFFFF00);
                graphics.text(mc.font, Component.literal("Height: " + height), width - 128, 20, 0xFFFFFF00);
                graphics.text(mc.font, Component.literal("ImageWidth/width: " + imageWidth), width - 128, 30, 0xFFFFFF00);
                graphics.text(mc.font, Component.literal("ImageHeight/height: " + imageHeight), width - 128, 40, 0xFFFFFF00);
                graphics.text(mc.font, Component.literal("GUILeft: " + guiLeft), width - 128, 50, 0xFFFFFF00);
                graphics.text(mc.font, Component.literal("GUITop: " + guiTop), width - 128, 60, 0xFFFFFF00);
                graphics.text(mc.font, Component.literal("mouseX: " + mouseX), width - 128, 70, 0xFFFFFF00);
                graphics.text(mc.font, Component.literal("mouseY: " + mouseY), width - 128, 80, 0xFFFFFF00);

                if(ScreenFramework.getInstance() !=null) {
                    ScreenFramework framework = ScreenFramework.getInstance();
                    graphics.text(mc.font, Component.literal("Page data: "), 0, 10, 0xFFFFFF00);
                    graphics.text(mc.font, Component.literal("Page: "+framework.getDisplayingPageId()), 0, 20, 0xFFFFFF00);
                    graphics.text(mc.font, Component.literal("Rows: "+framework.rows()), 10, 30, 0xFFFFFF00);
                    graphics.text(mc.font, Component.literal("Columns: "+framework.columns()), 10, 40, 0xFFFFFF00);
                    graphics.text(mc.font, Component.literal("Sort type: "+framework.sortType()), 10, 50, 0xFFFFFF00);
                    graphics.text(mc.font, Component.literal("Sort reversed: "+framework.isSortReversed()), 10, 60, 0xFFFFFF00);
                    graphics.text(mc.font, Component.literal("Searching: "+framework.searching()), 10, 70, 0xFFFFFF00);
                }
            }
        }
    }

    public static void click(int button, Screen screen){
        if(!ClientConfigs.SCREEN_DEBUG.get()) return;

        if(button == InputConstants.KEY_F3){
            phase++;
            if(phase>3) phase=0;
        }
        if(button==InputConstants.KEY_F4){
            hideMenu=!hideMenu;
        }
        if(screen instanceof EndlessInventoryScreen EIS && EIS.getFrameWork() != null){
        int step = (Minecraft.getInstance().hasShiftDown() ? 10 : 1);
            int dx = 0;
            int dy = 0;
            if(button == InputConstants.KEY_UP){
                dy = -step;
            } else if(button == InputConstants.KEY_DOWN){
                dy = step;
            } else if(button == InputConstants.KEY_LEFT){
                dx = -step;
            } else if(button == InputConstants.KEY_RIGHT){
                dx = step;
            }
            if(dx != 0 || dy != 0){
                // Nudge the framework and page together so debug movements stay aligned.
                EIS.getFrameWork().move(dx, dy);
            }
            if(EIS.getFrameWork().getDisplayingPage() instanceof ItemPage itemPage){
                if(button == InputConstants.KEY_R){
                    itemPage.requestRemoteContents();
                }
                if(button == InputConstants.KEY_T){
                    itemPage.setChanged();
                }
            }
        }
    }
}



