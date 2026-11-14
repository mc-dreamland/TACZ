package com.tacz.guns.client.paper;

/** Separates visible flight from the grace period for authoritative corrections. */
final class PaperVisualLifetime {
    private static final int CORRECTION_GRACE_TICKS = 2;
    private static final int IMPACT_GRACE_TICKS = 4;

    final int life;
    private long visibleUntil;
    private long expiresAt;
    private long impactAt = -1;
    private boolean renderedImpact;
    private boolean ended;

    PaperVisualLifetime(int life, int age, long now) {
        this.life = life;
        synchronizeAge(age, now);
    }

    void synchronizeAge(int age, long now) {
        visibleUntil = now + life - age;
        expiresAt = visibleUntil + CORRECTION_GRACE_TICKS;
    }

    boolean hasImpact() {
        return impactAt >= 0;
    }

    /** Only a newly confirmed hit may produce one final rendered segment. */
    boolean end(String reason, long now) {
        if (!"hit".equals(reason)) {
            ended = true;
            return false;
        }
        if (ended || hasImpact()) return false;
        impactAt = now;
        return true;
    }

    boolean isExpired(long now) {
        return ended || now > expiresAt
                || hasImpact() && (renderedImpact || now - impactAt > IMPACT_GRACE_TICKS);
    }

    boolean canRender(long now, int predictedAge) {
        return !isExpired(now) && (hasImpact() || now < visibleUntil && predictedAge < life);
    }

    void markRendered() {
        if (hasImpact()) renderedImpact = true;
    }
}
