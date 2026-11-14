package dev.projectileaura;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SmartNuker for 1.21.11, using Mojang mappings.
 * Air exposure AND an unobstructed player-eye raycast are mandatory in every mode.
 *
 * Legit uses Minecraft's real mining lifecycle rather than manually forging
 * key states, which would interfere with user input and other modules.
 */
public final class SmartNukerModule extends Module {
    public enum MiningMode { Packet, Legit, Hybrid }
    public enum TargetOrder { Priority_First, Closest_First, Fastest_First }

    private static final int SCAN_PER_TICK = 220;
    private static final int MAX_RAYCAST_CANDIDATES = 32;
    private static final int CONFIRM_TICKS = 35;
    private static final int FAILURE_COOLDOWN = 60;
    private static final int PACKET_PENALTY_TICKS = 200;
    private static final int MAX_PENDING = 32;
    private static final double[] FACE_OFFSETS = { 0.0, -0.30, 0.30 };

    private final SettingGroup general = settings.getDefaultGroup();
    private final SettingGroup range = settings.createGroup("Range");
    private final SettingGroup blocks = settings.createGroup("Blocks");
    private final SettingGroup targeting = settings.createGroup("Targeting");
    private final SettingGroup mining = settings.createGroup("Mining");
    private final SettingGroup rotation = settings.createGroup("Rotation");
    private final SettingGroup safety = settings.createGroup("Safety");
    private final SettingGroup render = settings.createGroup("Render");

    private final Setting<MiningMode> mode = general.add(new EnumSetting.Builder<MiningMode>()
        .name("mining-mode").description("Packet, normal client mining, or adaptive hybrid mining.")
        .defaultValue(MiningMode.Hybrid).build());
    private final Setting<Integer> radius = range.add(new IntSetting.Builder()
        .name("horizontal-radius").description("Radius of the horizontal scanning circle.")
        .defaultValue(5).min(1).max(8).sliderRange(1, 8).build());
    private final Setting<Integer> vertical = range.add(new IntSetting.Builder()
        .name("vertical-range").description("Blocks above and below the player's block position.")
        .defaultValue(3).min(1).max(8).sliderRange(1, 8).build());
    private final Setting<Double> reach = range.add(new DoubleSetting.Builder()
        .name("block-reach").description("Maximum eye-to-hit distance, also capped by vanilla/server reach.")
        .defaultValue(4.5).min(1).max(6).sliderRange(1, 6).decimalPlaces(2).build());

    private final Setting<List<Block>> allowedBlocks = blocks.add(new BlockListSetting.Builder()
        .name("allowed-blocks").description("Mine only these blocks. Empty means all breakable blocks.")
        .build());
    private final Setting<List<Block>> priorityBlocks = blocks.add(new BlockListSetting.Builder()
        .name("priority-blocks").description("Selected blocks are prioritized in the listed order.")
        .build());
    private final Setting<TargetOrder> order = targeting.add(new EnumSetting.Builder<TargetOrder>()
        .name("target-order").description("Primary target selection strategy.")
        .defaultValue(TargetOrder.Priority_First).build());
    private final Setting<Boolean> persistence = targeting.add(new BoolSetting.Builder()
        .name("target-persistence").description("Finish the current target instead of changing unnecessarily.")
        .defaultValue(true).build());

    private final Setting<Boolean> autoTool = mining.add(new BoolSetting.Builder()
        .name("auto-tool").description("Select the fastest valid hotbar tool for the target.")
        .defaultValue(true).build());
    private final Setting<Integer> miningDelay = mining.add(new IntSetting.Builder()
        .name("mining-delay-ticks").description("Minimum ticks between target starts.")
        .defaultValue(0).min(0).sliderRange(0, 20).build());
    private final Setting<Integer> maxPacketTargets = mining.add(new IntSetting.Builder()
        .name("max-packet-targets").description("Maximum instant-break packet targets per tick; slower targets are sequential.")
        .defaultValue(1).min(1).max(8).sliderRange(1, 8)
        .visible(() -> mode.get() != MiningMode.Legit).build());

