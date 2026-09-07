package dev.shadowvoid.chromium.automace;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Comparator;

/**
 * Client-side automatic mace smash for Minecraft 1.21.11 + Yarn mappings.
 *
 * The camera never has to face the target. A normal look packet is placed before
 * the attack packet and the real camera rotation is restored immediately after
 * the attack. No mixin is required.
 */
public final class AutoMaceModule {
    private final AutoMaceConfig config;

    private boolean enabled;
    private ClientWorld lastWorld;
    private boolean attackedThisFall;
    private int descendingTicks;
    private int cooldownTicks;

    private int restoreSlot = -1;
    private int automaticSlot = -1;
    private int restoreTicks;

    public AutoMaceModule(AutoMaceConfig config, boolean enabledByDefault) {
        this.config = config;
        this.enabled = enabledByDefault;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void tick(MinecraftClient client) {
        if (client.world != lastWorld) {
            resetForWorld(client.world);
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
        tickPendingSlotRestore(client);

        ClientPlayerEntity self = client.player;
        ClientWorld world = client.world;
        if (!enabled || self == null || world == null || client.interactionManager == null) {
            return;
        }

        updateFallEpisode(self);

        if (config.stopWhenScreenOpen() && client.currentScreen != null) {
            return;
        }
        if (config.stopWhileUsingItem() && self.isUsingItem()) {
            return;
        }
        if (!self.isAlive() || self.isSpectator() || self.getAbilities().flying) {
            return;
        }
        if (!isSmashFall(self) || attackedThisFall || cooldownTicks > 0) {
            return;
        }
        PlayerEntity target = chooseTarget(self, world);
        if (target == null) {
            return;
        }

        int maceSlot = findMaceInHotbar(self);
        if (maceSlot < 0) {
            return;
        }

        boolean stunSlam = shouldStunSlam(target);
        int axeSlot = stunSlam ? findAxeInHotbar(self) : -1;
        if (stunSlam && axeSlot < 0) {
            // A raised/equipped shield would simply block the mace, so do not waste
            // the one smash opportunity when the axe half of the combo is impossible.
            return;
        }

        AttackOutcome outcome = attackNow(client, self, target, maceSlot, axeSlot, stunSlam);
        if (outcome.consumesFall()) {
            attackedThisFall = true;
            cooldownTicks = config.globalCooldownTicks();
        }
    }

    private void updateFallEpisode(ClientPlayerEntity self) {
        if (self.isOnGround()) {
            descendingTicks = 0;
            attackedThisFall = false;
            return;
        }

        if (self.getVelocity().y <= config.minimumDownwardVelocity()) {
            descendingTicks++;
        } else {
            descendingTicks = 0;
        }
    }

    private boolean isSmashFall(ClientPlayerEntity self) {
        return !self.isOnGround()
                && self.fallDistance >= config.minimumSmashFallDistance()
                && self.getVelocity().y <= config.minimumDownwardVelocity()
                && descendingTicks >= config.minimumDescendingTicks()
                && !self.isGliding()
                && !self.isClimbing()
                && !self.isInFluid()
                && !self.hasVehicle();
    }

    private PlayerEntity chooseTarget(ClientPlayerEntity self, ClientWorld world) {
        final double triggerRangeSq = config.triggerRange() * config.triggerRange();

        return world.getPlayers().stream()
                .filter(target -> target != self)
                .filter(target -> basicTargetFilter(self, target))
                .filter(target -> self.getBoundingBox().squaredMagnitude(target.getBoundingBox()) <= triggerRangeSq)
                // This uses the current vanilla entity-interaction/attack reach. It
                // respects attribute-modified reach instead of hardcoding 3 blocks.
                .filter(target -> self.canAttackEntityIn(target.getBoundingBox(), 0.0D))
                .map(target -> new Candidate(target, chooseAimPoint(self, target)))
                .filter(candidate -> !config.requireClearPath()
                        || hasClearPath(world, self, candidate.aimPoint()))
                .min(Comparator
                        .comparingDouble((Candidate candidate) ->
                                self.getBoundingBox().squaredMagnitude(candidate.player().getBoundingBox()))
                        .thenComparingDouble(candidate -> rotationCost(self, candidate.aimPoint()))
                        .thenComparingInt(candidate -> candidate.player().getId()))
                .map(Candidate::player)
                .orElse(null);
    }

    private boolean basicTargetFilter(ClientPlayerEntity self, AbstractClientPlayerEntity target) {
        if (!target.isAlive() || target.isRemoved() || target.isSpectator() || !target.canHit()) {
            return false;
        }
        if (config.ignoreCreativePlayers() && target.isCreative()) {
            return false;
        }
        return !config.ignoreTeammates() || !self.isTeammate(target);
    }

    private AttackOutcome attackNow(
            MinecraftClient client,
            ClientPlayerEntity self,
            PlayerEntity target,
            int maceSlot,
            int axeSlot,
            boolean stunSlam
    ) {
        if (!validateAtAttackTime(self, target)) {
            return AttackOutcome.NONE;
        }

        Vec3d aimPoint = chooseAimPoint(self, target);
        if (config.requireClearPath() && !hasClearPath(client.world, self, aimPoint)) {
            return AttackOutcome.NONE;
        }

        Rotation aim = rotationTo(self.getEyePos(), aimPoint);
        float cameraYaw = self.getYaw();
        float cameraPitch = self.getPitch();
        int originalSlot = self.getInventory().getSelectedSlot();

        sendLook(self, aim.yaw(), aim.pitch());

        boolean attackSent = false;

        if (stunSlam) {
            if (!self.getInventory().getStack(axeSlot).isIn(ItemTags.AXES)) {
                sendLook(self, cameraYaw, cameraPitch);
                return AttackOutcome.NONE;
            }
            selectHotbarSlot(self, axeSlot);
            if (!validateAtAttackTime(self, target) || !isAttackReady(self)) {
                sendLook(self, cameraYaw, cameraPitch);
                scheduleSlotRestore(self, originalSlot, axeSlot);
                return AttackOutcome.NONE;
            }
            client.interactionManager.attackEntity(self, target);
            self.swingHand(Hand.MAIN_HAND);
            attackSent = true;
        }

        if (!self.getInventory().getStack(maceSlot).isOf(Items.MACE)) {
            sendLook(self, cameraYaw, cameraPitch);
            scheduleSlotRestore(self, originalSlot, stunSlam ? axeSlot : originalSlot);
            return attackSent ? AttackOutcome.PARTIAL : AttackOutcome.NONE;
        }

        selectHotbarSlot(self, maceSlot);
        if (!validateAtAttackTime(self, target)
                || (!stunSlam && !isAttackReady(self))
                || (config.requireClearPath() && !hasClearPath(client.world, self, chooseAimPoint(self, target)))) {
            sendLook(self, cameraYaw, cameraPitch);
            scheduleSlotRestore(self, originalSlot, maceSlot);
            return attackSent ? AttackOutcome.PARTIAL : AttackOutcome.NONE;
        }

        client.interactionManager.attackEntity(self, target);
        self.swingHand(Hand.MAIN_HAND);

        // Explicitly restore server-side look. Because the local camera never moved,
        // relying on the next normal movement tick may leave the server facing the
        // silent aim rotation longer than intended.
        sendLook(self, cameraYaw, cameraPitch);
        scheduleSlotRestore(self, originalSlot, maceSlot);
        return AttackOutcome.COMPLETE;
    }

    private boolean isAttackReady(ClientPlayerEntity self) {
        // Attack speed belongs to the selected stack. Call this only after the
        // automatic slot change so the configured threshold applies to the weapon
        // that will send the first attack in the sequence.
        return self.getAttackCooldownProgress(0.0F) >= config.minimumAttackCooldown();
    }

    private boolean validateAtAttackTime(ClientPlayerEntity self, PlayerEntity target) {
        if (target == self || !target.isAlive() || target.isRemoved() || target.isSpectator() || !target.canHit()) {
            return false;
        }
        if (config.ignoreCreativePlayers() && target.isCreative()) {
            return false;
        }
        if (config.ignoreTeammates() && self.isTeammate(target)) {
            return false;
        }

        double triggerRangeSq = config.triggerRange() * config.triggerRange();
        return self.getBoundingBox().squaredMagnitude(target.getBoundingBox()) <= triggerRangeSq
                && self.canAttackEntityIn(target.getBoundingBox(), 0.0D)
                && isSmashFall(self);
    }

    private boolean shouldStunSlam(PlayerEntity target) {
        boolean shieldEquipped = target.getMainHandStack().isOf(Items.SHIELD)
                || target.getOffHandStack().isOf(Items.SHIELD);
        if (!shieldEquipped) {
            return false;
        }

        return switch (config.shieldTrigger()) {
            case EQUIPPED -> true;
            case RAISED_ONLY -> target.isBlocking()
                    && target.getBlockingItem() != null
                    && target.getBlockingItem().isOf(Items.SHIELD);
        };
    }

    private Vec3d chooseAimPoint(ClientPlayerEntity self, PlayerEntity target) {
        Vec3d eye = self.getEyePos();
        Box box = target.getBoundingBox();

        // Aim slightly inside the hitbox. Clamping to its surface can become a miss
        // after floating-point rounding at steep vertical angles.
        double inset = 0.03D;
        double minX = box.minX + inset;
        double minY = box.minY + inset;
        double minZ = box.minZ + inset;
        double maxX = box.maxX - inset;
        double maxY = box.maxY - inset;
        double maxZ = box.maxZ - inset;

        Vec3d point = new Vec3d(
                MathHelper.clamp(eye.x, minX, maxX),
                MathHelper.clamp(eye.y, minY, maxY),
                MathHelper.clamp(eye.z, minZ, maxZ)
        );

        return point.squaredDistanceTo(eye) < 1.0E-6D ? box.getCenter() : point;
    }

    private boolean hasClearPath(ClientWorld world, ClientPlayerEntity self, Vec3d aimPoint) {
        if (world == null) {
            return false;
        }

        HitResult blockHit = world.raycast(new RaycastContext(
                self.getEyePos(),
                aimPoint,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                self
        ));
        return blockHit.getType() == HitResult.Type.MISS;
    }

    private double rotationCost(ClientPlayerEntity self, Vec3d aimPoint) {
        Rotation rotation = rotationTo(self.getEyePos(), aimPoint);
        double yawDelta = Math.abs(MathHelper.wrapDegrees(rotation.yaw() - self.getYaw()));
        double pitchDelta = Math.abs(rotation.pitch() - self.getPitch());
        return yawDelta + pitchDelta;
    }

    private Rotation rotationTo(Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F);
        float pitch = MathHelper.clamp(
                (float) -Math.toDegrees(Math.atan2(delta.y, horizontal)),
                -90.0F,
                90.0F
        );
        return new Rotation(yaw, pitch);
    }

    private int findMaceInHotbar(ClientPlayerEntity self) {
        for (int slot = 0; slot < 9; slot++) {
            if (self.getInventory().getStack(slot).isOf(Items.MACE)) {
                return slot;
            }
        }
        return -1;
    }

    private int findAxeInHotbar(ClientPlayerEntity self) {
        for (int slot = 0; slot < 9; slot++) {
            if (self.getInventory().getStack(slot).isIn(ItemTags.AXES)) {
                return slot;
            }
        }
        return -1;
    }

    private void selectHotbarSlot(ClientPlayerEntity self, int slot) {
        if (self.getInventory().getSelectedSlot() == slot) {
            return;
        }
        self.getInventory().setSelectedSlot(slot);
        self.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private void sendLook(ClientPlayerEntity self, float yaw, float pitch) {
        self.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                yaw,
                pitch,
                self.isOnGround(),
                self.horizontalCollision
        ));
    }

