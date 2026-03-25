package com.emma.endinv.client.gui;

import com.emma.endinv.ModInfo;
import com.emma.endinv.client.ClientModInfo;
import com.emma.endinv.client.gui.bg.IRectangleParam;
import com.emma.endinv.client.option.ClientConfigs;
import com.emma.endinv.client.option.MenuAttachabilityCache;
import com.emma.endinv.network.payloads.toServer.OpenEndInvPayload;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;

import static com.emma.endinv.ModRegistries.NbtAttachments.getSyncedConfig;


/**The exceeded screen that controls interaction with Endless Inventory in client side.
 * Attached by an {@link AbstractContainerScreen<T>}
 * @param <T>
 *
 * @author Kay Zhang
 * @since 2025-6-4
 * @version 1.0.5
 */
public class AttachingScreen<T extends AbstractContainerMenu>{
    @Nullable
    public static AttachingScreen<?> INSTANCE;

    private static final Logger LOGGER = LogUtils.getLogger();

    public final AbstractContainerScreen<T> screen;
    public final T menu;

    private ScreenFramework frameWork;

    //invoked when an ACS is created or initialized
    public AttachingScreen(AbstractContainerScreen<T> screen){
        this.screen = screen;
        this.menu = screen.getMenu();
        INSTANCE = this;
    }
    @SuppressWarnings("nullable")
    public static boolean isAttachable(AbstractContainerScreen<?> screen){
        try{
            boolean doAttach = ClientConfigs.DO_ATTACH.get();
            boolean syncedCheck = getSyncedConfig().computeIfAbsent(Minecraft.getInstance().player).checkForAttaching();
            boolean menuAttachable = ClientConfigs.SPECIFIED_ATTACHABILITY.get().isMenuAttachable(screen.getMenu());
            boolean cacheAttachable = MenuAttachabilityCache.isAttachable(screen);
            if (!doAttach || !syncedCheck || !menuAttachable || !cacheAttachable) {
                LOGGER.info("[endinv] isAttachable failed: doAttach={} syncedCheck={} menuAttachable={} cacheAttachable={} menu={}",
                        doAttach, syncedCheck, menuAttachable, cacheAttachable, screen.getMenu().getClass().getSimpleName());
            }
            return doAttach && syncedCheck && menuAttachable && cacheAttachable;
        } catch (RuntimeException e){
            LOGGER.error("[endinv] isAttachable exception", e);
        }
        return false;
    }

    public static net.minecraft.client.gui.components.Button configButton(Screen screen, IRectangleParam configButtonParam,
                                                                          Runnable open, Runnable close) {
        Minecraft mc = Minecraft.getInstance();
        return Button.builder(
                Component.literal("⚙"),
                        btn -> {
                        if(Minecraft.getInstance().hasShiftDown()){
                            mc.setScreen(ClientModInfo.createConfigScreen(screen));
                        } else {
                            boolean currentA = ClientConfigs.DO_ATTACH.get();
                            currentA = !currentA;
                            ClientConfigs.DO_ATTACH.set(currentA);
                            if(currentA){
                                open.run();
                            }else {
                                close.run();
                            }
                        }
                })
                .pos(configButtonParam.x(), configButtonParam.y())
                .size(configButtonParam.width(), configButtonParam.height())
                .tooltip(Tooltip.create(Component.translatableWithFallback("endinv.configbutton", "Toggle endinv attached menu, shift for settings.")))
                .build();
    }

    //invoked when an ACS is initialized
    public void init(IScreenEvent event){
        this.frameWork = ScreenFramework.getInstance()==null ? new ScreenFramework(this) : ScreenFramework.getInstance();
        frameWork.addWidgetToScreen(event::addListener);
        // Ensure server-side manager attaches for the current menu so actions like quick-move are authoritative
        ModInfo.getPacketDistributor().sendToServer(new OpenEndInvPayload(false, frameWork.rows()));
    }

    public void renderPre(IScreenEvent event) {
        int mouseX = (int) event.getMouseX();
        int mouseY = (int) event.getMouseY();
        GuiGraphicsExtractor guiGraphics = event.getGuiGraphicsExtractor();
        float partialTick = event.getPartialTick();

        frameWork.renderBg(guiGraphics, mouseX, mouseY, partialTick);
        frameWork.render(guiGraphics,mouseX,mouseY,partialTick);
    }

    public void render(IScreenEvent event) {
//        int mouseX = (int) event.getMouseX();
//        int mouseY = (int) event.getMouseY();
//        GuiGraphicsExtractor guiGraphics = event.getGuiGraphicsExtractor();
//        float partialTick = event.getPartialTick();

        //frameWork.renderBg(guiGraphics,mouseX,mouseY,partialTick);
        //frameWork.render(guiGraphics,mouseX,mouseY,partialTick);
    }


    public void mouseClicked(IScreenEvent event) {
        boolean isActionOverride = frameWork.mouseClicked(event.getMouseButtonEvent(), true);
        event.setCanceled(isActionOverride);
    }


    public void mouseReleased(IScreenEvent event) {
        event.setCanceled(frameWork.mouseReleased(event.getMouseButtonEvent()));
    }

    public void mouseDragged(IScreenEvent event) {
        double dragX = event.getDragX();
        double dragY = event.getDragY();

        event.setCanceled(frameWork.mouseDragged(event.getMouseButtonEvent(), dragX, dragY));
    }

    public void mouseScrolled(IScreenEvent event) {
        frameWork.mouseScrolled(event.getMouseX(), event.getMouseY(), event.getScrollDeltaX(), event.getScrollDeltaY());
    }

    public void keyPressed(IScreenEvent event) {
        event.setCanceled(frameWork.keyPressed(event.getKeyEvent()));
    }

    public void charTyped(IScreenEvent event) {
        event.setCanceled(frameWork.charTyped(event.getCharEvent()));
    }

    public void closed(IScreenEvent event){
        frameWork.onClose();
        close("On attached screen closing");
    }

    public void close(@Nullable String reason){
        INSTANCE = null;
        LOGGER.info("Attached Screen {} closed with reason: {}", this, reason);
    }

    public AbstractContainerScreen<?> getScreen() {
        return screen;
    }

    public ScreenFramework getFrameWork(){
        return frameWork;
    }

    public List<Rect2i> getArea(){
        return List.of(new Rect2i(frameWork.leftPos, frameWork.topPos, frameWork.imageWidth, frameWork.imageHeight));
    }

}















