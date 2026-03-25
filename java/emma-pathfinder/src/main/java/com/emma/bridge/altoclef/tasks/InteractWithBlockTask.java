package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.baritone.GoalAnd;
import adris.altoclef.util.baritone.GoalBlockSide;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.control.BlockInteraction;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.TaskHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.process.ICustomGoalProcess;
import adris.altoclef.control.DirectInput;
import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;

import java.util.Objects;
import java.util.Optional;

/**
 * Left or Right click on a block on a particular (or any) side of the block.
 * <p>
 * Uses pure distance check + direct Mojang API calls for interaction,
 * matching the Phase 52-53 Baritone pattern. No raycast gating, no crosshairTarget
 * dependency — constructs BlockHitResult directly from known BlockPos + computed face.
 */
public class InteractWithBlockTask extends Task {
    private final MovementProgressChecker moveChecker = new MovementProgressChecker();
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final ItemTarget toUse;
    private final Direction direction;
    private final BlockPos target;
    private final boolean walkInto;
    private final Vec3i interactOffset;
    private final Input interactInput;
    private final boolean shiftClick;
    private final TimerGame clickTimer = new TimerGame(5);
    private final TimeoutWanderTask wanderTask = new TimeoutWanderTask(5, true);
    private Task unstuckTask = null;
    private ClickResponse cachedClickStatus = ClickResponse.CANT_REACH;
    private int waitingForClickTicks = 0;
    private int interactCooldownTicks = 0;

    public InteractWithBlockTask(ItemTarget toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, Vec3i interactOffset, boolean shiftClick) {
        this.toUse = toUse;
        this.direction = direction;
        this.target = target;
        this.interactInput = interactInput;
        this.walkInto = walkInto;
        this.interactOffset = interactOffset;
        this.shiftClick = shiftClick;
    }

    public InteractWithBlockTask(ItemTarget toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, boolean shiftClick) {
        this(toUse, direction, target, interactInput, walkInto, Vec3i.ZERO, shiftClick);
    }

    public InteractWithBlockTask(ItemTarget toUse, Direction direction, BlockPos target, boolean walkInto) {
        this(toUse, direction, target, Input.CLICK_RIGHT, walkInto, true);
    }

    public InteractWithBlockTask(ItemTarget toUse, BlockPos target, boolean walkInto, Vec3i interactOffset) {
        // null means any side is OK
        this(toUse, null, target, Input.CLICK_RIGHT, walkInto, interactOffset, true);
    }

    public InteractWithBlockTask(ItemTarget toUse, BlockPos target, boolean walkInto) {
        this(toUse, target, walkInto, Vec3i.ZERO);
    }

    public InteractWithBlockTask(ItemTarget toUse, BlockPos target) {
        this(toUse, target, false);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, Vec3i interactOffset, boolean shiftClick) {
        this(new ItemTarget(toUse, 1), direction, target, interactInput, walkInto, interactOffset, shiftClick);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, boolean shiftClick) {
        this(new ItemTarget(toUse, 1), direction, target, interactInput, walkInto, shiftClick);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target, boolean walkInto) {
        this(new ItemTarget(toUse, 1), direction, target, walkInto);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target) {
        this(new ItemTarget(toUse, 1), direction, target, Input.CLICK_RIGHT, false, false);
    }

    public InteractWithBlockTask(Item toUse, BlockPos target, boolean walkInto, Vec3i interactOffset) {
        this(new ItemTarget(toUse, 1), target, walkInto, interactOffset);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target, Vec3i interactOffset) {
        this(new ItemTarget(toUse, 1), direction, target, Input.CLICK_RIGHT, false, interactOffset, false);
    }

    public InteractWithBlockTask(Item toUse, BlockPos target, Vec3i interactOffset) {
        this(new ItemTarget(toUse, 1), null, target, Input.CLICK_RIGHT, false, interactOffset, false);
    }

    public InteractWithBlockTask(Item toUse, BlockPos target, boolean walkInto) {
        this(new ItemTarget(toUse, 1), target, walkInto);
    }

    public InteractWithBlockTask(Item toUse, BlockPos target) {
        this(new ItemTarget(toUse, 1), target);
    }

