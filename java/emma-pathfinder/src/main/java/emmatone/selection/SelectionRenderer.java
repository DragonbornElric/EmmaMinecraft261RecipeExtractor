package emmatone.selection;

import emmatone.Emmatone;
import emmatone.api.event.events.RenderEvent;
import emmatone.api.event.listener.AbstractGameEventListener;
import emmatone.api.selection.ISelection;
import emmatone.utils.IRenderer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.AABB;

public class SelectionRenderer implements IRenderer, AbstractGameEventListener {

    public static final double SELECTION_BOX_EXPANSION = .005D;

    private final SelectionManager manager;

    SelectionRenderer(Emmatone emmatone, SelectionManager manager) {
        this.manager = manager;
        emmatone.getGameEventHandler().registerEventListener(this);
    }

    public static void renderSelections(PoseStack stack, ISelection[] selections) {
        float opacity = settings.selectionOpacity.value;
        boolean ignoreDepth = settings.renderSelectionIgnoreDepth.value;
        float lineWidth = settings.selectionLineWidth.value;

        if (!settings.renderSelection.value || selections.length == 0) {
            return;
        }

        BufferBuilder bufferBuilder = IRenderer.startLines(settings.colorSelection.value, opacity, lineWidth);

        for (ISelection selection : selections) {
            IRenderer.emitAABB(bufferBuilder, stack, selection.aabb(), SELECTION_BOX_EXPANSION);
        }

        if (settings.renderSelectionCorners.value) {
            IRenderer.glColor(settings.colorSelectionPos1.value, opacity);

            for (ISelection selection : selections) {
                IRenderer.emitAABB(bufferBuilder, stack, new AABB(selection.pos1()));
            }

            IRenderer.glColor(settings.colorSelectionPos2.value, opacity);

            for (ISelection selection : selections) {
                IRenderer.emitAABB(bufferBuilder, stack, new AABB(selection.pos2()));
            }
        }

        IRenderer.endLines(bufferBuilder, ignoreDepth);
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        renderSelections(event.getModelViewStack(), manager.getSelections());
    }
}
