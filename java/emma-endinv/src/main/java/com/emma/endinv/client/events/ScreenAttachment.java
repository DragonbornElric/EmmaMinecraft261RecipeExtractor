package com.emma.endinv.client.events;

import com.emma.endinv.ModInfo;
import com.emma.endinv.client.gui.AttachingScreen;
import com.emma.endinv.client.gui.EndlessInventoryScreen;
import com.emma.endinv.client.gui.IScreenEvent;

import com.emma.endinv.client.gui.bg.IRectangleParam;
import com.emma.endinv.client.option.ClientConfigs;
import com.emma.endinv.network.payloads.toServer.OpenEndInvPayload;
import com.emma.endinv.mixin.AbstractContainerScreenAccessor;
import com.emma.endinv.mixin.ScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.entity.player.Player;

import org.jetbrains.annotations.Nullable;

public final class ScreenAttachment {

    @Nullable
    public static AttachingScreen<?> attachment;

    private static boolean charTypedEventsRegistered;
    /** Tracks the parent container's leftPos to detect recipe book toggles */
    private static int lastContainerLeft = -1;

    private ScreenAttachment() {
    }

    public static void register() {
        if (!charTypedEventsRegistered) {
            ScreenCharTypedEvents.BEFORE_CHAR_TYPED.register(ScreenAttachment::beforeCharTyped);
            charTypedEventsRegistered = true;
        }

        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) -> {
            if (screen instanceof AbstractContainerScreen<?>) {
                // Force config re-read before screen init (1.21.11 parity)
                ClientConfigs.ATTACHED_MENU_CONFIG.get();
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container) || screen instanceof EndlessInventoryScreen) {
                return;
            }

            // Add independent config/toggle button (Shift opens settings)
            IRectangleParam btnParam = ClientConfigs.ATTACHED_MENU_CONFIG.get().adjust(container).configButtonA();
            ((ScreenAccessor) screen).endinv$invokeAddRenderableWidget(
                    AttachingScreen.configButton(
                            screen,
                            btnParam,
                            () -> {
                                if (attachment == null) {
                                    ModInfo.getPacketDistributor().sendToServer(new OpenEndInvPayload());
                                    attachment = new AttachingScreen<>(container);
                                    attachment.init(new IScreenEvent() {
                                        @Override
                                        public void addListener(AbstractWidget widget) {
                                            ((ScreenAccessor) screen).endinv$invokeAddRenderableWidget(widget);
                                        }
                                    });
                                }
                            },
                            () -> {
                                if (attachment != null) {
                                    attachment.closed(new IScreenEvent() {});
                                    attachment = null;
                                }
                            }
                    )
            );

            Player player = client.player;
            if (player == null) {
                attachment = null;
                return;
            }

            if (AttachingScreen.isAttachable(container)) {
                if (attachment == null) {
                    ModInfo.getPacketDistributor().sendToServer(new OpenEndInvPayload());
                    attachment = new AttachingScreen<>(container);
                    attachment.init(new IScreenEvent() {
                        @Override
                        public void addListener(AbstractWidget widget) {
                            ((ScreenAccessor) screen).endinv$invokeAddRenderableWidget(widget);
                        }
                    });
                }
            }

            ScreenEvents.remove(screen).register(s -> {
                if (attachment != null) {
                    attachment.closed(new IScreenEvent() {});
                    attachment = null;
                }
            });

            // Track initial container leftPos for recipe book detection
            lastContainerLeft = ((AbstractContainerScreenAccessor) container).endinv$getLeftPos();