    public InteractWithBlockTask(BlockPos target, boolean shiftClick) {
        this(ItemTarget.EMPTY, null, target, Input.CLICK_RIGHT, false, shiftClick);
    }

    public InteractWithBlockTask(BlockPos target) {
        this(ItemTarget.EMPTY, null, target, Input.CLICK_RIGHT, false, false);
    }


    private static Goal createGoalForInteract(BlockPos target, int reachDistance, Direction interactSide, Vec3i interactOffset, boolean walkInto) {

        boolean sideMatters = interactSide != null;
        if (sideMatters) {
            Vec3i offs = interactSide.getVector();
            if (offs.getY() == -1) {
                // If we're below, place ourselves two blocks below.
                offs = offs.down();
            }
            target = target.add(offs);
        }

        if (walkInto) {
            return new GoalTwoBlocks(target);
        } else {
            if (sideMatters) {
                // Make sure we're on the right side of the block.
                Goal sideGoal = new GoalBlockSide(target, interactSide, 1);
                return new GoalAnd(sideGoal, new GoalNear(target.add(interactOffset), reachDistance));
            } else {
                // TODO: Cleaner method of picking which side to approach from. This is only here for the lava stuff.
                return new GoalTwoBlocks(target.up());
                //return new GoalNear(target.add(interactOffset), reachDistance);
            }
        }
    }


    @Override
    protected void onStart() {
        AltoClef.getInstance().getClientBaritone().getPathingBehavior().forceCancel();

        moveChecker.reset();
        stuckCheck.reset();
        wanderTask.resetWander();
        clickTimer.reset();
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            moveChecker.reset();
        }
        if (TaskHelper.handleNetherPortalEscape(mod)) {
            setDebugState("Getting out from nether portal");
            return null;
        }
        if (unstuckTask != null && unstuckTask.isActive() && !unstuckTask.isFinished() && TaskHelper.stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return unstuckTask;
        }
        if (!moveChecker.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = TaskHelper.stuckInBlock(mod);
            if (blockStuck != null) {
                unstuckTask = TaskHelper.getUnstuckTask();
                return unstuckTask;
            }
            stuckCheck.reset();
        }

        cachedClickStatus = ClickResponse.CANT_REACH;

        // Get our use item first
        if (!ItemTarget.nullOrEmpty(toUse) && !StorageHelper.itemTargetsMet(mod, toUse)) {
            moveChecker.reset();
            clickTimer.reset();
            return TaskCatalogue.getItemTask(toUse);
        }

        // Wander and check
        if (wanderTask.isActive() && !wanderTask.isFinished()) {
            moveChecker.reset();
            clickTimer.reset();
            return wanderTask;
        }
        if (!moveChecker.check(mod)) {
            Debug.logMessage("Failed, blacklisting and wandering.");
            mod.getBlockScanner().requestBlockUnreachable(target);
            return wanderTask;
        }

        int reachDistance = 0;
        Goal moveGoal = createGoalForInteract(target, reachDistance, direction, interactOffset, walkInto);
        ICustomGoalProcess proc = mod.getClientBaritone().getCustomGoalProcess();

        cachedClickStatus = rightClick(mod);
        switch (Objects.requireNonNull(cachedClickStatus)) {
            case CANT_REACH -> {
                setDebugState("Getting to our goal");
                // Get to our goal then
                if (!proc.isActive()) {
                    proc.setGoalAndPath(moveGoal);
                }
                clickTimer.reset();
            }
            case WAIT_FOR_CLICK -> {
                setDebugState("Waiting for click");
                if (proc.isActive()) {
                    proc.onLostControl();
                }
                clickTimer.reset();

                // try to get unstuck by pressing shift
                waitingForClickTicks++;
                if (waitingForClickTicks % 25 == 0 && shiftClick) {
                    DirectInput.setSneaking(true);
                    mod.log("trying to press shift");
                }

                if (waitingForClickTicks > 10*20) {
                    mod.log("trying to wander");
                    waitingForClickTicks = 0;
                    return wanderTask;
                }
            }
            case CLICK_ATTEMPTED -> {
                setDebugState("Clicking.");
                if (proc.isActive()) {
                    proc.onLostControl();
                }
                if (clickTimer.elapsed()) {
                    // We tried clicking but failed.
                    clickTimer.reset();
                    return wanderTask;
                }
            }
        }

        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();

