package dev.projectileaura;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Independent visible-block mining module. Uses vanilla mining, not packet bursts. */
public class MiningAuraModule extends Module {
    public enum Filter { All, Whitelist, Blacklist }
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Filter> filter = general.add(new EnumSetting.Builder<Filter>()
        .name("filter").description("Which block types to mine.")
        .defaultValue(Filter.All).build());
    private final Setting<List<Block>> blocks = general.add(new BlockListSetting.Builder()
        .name("blocks").description("Block types for whitelist or blacklist mode.")
        .visible(() -> filter.get() != Filter.All).build());
    private final Setting<Double> range = general.add(new DoubleSetting.Builder()
        .name("range").description("Eye-to-block reach, capped at normal interaction reach.")
        .defaultValue(4.5).min(1).max(6).sliderRange(1, 6).build());
    private final Setting<Integer> fov = general.add(new IntSetting.Builder()
        .name("field-of-view").description("Full angle around the current view; 360 mines in every direction.")
        .defaultValue(360).min(1).max(360).sliderRange(30, 360).build());
    private final Setting<Boolean> autoTool = general.add(new BoolSetting.Builder()
        .name("auto-tool").description("Select the best hotbar tool when starting a block.")
        .defaultValue(true).build());
    private final Setting<Integer> delay = general.add(new IntSetting.Builder()
        .name("delay-ticks").description("Ticks between blocks.")
        .defaultValue(0).min(0).sliderRange(0, 20).build());
    private final Setting<Integer> timeout = general.add(new IntSetting.Builder()
        .name("timeout-ticks").description("Maximum extra ticks to wait beyond estimated mining time.")
        .defaultValue(40).min(10).sliderRange(10, 200).build());

    private record Aim(BlockPos pos, Direction face, Vec3 point, double distance) {}
    private Aim target;
    private BlockState originalState;
    private int ticks, readyAt, age;
    private double progress;
    private Object world;
    private final Map<BlockPos, Integer> retryAfter = new HashMap<>();

    public MiningAuraModule() {
        super(ProjectileAuraAddon.CATEGORY, "mining-aura",
            "Automatically mines visible reachable blocks using vanilla mining mechanics.");
    }

    @Override public void onActivate() { reset(); }
    @Override public void onDeactivate() { stop(); reset(); }

    private void reset() {
        target = null; originalState = null;
        ticks = 0; readyAt = 0; age = 0; progress = 0;
        world = null; retryAfter.clear();
    }

    private void stop() {
        // Meteor cancels its own mining when no break is requested this tick.
        target = null; originalState = null; age = 0; progress = 0;
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.level != world) {
            reset();
            world = mc.level;
        }
        if (mc.player == null || mc.level == null || mc.gameMode == null
            || !mc.player.isAlive() || mc.player.isSpectator() || mc.screen != null) {
            stop();
            return;
        }
        ticks++;
        retryAfter.entrySet().removeIf(e -> e.getValue() <= ticks);
        if (target != null) {
            BlockState state = mc.level.getBlockState(target.pos());
            if (!state.equals(originalState) || !allowed(target.pos(), state)) {
                stop();
                readyAt = ticks + delay.get();
            } else {
                Aim visible = visibleAim(target.pos(), false);
                if (visible == null) {
                    stop();
                } else {
                    target = visible;
                }
            }
        }
        if (ticks < readyAt) return;
        if (target == null) {
            target = findTarget();
            if (target == null) { readyAt = ticks + 4; return; }
            originalState = mc.level.getBlockState(target.pos());
            if (autoTool.get()) selectTool(originalState);
        }

