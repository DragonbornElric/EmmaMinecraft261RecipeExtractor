package com.emma.endinv.client.gui;

import com.emma.endinv.ModInfo;
import com.emma.endinv.menu.EndlessInventoryMenu;
import com.emma.endinv.network.payloads.toServer.ToggleCraftingPayload;
import com.emma.endinv.util.NotNullWhenInitialized;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

public class EndlessInventoryScreen extends AbstractContainerScreen<EndlessInventoryMenu> {
    private static final Identifier CRAFTING_TEXTURE = Identifier.fromNamespaceAndPath("minecraft", "textures/gui/container/crafting_table.png");
    @NotNullWhenInitialized
    private ScreenFramework frameWork;
    @Nullable
    private CycleButton<Boolean> craftingToggleButton;
    private boolean craftingVisible;

    public EndlessInventoryScreen(EndlessInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, 176, 114 + menu.getBaseRows() * 18);
        this.inventoryLabelY = this.imageHeight - 94;
    }

    public void init(){
        super.init();
        craftingVisible = menu.isCraftingVisible();
        this.inventoryLabelY = this.imageHeight - 94;
        this.leftPos = (this.width - this.imageWidth) / 2;
        this.topPos = (this.height - this.imageHeight) / 2;
        var existing = ScreenFramework.getInstance();
        if (existing != null) {
            existing.onClose();
        }
        this.frameWork = new ScreenFramework(this);

        frameWork.addWidgetToScreen(this::addRenderableWidget);
        addCraftingToggleButton();
        // Ensure initial UI matches menu's crafting visibility and row count
        if (this.craftingToggleButton != null) {
            this.craftingToggleButton.setValue(craftingVisible);
        }
        if (this.frameWork != null) {
            this.frameWork.resizePageRows(menu.getVisibleRows());
        }
    }

    private void addCraftingToggleButton() {
        int width = 70;
        this.craftingToggleButton = CycleButton.onOffBuilder(false)
                .create(0,0,width,20,Component.literal("Crafter"), (it,on)->{
                    toggleCrafting();
                    if(it.getValue()!=craftingVisible) it.setValue(craftingVisible);
                });
        updateCraftingToggleButtonPosition();
        addRenderableWidget(this.craftingToggleButton);
    }

    /**
     * Keeps the crafting toggle anchored to the screen chrome after layout changes.
     */
    private void updateCraftingToggleButtonPosition() {
        if (this.craftingToggleButton == null) {
            return;
        }
        int width = this.craftingToggleButton.getWidth();
        int x = this.leftPos + this.imageWidth - width - 8;
        int y = this.topPos - 20;
        this.craftingToggleButton.setX(x);
        this.craftingToggleButton.setY(y);
    }

    /**
     * Toggle crafter visibility and realign the surrounding widgets without rebuilding the screen.
     */
    private void toggleCrafting() {
        craftingVisible = !craftingVisible;
        menu.setCraftingVisible(craftingVisible);
        ModInfo.getPacketDistributor().sendToServer(new ToggleCraftingPayload(craftingVisible));
        int previousTop = this.topPos;
        // imageHeight is final in MC 26.1; layout is fixed from constructor
        this.leftPos = (this.width - this.imageWidth) / 2;
        this.topPos = (this.height - this.imageHeight) / 2;
        updateCraftingToggleButtonPosition();
        if (frameWork != null) {
            frameWork.resizePageRows(menu.getVisibleRows());
            frameWork.move(0, this.topPos - previousTop);
        }
    }

    private void drawCraftingBackground(GuiGraphicsExtractor guiGraphics) {
        int craftX = this.leftPos;
        int craftY = this.topPos + 18 * menu.getVisibleRows() + 18;
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, CRAFTING_TEXTURE, craftX, craftY, 0, 12, 176, 58, 256, 256);
    }

    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick){
        this.extractBackground(guiGraphics, mouseX, mouseY, partialTick);
        frameWork.renderBg(guiGraphics,mouseX,mouseY,partialTick);
        if (menu.isCraftingVisible()) {
            drawCraftingBackground(guiGraphics);
        }
        frameWork.render(guiGraphics,mouseX,mouseY,partialTick);
        super.extractRenderState(guiGraphics,mouseX,mouseY,partialTick);

        this.extractTooltip(guiGraphics,mouseX,mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean pre) {
        for(GuiEventListener guieventlistener : this.children()) {
            if (guieventlistener.mouseClicked(event, pre)) {
                this.setFocused(guieventlistener);
                if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                    this.setDragging(true);
                }
                return true;
            }
        }
        return frameWork.mouseClicked(event, pre) || super.mouseClicked(event, pre);

    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double x, double y) {
        return frameWork.mouseDragged(event, x, y) || super.mouseDragged(event, x, y);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent p_446114_) {
        return frameWork.mouseReleased(p_446114_) || super.mouseReleased(p_446114_);
    }

    @Override
    public boolean mouseScrolled(double p_364830_, double p_360707_, double p_364436_, double p_364417_) {
        return super.mouseScrolled(p_364830_, p_360707_, p_364436_, p_364417_) || frameWork.mouseScrolled(p_364830_, p_360707_, p_364436_, p_364417_);
    }

    @Override
    public boolean keyPressed(KeyEvent p_445387_) {
        if (this.minecraft != null && this.minecraft.options.keyInventory.matches(p_445387_)) {
            this.onClose();
            return true;
        }
        return frameWork.keyPressed(p_445387_) || super.keyPressed(p_445387_);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return frameWork.charTyped(event);
    }

    protected void slotClicked(Slot slot, int slotId, int mouseButton, ContainerInput type) {
        super.slotClicked(slot,slotId,mouseButton,type);
        this.menu.broadcastChanges();
    }

    public void onClose(){
        super.onClose();
        frameWork.onClose();
    }


    public com.emma.endinv.menu.page.pageManager.PageMetaDataManager getPageManager() {
        return menu;
    }

    public AbstractContainerScreen<?> getScreen() {
        return this;
    }

    public ScreenFramework getFrameWork() {
        return frameWork;
    }

    public int getGuiLeft() {
        return leftPos;
    }

    public int getGuiTop() {
        return topPos;
    }

    public int getXSize() {
        return imageWidth;
    }

    public int getYSize() {
        return imageHeight;
    }

}

