package io.freemytv;

/** Damped spring, like macOS animations: response (s) and damping (1 = no bounce). */
final class Spring {
    float value, velocity, target;
    private final float response, damping;

    Spring(float response, float damping, float initial) {
        this.response = response;
        this.damping = damping;
        value = target = initial;
    }

    void snap(float v) {
        value = target = v;
        velocity = 0;
    }

    /** Advances `dt` seconds; returns true while still moving. */
    boolean step(float dt) {
        if (settled()) {
            value = target;
            velocity = 0;
            return false;
        }
        float omega = (float) (2 * Math.PI / response);
        float stiffness = omega * omega, friction = 2 * damping * omega;
        int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float accel = -stiffness * (value - target) - friction * velocity;
            velocity += accel * h;
            value += velocity * h;
        }
        return true;
    }

    boolean settled() {
        // Past ~512 (pixels), a step can be under half a float's ulp: the value freezes a few ulps from the target and
        // would never settle, so the tolerance grows with the target's ulp (unchanged for unit-scale springs).
        float tol = Math.max(0.0008f, Math.ulp(target) * 64f);
        return Math.abs(value - target) < tol && Math.abs(velocity) < Math.max(0.004f, tol * 20f);
    }
}
