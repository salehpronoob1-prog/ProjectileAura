package dev.projectileaura;

import net.minecraft.world.phys.Vec3;

/** Numerical iterative intercept solver, with Minecraft-like discrete gravity and drag. */
public final class Ballistics {
    private Ballistics() {}

    public static Vec3 solve(Vec3 origin, Vec3 target, Vec3 targetVelocity, double speed, double gravity, double drag) {
        if (speed <= 0) return null;
        double t = Math.max(1, target.distanceTo(origin) / speed);
        Vec3 direction = target.subtract(origin).normalize();
        double bestError = Double.POSITIVE_INFINITY;
        Vec3 best = null;
        // Search flight times; compensate drop and drag using a discrete simulation.
        for (int ticks = 1; ticks <= 100; ticks++) {
            double decay = Math.pow(drag, ticks);
            double sum = Math.abs(1 - drag) < 1e-9 ? ticks : (1 - decay) / (1 - drag);
            double verticalDrop = 0;
            double vy = 0;
            for (int i = 0; i < ticks; i++) { verticalDrop += vy; vy = (vy - gravity) * drag; }
            Vec3 future = target.add(targetVelocity.scale(ticks));
            Vec3 delta = future.subtract(origin).subtract(0, verticalDrop, 0);
            double needed = delta.length() / sum;
            double error = Math.abs(needed - speed);
            if (error < bestError) { bestError = error; best = delta.normalize(); }
        }
        return bestError < speed * 0.20 ? best : null;
    }
}