        mod.getClientBaritone().getPathingBehavior().forceCancel();
        DirectInput.setSneaking(false);
    }

    @Override
    public boolean isFinished() {
        return false;
        //return _trying && !proc(mod).isActive();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof InteractWithBlockTask task) {
            if ((task.direction == null) != (direction == null)) return false;
            if (task.direction != null && !task.direction.equals(direction)) return false;
            if ((task.toUse == null) != (toUse == null)) return false;
            if (task.toUse != null && !task.toUse.equals(toUse)) return false;
            if (!task.target.equals(target)) return false;
            if (!task.interactInput.equals(interactInput)) return false;
            return task.walkInto == walkInto;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Interact using " + toUse + " at " + target + " dir " + direction;
    }

    public ClickResponse getClickStatus() {
        return cachedClickStatus;
    }

    /**
     * Direct API interaction — no raycast gating, no crosshairTarget dependency.
     * Uses distance check + constructed BlockHitResult, matching Phase 52-53 Baritone pattern.
     */
    private ClickResponse rightClick(AltoClef mod) {

        // Don't interact if baritone can't interact.
        if (mod.getExtraBaritoneSettings().isInteractionPaused() || mod.getFoodChain().needsToEat() ||
                mod.getPlayer().isBlocking())
            return ClickResponse.WAIT_FOR_CLICK;

        // We can't interact while a screen is open.
        if (!StorageHelper.isPlayerInventoryOpen()) {
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (!cursorStack.isEmpty()) {
                Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
                if (moveTo.isPresent()) {
                    mod.getSlotHandler().clickSlot(moveTo.get(), 0, SlotActionType.PICKUP);
                    return ClickResponse.WAIT_FOR_CLICK;
                }
                if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                    return ClickResponse.WAIT_FOR_CLICK;
                }
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                // Try throwing away cursor slot if it's garbage
                if (garbage.isPresent()) {
                    mod.getSlotHandler().clickSlot(garbage.get(), 0, SlotActionType.PICKUP);
                    return ClickResponse.WAIT_FOR_CLICK;
                }
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                return ClickResponse.WAIT_FOR_CLICK;
            } else {
                StorageHelper.closeScreen();
                return ClickResponse.WAIT_FOR_CLICK;
            }
        }

        // Debounce: don't fire interactions every tick
        if (interactCooldownTicks > 0) {
            interactCooldownTicks--;
            return ClickResponse.CLICK_ATTEMPTED;
        }

        // Don't interact if a screen/GUI is already open (crafting, bed, chest, etc.)
        if (MinecraftClient.getInstance().currentScreen != null) {
            return ClickResponse.CLICK_ATTEMPTED;
        }

        // Distance-based reach check (no raycast — matches Movement.prepared() pattern)
        if (BlockInteraction.isInReach(mod, target)) {
            if (toUse != null) {
                mod.getSlotHandler().forceEquipItem(toUse, false);
            } else {
                mod.getSlotHandler().forceDeequipRightClickableItem();
            }

            // Cosmetic look-at (not a gate)
            LookHelper.lookAt(mod, target);

            // Construct BlockHitResult directly — no crosshairTarget dependency
            Direction face = (direction != null) ? direction : BlockInteraction.computeFace(mod, target);
            BlockHitResult bhr = BlockInteraction.createHitResult(target, face);

            if (interactInput == Input.CLICK_RIGHT) {
                MinecraftClient.getInstance().interactionManager.interactBlock(
                        mod.getPlayer(), Hand.MAIN_HAND, bhr);
            } else if (interactInput == Input.CLICK_LEFT) {
                MinecraftClient.getInstance().interactionManager.attackBlock(target, face);
            }
            if (shiftClick) {
                DirectInput.setSneaking(true);
            }
            interactCooldownTicks = 5; // ~250ms cooldown, matches vanilla right-click rate
            return ClickResponse.CLICK_ATTEMPTED;
        }

        if (shiftClick) {
            DirectInput.setSneaking(false);
        }
        return ClickResponse.CANT_REACH;
    }

    public enum ClickResponse {
        CANT_REACH,
        WAIT_FOR_CLICK,
        CLICK_ATTEMPTED
    }
}