            // Render the attached EndInv panel via Fabric screen events (works for all screen subclasses)
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
                AttachingScreen<?> current = attachment;
                if (current == null || current.getScreen() != s || !isAttachmentActive(current)) {
                    return;
                }
                // Detect recipe book toggle: container leftPos changes when it opens/closes
                int currentLeft = ((AbstractContainerScreenAccessor) container).endinv$getLeftPos();
                if (currentLeft != lastContainerLeft) {
                    lastContainerLeft = currentLeft;
                    // Rebuild with updated layout (columns recalculated for recipe book state)
                    current.closed(new IScreenEvent() {});
                    attachment = new AttachingScreen<>(container);
                    attachment.init(new IScreenEvent() {
                        @Override
                        public void addListener(AbstractWidget widget) {
                            ((ScreenAccessor) s).endinv$invokeAddRenderableWidget(widget);
                        }
                    });
                    current = attachment;
                }
                current.renderPre(new IScreenEvent() {
                    @Override public double getMouseX() { return mouseX; }
                    @Override public double getMouseY() { return mouseY; }
                    @Override public float getPartialTick() { return delta; }
                    @Override public GuiGraphicsExtractor getGuiGraphicsExtractor() { return graphics; }
                });
            });

            // Register input handlers — they read the static `attachment` field at invocation time,
            // so they work correctly even when attachment is created later via the toggle button.
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> allowMouseClick(attachment, event));
            ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> allowMouseRelease(attachment, event));
            ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontal, vertical) -> allowMouseScroll(attachment, mouseX, mouseY, horizontal, vertical));

            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> allowKeyPress(attachment, event));

        });
    }

    private static boolean allowMouseClick(AttachingScreen<?> expected, MouseButtonEvent buttonEvent) {
        if (attachment != expected || !isAttachmentActive(expected)) {
            return true;
        }
        boolean[] canceled = {false};
        expected.mouseClicked(new IScreenEvent() {
            public MouseButtonEvent getMouseButtonEvent(){
                return buttonEvent;
            }

            @Override
            public void setCanceled(boolean flag) {
                canceled[0] = flag;
            }
        });
        return !canceled[0];
    }

    private static boolean allowMouseRelease(AttachingScreen<?> expected, MouseButtonEvent buttonEvent) {
        if (attachment != expected || !isAttachmentActive(expected)) {
            return true;
        }
        boolean[] canceled = {false};
        expected.mouseReleased(new IScreenEvent() {

            public MouseButtonEvent getMouseButtonEvent(){
                return buttonEvent;
            }

            @Override
            public void setCanceled(boolean flag) {
                canceled[0] = flag;
            }
        });
        return !canceled[0];
    }

    public static void onRenderAfterBackground(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        AttachingScreen<?> current = attachment;
        if (current == null || current.getScreen() != screen || !isAttachmentActive(current)) {
            return;
        }
        current.renderPre(new IScreenEvent() {
            @Override
            public double getMouseX() { return mouseX; }

            @Override
            public double getMouseY() { return mouseY; }

            @Override
            public float getPartialTick() { return partialTick; }

            @Override
            public GuiGraphicsExtractor getGuiGraphicsExtractor() { return graphics; }
        });
    }

    // Reserved for potential overlay rendering parity (currently no-op in common)
    public static void onRenderPost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        AttachingScreen<?> current = attachment;
        if (current == null || current.getScreen() != screen || !isAttachmentActive(current)) {
            return;
        }
        current.render(new IScreenEvent() {
            @Override
            public double getMouseX() { return mouseX; }

            @Override
            public double getMouseY() { return mouseY; }

            @Override
            public float getPartialTick() { return partialTick; }

            @Override
            public GuiGraphicsExtractor getGuiGraphicsExtractor() { return graphics; }
        });
    }

    private static boolean allowMouseScroll(AttachingScreen<?> expected, double mouseX, double mouseY, double horizontal, double vertical) {
        if (attachment != expected || !isAttachmentActive(expected)) {
            return true;
        }
        boolean[] canceled = new boolean[]{false};
        expected.mouseScrolled(new IScreenEvent() {
            @Override
            public double getMouseX() {
                return mouseX;
            }

            @Override
            public double getMouseY() {
                return mouseY;
            }

            @Override
            public double getScrollDeltaY() {
                return vertical;
            }

            @Override
            public double getScrollDeltaX() {
                return horizontal;
            }

            @Override
            public void setCanceled(boolean canceled1){
                canceled[0] = canceled1;
            }
        });
        return !canceled[0];
    }

    private static boolean allowKeyPress(AttachingScreen<?> expected, KeyEvent keyEvent) {
        if (attachment != expected || !isAttachmentActive(expected)) {
            return true;
        }
        boolean[] canceled = {false};
        expected.keyPressed(new IScreenEvent() {
            public KeyEvent getKeyEvent(){
                return keyEvent;
            }

            @Override
            public void setCanceled(boolean flag) {
                canceled[0] = flag;
            }
        });
        return !canceled[0];
    }

    public static boolean handleMouseDrag(AbstractContainerScreen<?> screen, MouseButtonEvent event, double deltaX, double deltaY) {
        AttachingScreen<?> current = attachment;
        if (current == null || current.screen != screen || !isAttachmentActive(current)) {
            return false;
        }
        boolean[] canceled = {false};
        current.mouseDragged(new IScreenEvent() {

            @Override
            public double getDragX() {
                return deltaX;
            }

            @Override
            public double getDragY() {
                return deltaY;
            }

            @Override
            public void setCanceled(boolean flag) {
                canceled[0] = flag;
            }

            public MouseButtonEvent getMouseButtonEvent(){
                return event;
            }
        });
        return canceled[0];
    }

    private static boolean beforeCharTyped(GuiEventListener guiEventListener, CharacterEvent event) {
        AttachingScreen<?> current = attachment;
        if (current == null) {
            return false;
        }
        if (!(guiEventListener instanceof Screen screen) || current.screen != screen) {
            return false;
        }
        if (!isAttachmentActive(current)) {
            return false;
        }
        boolean[] canceled = {false};
        current.charTyped(new IScreenEvent() {
            @Override
            public CharacterEvent getCharEvent() {
                return event;
            }

            @Override
            public void setCanceled(boolean flag) {
                canceled[0] = flag;
            }
        });
        return canceled[0];
    }

    private static boolean isAttachmentActive(@Nullable AttachingScreen<?> expected) {
        Screen screen = Minecraft.getInstance().screen;
        if (!(screen instanceof AbstractContainerScreen<?> c)) {
            attachment = null;
            return false;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            attachment = null;
            return false;
        }

        if (expected == null || expected.screen != screen) {
            return false;
        }
        if (!AttachingScreen.isAttachable(c)) {
            attachment = null;
            return false;
        }
        return true;
    }
}