        Vec3 delta = target.point().subtract(mc.player.getEyePosition());
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
        // Recheck the actual view ray before sending a normal mining action.
        Vec3 eye = mc.player.getEyePosition();
        BlockHitResult hit = mc.level.clip(new ClipContext(eye,
            eye.add(mc.player.getViewVector(1f).scale(reach())),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target.pos())) {
            fail();
            return;
        }
        // Keep vanilla input handling from cancelling automatic mining.
        if (!BlockUtils.breakBlock(target.pos(), true)) { fail(); return; }
        age++;
        double step = Math.max(0, originalState.getDestroyProgress(mc.player, mc.level, target.pos()));
        progress += step;
        // Allow genuinely slow blocks to finish; back off if the server never changes the block.
        if ((step <= 0 && age > timeout.get()) ||
            (progress >= 1.0 && age > Math.ceil(1.0 / Math.max(step, 0.000001)) + timeout.get())
            || age > 12000) fail();
    }

    private void fail() {
        if (target != null) retryAfter.put(target.pos(), ticks + 100);
        stop();
        readyAt = ticks + Math.max(1, delay.get());
    }

    private double reach() { return Math.min(range.get(), mc.player.blockInteractionRange()); }

    private boolean allowed(BlockPos pos, BlockState state) {
        if (state.isAir() || state.getDestroySpeed(mc.level, pos) < 0
            || !state.getFluidState().isEmpty() || retryAfter.containsKey(pos)) return false;
        return switch (filter.get()) {
            case All -> true;
            case Whitelist -> blocks.get().contains(state.getBlock());
            case Blacklist -> !blocks.get().contains(state.getBlock());
        };
    }

    private Aim findTarget() {
        Vec3 eye = mc.player.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        int radius = (int) Math.ceil(reach()) + 1;
        Aim best = null;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
            center.offset(radius, radius, radius))) {
            // Cheap bounding-box distance test before any raycasts.
            double dx = Math.max(0, Math.max(p.getX() - eye.x, eye.x - p.getX() - 1));
            double dy = Math.max(0, Math.max(p.getY() - eye.y, eye.y - p.getY() - 1));
            double dz = Math.max(0, Math.max(p.getZ() - eye.z, eye.z - p.getZ() - 1));
            double lower = dx * dx + dy * dy + dz * dz;
            if (lower > reach() * reach() || (best != null && lower >= best.distance())) continue;
            if (!allowed(p, mc.level.getBlockState(p))) continue;
            Aim candidate = visibleAim(p.immutable(), true);
            if (candidate != null && (best == null || candidate.distance() < best.distance())) best = candidate;
        }
        return best;
    }

    private Aim visibleAim(BlockPos pos, boolean checkFov) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 view = mc.player.getViewVector(1f);
        double max = reach() * reach();
        double cos = Math.cos(Math.toRadians(fov.get() / 2.0));
        Aim best = null;
        // Sample the center and inner corners of all six faces. The actual first
        // raycast hit decides visibility, including non-full-cube block shapes.
        double[] offsets = {0.25, 0.5, 0.75};
        for (Direction face : Direction.values()) {
            for (double u : offsets) for (double v : offsets) {
                double x = u, y = v, z = 0.5;
                switch (face) {
                    case DOWN -> { x = u; y = 0.01; z = v; }
                    case UP -> { x = u; y = 0.99; z = v; }
                    case NORTH -> { x = u; y = v; z = 0.01; }
                    case SOUTH -> { x = u; y = v; z = 0.99; }
                    case WEST -> { x = 0.01; y = u; z = v; }
                    case EAST -> { x = 0.99; y = u; z = v; }
                }
                Vec3 point = new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                Vec3 direction = point.subtract(eye);
                if (direction.lengthSqr() < 1e-8) continue;
                if (checkFov && fov.get() < 360 && view.dot(direction.normalize()) < cos) continue;
                BlockHitResult hit = mc.level.clip(new ClipContext(eye, point,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) continue;
                double distance = eye.distanceToSqr(hit.getLocation());
                if (distance <= max && (best == null || distance < best.distance()))
                    best = new Aim(pos, hit.getDirection(), point, distance);
            }
        }
        return best;
    }

    private void selectTool(BlockState state) {
        int best = mc.player.getInventory().getSelectedSlot();
        double bestScore = toolScore(mc.player.getInventory().getItem(best), state);
        for (int i = 0; i < 9; i++) {
            double score = toolScore(mc.player.getInventory().getItem(i), state);
            if (score > bestScore) { best = i; bestScore = score; }
        }
        if (best != mc.player.getInventory().getSelectedSlot()) InvUtils.swap(best, false);
    }

    private double toolScore(ItemStack stack, BlockState state) {
        return stack.getDestroySpeed(state) * (state.requiresCorrectToolForDrops()
            && !stack.isCorrectToolForDrops(state) ? 0.3 : 1);
    }
}