    private final Setting<Boolean> smoothRotation = rotation.add(new BoolSetting.Builder()
        .name("smooth-rotation").description("Limit the turn speed when aligning with the mining face.")
        .defaultValue(true).build());
    private final Setting<Double> rotationSpeed = rotation.add(new DoubleSetting.Builder()
        .name("rotation-speed").description("Maximum degrees to turn each tick.")
        .defaultValue(20).min(0.1).sliderRange(1, 180).decimalPlaces(1)
        .visible(smoothRotation::get).build());

    // Air exposure and player sight are deliberately not toggles.
    private final Setting<Boolean> liquidProtection = safety.add(new BoolSetting.Builder()
        .name("liquid-protection").description("Skip blocks touching water or lava.")
        .defaultValue(true).build());
    private final Setting<Boolean> durabilityProtection = safety.add(new BoolSetting.Builder()
        .name("durability-protection").description("Avoid using tools with three or fewer durability remaining.")
        .defaultValue(false).build());
    private final Setting<Boolean> pauseGuis = safety.add(new BoolSetting.Builder()
        .name("pause-in-guis").description("Stop mining while a GUI is open.")
        .defaultValue(true).build());
    private final Setting<Boolean> pauseUsing = safety.add(new BoolSetting.Builder()
        .name("pause-using-items").description("Stop mining while using food or another item.")
        .defaultValue(true).build());
    private final Setting<Boolean> debug = safety.add(new BoolSetting.Builder()
        .name("debug").description("Show additional information in the module status text.")
        .defaultValue(false).build());

    private final Setting<Boolean> targetHighlight = render.add(new BoolSetting.Builder()
        .name("target-highlight").description("Draw a box around the selected block.")
        .defaultValue(true).build());
    private final Setting<SettingColor> sideColor = render.add(new ColorSetting.Builder()
        .name("side-color").defaultValue(new SettingColor(50, 180, 255, 50)).build());
    private final Setting<SettingColor> lineColor = render.add(new ColorSetting.Builder()
        .name("line-color").defaultValue(new SettingColor(50, 180, 255, 230)).build());

    private record Offset(int x, int y, int z) {}
    private record Aim(Direction face, Vec3 point, double distanceSq) {}
    private record Candidate(BlockPos pos, BlockState state, Aim aim, int preferredSlot, double speed) {}
    private record Pending(Block block, int timeout) {}

    private static final class Active {
        final BlockPos pos;
        final Block originalBlock;
        final MiningMode miningMode;
        Aim aim;
        int startTick;
        boolean started;
        boolean awaitingConfirmation;
        boolean finishedPacket;
        float estimatedProgress;
        Active(Candidate candidate, MiningMode miningMode, int tick) {
            this.pos = candidate.pos();
            this.originalBlock = candidate.state().getBlock();
            this.aim = candidate.aim();
            this.miningMode = miningMode;
            this.startTick = tick;
        }
    }

    private final List<Offset> scanOffsets = new ArrayList<>();
    private final Set<BlockPos> candidates = new HashSet<>();
    private final Map<BlockPos, Integer> failures = new HashMap<>();
    private final Map<BlockPos, Pending> pending = new HashMap<>();
    private BlockPos scanOrigin;
    private int scanRadius = -1, scanHeight = -1, scanCursor;
    private int tick, nextStartTick, packetPenaltyUntil, successCount, failureCount;
    private Active active;

    public SmartNukerModule() {
        super(ProjectileAuraAddon.CATEGORY, "smart-nuker",
            "Mines visible, air-exposed blocks inside a circular area with strict reach checks.");
    }

    @Override public void onActivate() {
        clearMining();
        candidates.clear();
        scanOffsets.clear();
        scanOrigin = null;
        scanRadius = -1;
        scanHeight = -1;
        scanCursor = 0;
        pending.clear();
        failures.clear();
        tick = 0;
        nextStartTick = 0;
        packetPenaltyUntil = 0;
        successCount = 0;
        failureCount = 0;
    }

