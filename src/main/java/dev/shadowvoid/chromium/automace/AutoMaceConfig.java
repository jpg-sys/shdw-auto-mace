package dev.shadowvoid.chromium.automace;

/**
 * Runtime defaults for the standalone draft. Chromium's module/config system can
 * expose these fields directly later.
 */
public record AutoMaceConfig(
        double triggerRange,
        double minimumSmashFallDistance,
        double minimumDownwardVelocity,
        int minimumDescendingTicks,
        float minimumAttackCooldown,
        int restoreSlotAfterTicks,
        int globalCooldownTicks,
        boolean stopWhenScreenOpen,
        boolean stopWhileUsingItem,
        boolean ignoreCreativePlayers,
        boolean ignoreTeammates,
        boolean requireClearPath,
        ShieldTrigger shieldTrigger
) {
    public static AutoMaceConfig defaults() {
        return new AutoMaceConfig(
                1.5D,
                1.51D,
                -0.08D,
                1,
                0.90F,
                1,
                3,
                true,
                true,
                true,
                true,
                true,
                ShieldTrigger.EQUIPPED
        );
    }

    public enum ShieldTrigger {
        /** Axe -> mace whenever a shield is in either hand. */
        EQUIPPED,

        /** Axe -> mace only while the remote player is actively blocking. */
        RAISED_ONLY
    }
}