    private void scheduleSlotRestore(ClientPlayerEntity self, int original, int currentAutomaticSlot) {
        if (original == currentAutomaticSlot) {
            clearSlotRestore();
            return;
        }
        restoreSlot = original;
        automaticSlot = currentAutomaticSlot;
        restoreTicks = Math.max(1, config.restoreSlotAfterTicks());
    }

    private void tickPendingSlotRestore(MinecraftClient client) {
        if (restoreSlot < 0 || client.player == null) {
            return;
        }
        if (--restoreTicks > 0) {
            return;
        }

        ClientPlayerEntity self = client.player;
        // If the user manually selected another slot after the combo, respect it.
        if (self.getInventory().getSelectedSlot() == automaticSlot) {
            selectHotbarSlot(self, restoreSlot);
        }
        clearSlotRestore();
    }

    private void clearSlotRestore() {
        restoreSlot = -1;
        automaticSlot = -1;
        restoreTicks = 0;
    }

    private void resetForWorld(ClientWorld world) {
        lastWorld = world;
        attackedThisFall = false;
        descendingTicks = 0;
        cooldownTicks = 0;
        clearSlotRestore();
    }

    private record Candidate(PlayerEntity player, Vec3d aimPoint) {}

    private record Rotation(float yaw, float pitch) {}

    private enum AttackOutcome {
        NONE(false),
        PARTIAL(true),
        COMPLETE(true);

        private final boolean consumesFall;

        AttackOutcome(boolean consumesFall) {
            this.consumesFall = consumesFall;
        }

        boolean consumesFall() {
            return consumesFall;
        }
    }
}