    @Override public void onDeactivate() {
        clearMining();
        candidates.clear();
        pending.clear();
        failures.clear();
        scanOrigin = null;
    }

    @EventHandler private void onRender(Render3DEvent event) {
        if (targetHighlight.get() && active != null)
            event.renderer.box(active.pos, sideColor.get(), lineColor.get(), ShapeMode.Both, 0);
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.player.isDeadOrDying()) {
            clearMining();
            candidates.clear();
            pending.clear();
            scanOrigin = null;
            return;
        }
        tick++;
        updatePending();
        failures.entrySet().removeIf(e -> tick >= e.getValue());

        if ((pauseGuis.get() && mc.screen != null) || (pauseUsing.get() && mc.player.isUsingItem())) {
            clearMining();
            return;
        }

        scanIncrementally();

        if (active != null) {
            if (!persistence.get() && !active.awaitingConfirmation && tick % 5 == 0) {
                Candidate better = findBestCandidate();
                if (better != null && !better.pos().equals(active.pos)
                    && priorityRank(better.state().getBlock()) < priorityRank(active.originalBlock))
                    stopActive(false);
            }
            if (active != null) updateActive();
            if (active != null) return;
        }
        if (tick < nextStartTick) return;

        // A slow target owns the mining lifecycle; only instant packet
        // targets can be processed in a batch in the same tick.
        int budget = mode.get() == MiningMode.Legit ? 1 : maxPacketTargets.get();
        for (int n = 0; n < budget && active == null && pending.size() < MAX_PENDING; n++) {
            Candidate candidate = findBestCandidate();
            if (candidate == null) break;
            MiningMode selectedMode = mode.get();
            if (selectedMode == MiningMode.Hybrid) {
                selectedMode = tick >= packetPenaltyUntil && candidate.speed() >= 1.0
                    ? MiningMode.Packet : MiningMode.Legit;
            }
            active = new Active(candidate, selectedMode, tick);
            updateActive();
            if (active == null && miningDelay.get() > 0) break;
        }
    }

    private void rebuildOffsets() {
        scanOffsets.clear();
        int r = radius.get(), v = vertical.get();
        for (int dy = -v; dy <= v; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz <= r * r) scanOffsets.add(new Offset(dx, dy, dz));
                }
            }
        }
        scanOffsets.sort(Comparator.comparingInt(a -> a.x() * a.x() + a.y() * a.y() + a.z() * a.z()));
        scanCursor = 0;
        candidates.clear();
        scanRadius = r;
        scanHeight = v;
    }

    private void scanIncrementally() {
        BlockPos here = mc.player.blockPosition();
        if (scanRadius != radius.get() || scanHeight != vertical.get()) rebuildOffsets();
        if (!here.equals(scanOrigin)) {
            scanOrigin = here.immutable();
            scanCursor = 0;
            candidates.clear();
        }
        for (int i = 0; i < SCAN_PER_TICK && !scanOffsets.isEmpty(); i++) {
            if (scanCursor >= scanOffsets.size()) scanCursor = 0;
            Offset offset = scanOffsets.get(scanCursor++);
            BlockPos pos = scanOrigin.offset(offset.x(), offset.y(), offset.z());
            BlockState state = mc.level.getBlockState(pos);
            if (precheck(pos, state)) candidates.add(pos);
            else candidates.remove(pos);
        }
    }

    private boolean precheck(BlockPos pos, BlockState state) {
        if (state.isAir() || state.getBlock() == Blocks.BEDROCK || state.getBlock() == Blocks.BARRIER)
            return false;
        if (state.getDestroySpeed(mc.level, pos) < 0 || !state.getFluidState().isEmpty()) return false;
        if (state.getShape(mc.level, pos).isEmpty()) return false;
        if (!allowedBlocks.get().isEmpty() && !allowedBlocks.get().contains(state.getBlock())) return false;
        if (!insideCylinder(pos)) return false;
        if (!hasAirFace(pos)) return false;  // Mandatory six-side AIR test.
        if (liquidProtection.get()) {
            for (Direction dir : Direction.values()) {
                if (!mc.level.getBlockState(pos.relative(dir)).getFluidState().isEmpty()) return false;
            }
        }
        return true;
    }

    private boolean insideCylinder(BlockPos pos) {
        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();
        return dx * dx + dz * dz <= (double) radius.get() * radius.get()
            && Math.abs(pos.getY() - mc.player.blockPosition().getY()) <= vertical.get();
    }

    private boolean hasAirFace(BlockPos pos) {
        for (Direction face : Direction.values()) {
            if (mc.level.getBlockState(pos.relative(face)).isAir()) return true;
        }
        return false;
    }

    private double effectiveReach() {
        return Math.min(reach.get(), mc.player.blockInteractionRange());
    }

    private Aim visibleFace(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        double maxReachSq = effectiveReach() * effectiveReach();
        Aim best = null;
        for (Direction face : Direction.values()) {
            if (!mc.level.getBlockState(pos.relative(face)).isAir()) continue;
            Vec3 faceCenter = Vec3.atCenterOf(pos).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            Vec3 towardEye = eye.subtract(faceCenter);
            if (towardEye.x * face.getStepX() + towardEye.y * face.getStepY()
                + towardEye.z * face.getStepZ() < -0.001) continue;
            for (double u : FACE_OFFSETS) for (double v : FACE_OFFSETS) {
                double x = pos.getX() + 0.5;
                double y = pos.getY() + 0.5;
                double z = pos.getZ() + 0.5;
                switch (face) {
                    case UP, DOWN -> { x += u; z += v; y += face == Direction.UP ? 0.501 : -0.501; }
                    case EAST, WEST -> { y += u; z += v; x += face == Direction.EAST ? 0.501 : -0.501; }
                    case NORTH, SOUTH -> { x += u; y += v; z += face == Direction.SOUTH ? 0.501 : -0.501; }
                }
                Vec3 point = new Vec3(x, y, z);
                if (eye.distanceToSqr(point) > maxReachSq) continue;
                BlockHitResult hit = mc.level.clip(new ClipContext(eye, point,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)
                    || hit.getDirection() != face) continue;
                double distanceSq = eye.distanceToSqr(hit.getLocation());
                if (distanceSq > maxReachSq) continue;
                if (best == null || distanceSq < best.distanceSq()) best = new Aim(face, point, distanceSq);
            }
        }
        return best;
    }

    private int bestToolSlot(BlockState state) {
        int selected = mc.player.getInventory().getSelectedSlot();
        if (!autoTool.get()) return suitable(mc.player.getInventory().getItem(selected)) ? selected : -1;
        int best = -1;
        float score = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!suitable(stack)) continue;
            float current = stack.getDestroySpeed(state);
            if (stack.isCorrectToolForDrops(state)) current += 0.01f;
            if (current > score) { score = current; best = i; }
        }
        return best;
    }

    private boolean suitable(ItemStack stack) {
        return !durabilityProtection.get() || !stack.isDamageableItem()
            || stack.getMaxDamage() - stack.getDamageValue() > 3;
    }

    private double estimatedSpeed(BlockState state, int slot, BlockPos pos) {
        float hardness = state.getDestroySpeed(mc.level, pos);
        if (hardness <= 0) return hardness == 0 ? 1 : 0;
        ItemStack tool = mc.player.getInventory().getItem(slot);
        double harvestMultiplier = tool.isCorrectToolForDrops(state) ? 30.0 : 100.0;
        return tool.getDestroySpeed(state) / hardness / harvestMultiplier;
    }

    private int priorityRank(Block block) {
        int i = priorityBlocks.get().indexOf(block);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private Comparator<Candidate> comparator() {
        Comparator<Candidate> priority = Comparator.comparingInt(c -> priorityRank(c.state().getBlock()));
        Comparator<Candidate> distance = Comparator.comparingDouble(c -> c.aim().distanceSq());
        Comparator<Candidate> fastest = Comparator.comparingDouble((Candidate c) -> -c.speed());
        Comparator<Candidate> tie = Comparator.comparingLong(c -> c.pos().asLong());
        return switch (order.get()) {
            case Priority_First -> priority.thenComparing(distance).thenComparing(fastest).thenComparing(tie);
            case Closest_First -> distance.thenComparing(priority).thenComparing(fastest).thenComparing(tie);
            case Fastest_First -> fastest.thenComparing(priority).thenComparing(distance).thenComparing(tie);
        };
    }

    private Candidate findBestCandidate() {
        // Cheap checks before raycasting, which is the expensive phase.
        record Preliminary(BlockPos pos, BlockState state, int slot, double speed, double distanceSq) {}
        List<Preliminary> preliminary = new ArrayList<>();
        Vec3 eye = mc.player.getEyePosition();
        double reachSq = effectiveReach() * effectiveReach();
        Iterator<BlockPos> it = candidates.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            BlockState state = mc.level.getBlockState(pos);
            if (!precheck(pos, state)) { it.remove(); continue; }
            if (failures.getOrDefault(pos, 0) > tick || pending.containsKey(pos)) continue;
            if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > (effectiveReach() + 0.9) * (effectiveReach() + 0.9)) continue;
            int slot = bestToolSlot(state);
            if (slot < 0) continue;
            double delta = estimatedSpeed(state, slot, pos);
            if (delta <= 0) continue;
            preliminary.add(new Preliminary(pos, state, slot, delta,
                eye.distanceToSqr(Vec3.atCenterOf(pos))));
        }
        Comparator<Preliminary> byPriority = Comparator.comparingInt(p -> priorityRank(p.state().getBlock()));
        Comparator<Preliminary> byDistance = Comparator.comparingDouble(Preliminary::distanceSq);
        Comparator<Preliminary> bySpeed = Comparator.comparingDouble((Preliminary p) -> -p.speed());
        Comparator<Preliminary> rough = switch (order.get()) {
            case Priority_First -> byPriority.thenComparing(byDistance).thenComparing(bySpeed);
            case Closest_First -> byDistance.thenComparing(byPriority).thenComparing(bySpeed);
            case Fastest_First -> bySpeed.thenComparing(byPriority).thenComparing(byDistance);
        };
        preliminary.sort(rough);
        Candidate best = null;
        for (int i = 0; i < Math.min(MAX_RAYCAST_CANDIDATES, preliminary.size()); i++) {
            Preliminary p = preliminary.get(i);
            Aim aim = visibleFace(p.pos());
            if (aim == null || aim.distanceSq() > reachSq) continue;
            Candidate found = new Candidate(p.pos(), p.state(), aim, p.slot(), p.speed());
            if (best == null || comparator().compare(found, best) < 0) best = found;
        }
        return best;
    }

    private void updateActive() {
        Active a = active;
        if (a == null) return;
        BlockState state = mc.level.getBlockState(a.pos);
        if (state.getBlock() != a.originalBlock) {
            successCount++;
            stopActive(false);
            return;
        }
        if (a.awaitingConfirmation) {
            if (tick - a.startTick > CONFIRM_TICKS) stopActive(true);
            return;
        }
        if (!precheck(a.pos, state)) { stopActive(false); return; }
        Aim latest = visibleFace(a.pos);
        if (latest == null) { stopActive(false); return; }
        a.aim = latest;
        if (!facePlayer(latest)) return;
        if (!pointedAtTarget(a)) return;

        if (!a.started) {
            if (autoTool.get()) {
                int slot = bestToolSlot(state);
                if (slot < 0) { stopActive(false); return; }
                InvUtils.swap(slot, false);
            } else if (!suitable(mc.player.getMainHandItem())) {
                stopActive(false);
                return;
            }
            a.started = true;
            a.startTick = tick;
            if (a.miningMode == MiningMode.Packet) {
                packetAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, a.pos, a.aim.face());
                if (state.getDestroyProgress(mc.player, mc.level, a.pos) >= 1) {
                    packetAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, a.pos, a.aim.face());
                    a.finishedPacket = true;
                    pending.put(a.pos, new Pending(a.originalBlock, tick + CONFIRM_TICKS));
                    active = null;
                    nextStartTick = tick + miningDelay.get();
                    return;
                }
            } else {
                mc.gameMode.startDestroyBlock(a.pos, a.aim.face());
                mc.player.swing(InteractionHand.MAIN_HAND);
                return;
            }
        }

        if (a.miningMode == MiningMode.Legit) {
            mc.gameMode.continueDestroyBlock(a.pos, a.aim.face());
            if (tick % 4 == 0) mc.player.swing(InteractionHand.MAIN_HAND);
            if (tick - a.startTick > 240) stopActive(true);
        } else {
            a.estimatedProgress += state.getDestroyProgress(mc.player, mc.level, a.pos);
            if (a.estimatedProgress >= 1) {
                packetAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, a.pos, a.aim.face());
                a.finishedPacket = true;
                a.awaitingConfirmation = true;
                a.startTick = tick;
            } else if (tick - a.startTick > 240) stopActive(true);
        }
    }

    private boolean facePlayer(Aim aim) {
        Vec3 delta = aim.point().subtract(mc.player.getEyePosition());
        double horizontal = Math.hypot(delta.x, delta.z);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        float maxTurn = smoothRotation.get() ? rotationSpeed.get().floatValue() : 180f;
        float yawDelta = Mth.wrapDegrees(desiredYaw - mc.player.getYRot());
        float pitchDelta = desiredPitch - mc.player.getXRot();
        mc.player.setYRot(mc.player.getYRot() + Mth.clamp(yawDelta, -maxTurn, maxTurn));
        mc.player.setXRot(Mth.clamp(mc.player.getXRot()
            + Mth.clamp(pitchDelta, -maxTurn, maxTurn), -90f, 90f));
        return Math.abs(Mth.wrapDegrees(desiredYaw - mc.player.getYRot())) <= 0.8f
            && Math.abs(desiredPitch - mc.player.getXRot()) <= 0.8f;
    }

    private boolean pointedAtTarget(Active a) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getViewVector(1f).scale(effectiveReach()));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(a.pos)
            && hit.getDirection() == a.aim.face()
            && eye.distanceToSqr(hit.getLocation()) <= effectiveReach() * effectiveReach();
    }

    private void packetAction(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction face) {
        if (mc.getConnection() != null) {
            // Matching Meteor's official Mojang-mapped packet prediction path.
            mc.gameMode.startPrediction(mc.level, sequence ->
                new ServerboundPlayerActionPacket(action, pos, face, sequence));
        }
    }

    private void updatePending() {
        Iterator<Map.Entry<BlockPos, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Pending> e = it.next();
            if (mc.level.getBlockState(e.getKey()).getBlock() != e.getValue().block()) {
                successCount++;
                it.remove();
            } else if (tick >= e.getValue().timeout()) {
                failureCount++;
                failures.put(e.getKey(), tick + FAILURE_COOLDOWN);
                if (mode.get() == MiningMode.Hybrid) packetPenaltyUntil = tick + PACKET_PENALTY_TICKS;
                it.remove();
            }
        }
    }

    private void stopActive(boolean failed) {
        if (active != null) {
            if (mc.gameMode != null && active.started && active.miningMode == MiningMode.Legit)
                mc.gameMode.stopDestroyBlock();
            if (active.started && active.miningMode == MiningMode.Packet && !active.finishedPacket && mc.getConnection() != null)
                packetAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, active.pos, active.aim.face());
            if (failed) {
                failureCount++;
                failures.put(active.pos, tick + FAILURE_COOLDOWN);
                if (mode.get() == MiningMode.Hybrid && active.miningMode == MiningMode.Packet)
                    packetPenaltyUntil = tick + PACKET_PENALTY_TICKS;
            }
            active = null;
            nextStartTick = tick + miningDelay.get();
        }
    }

    private void clearMining() { stopActive(false); }

    @Override public String getInfoString() {
        if (debug.get()) return String.format("%s | %d success / %d failed | %d cached",
            active == null ? "Scanning" : active.miningMode.name(), successCount, failureCount, candidates.size());
        return active == null ? "Scanning" : active.miningMode.name();
    }
}
