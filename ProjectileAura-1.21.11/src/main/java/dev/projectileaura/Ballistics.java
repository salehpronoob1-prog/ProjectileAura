package dev.projectileaura;

import net.minecraft.world.phys.Vec3;

/**
 * Stable low-arc projectile intercept using fractional flight time.
 * Physics: move by velocity; then apply gravity and drag each tick.
 */
public final class Ballistics {
    private static final double EPS = 1e-7;
    private static final double STEP = 0.25;
    private static final double MAX_TICKS = 120.0;

    private Ballistics() {}

    public static Vec3 solve(Vec3 origin, Vec3 target, Vec3 targetMotion,
                             double speed, double gravity, double drag) {
        if (origin == null || target == null || targetMotion == null ||
            !Double.isFinite(speed) || speed <= 0 ||
            !Double.isFinite(gravity) || !Double.isFinite(drag) ||
            drag <= 0 || drag > 2.0 || origin.distanceToSqr(target) < EPS) return null;

        double previousTime = 1.0;
        double previousError = requiredSpeed(previousTime, origin, target, targetMotion, gravity, drag) - speed;
        if (Double.isFinite(previousError) && previousError <= 0)
            return direction(previousTime, origin, target, targetMotion, gravity, drag);

        for (double t = 1.0 + STEP; t <= MAX_TICKS + EPS; t += STEP) {
            double error = requiredSpeed(t, origin, target, targetMotion, gravity, drag) - speed;
            if (!Double.isFinite(error)) continue;
            // Use the FIRST positive-to-negative crossing: the flatter, low arc.
            if (Double.isFinite(previousError) && previousError > 0 && error <= 0) {
                double low = previousTime, high = t;
                for (int i = 0; i < 28; i++) {
                    double mid = (low + high) * 0.5;
                    double midError = requiredSpeed(mid, origin, target, targetMotion, gravity, drag) - speed;
                    if (!Double.isFinite(midError) || midError > 0) low = mid;
                    else high = mid;
                }
                return direction((low + high) * 0.5, origin, target, targetMotion, gravity, drag);
            }
            previousTime = t;
            previousError = error;
        }
        // Out of range: do not snap to a poor high-arc guess.
        return null;
    }

    private static double requiredSpeed(double t, Vec3 origin, Vec3 target,
                                        Vec3 motion, double gravity, double drag) {
        Vec3 d = corrected(t, origin, target, motion, gravity, drag);
        double sum = travelFactor(drag, t);
        if (d == null || !Double.isFinite(sum) || sum <= EPS) return Double.POSITIVE_INFINITY;
        return d.length() / sum;
    }

    private static Vec3 direction(double t, Vec3 origin, Vec3 target,
                                  Vec3 motion, double gravity, double drag) {
        Vec3 d = corrected(t, origin, target, motion, gravity, drag);
        if (d == null || d.lengthSqr() <= EPS) return null;
        Vec3 out = d.normalize();
        return finite(out) ? out : null;
    }

    private static Vec3 corrected(double t, Vec3 origin, Vec3 target,
                                  Vec3 motion, double gravity, double drag) {
        if (!Double.isFinite(t) || t < 1.0) return null;
        double sum = travelFactor(drag, t);
        if (!Double.isFinite(sum) || sum <= EPS) return null;
        double drop = Math.abs(drag - 1.0) < 1e-7
            ? -gravity * t * (t - 1.0) * 0.5
            : -gravity * drag * (t - sum) / (1.0 - drag);
        if (!Double.isFinite(drop)) return null;
        Vec3 d = target.add(motion.scale(t)).subtract(origin).subtract(0, drop, 0);
        return finite(d) ? d : null;
    }

    private static double travelFactor(double drag, double t) {
        if (Math.abs(drag - 1.0) < 1e-7) return t;
        double x = Math.log(drag);
        return Math.expm1(t * x) / Math.expm1(x);
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
