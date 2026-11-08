package com.xbow;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class XbowController {
    // ---- Настройки (меняй прямо здесь) ----
    private static final boolean LOCK_WALK = true;
    private static final double MIN_DISTANCE = 3.0;
    private static final double MAX_DISTANCE = 4.0;
    private static final int ACTION_DELAY = 2;        // тиков между действиями
    private static final float AIM_SPEED = 22.0f;     // макс. градусов за тик
    private static final int MAX_SEQUENCE_TICKS = 300;
    private static final int WAIT_LIMIT = 10;

    private enum Phase {
        FIND_POSITION, SWAP_RAIL, PLACE_RAIL, WAIT_RAIL,
        SWAP_CART, PLACE_CART, WAIT_CART,
        SWAP_XBOW, AIM, SHOOT, WAIT_SHOT, FINISH
    }

    private boolean active;
    private Phase phase;
    private BlockPos railPos;
    private int savedSlot = -1;

    private int age, delay, waitTicks, swapTries;
    private int railAttempts, cartAttempts, shotTries, blockedTicks, stableTicks;

    private boolean rotInit;
    private float curYaw, curPitch;

    // ------------------------------------------------------------------

    public void toggle(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        if (active) {
            say(mc, "Xbow: остановлено");
            finish(mc);
            return;
        }
        start(mc);
    }

    public void lockKeys(MinecraftClient mc) {
        if (!active || !LOCK_WALK || mc.player == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
    }

    private void start(MinecraftClient mc) {
        if (findRailSlot(mc) == -1) { say(mc, "Xbow: нет рельс в хотбаре"); return; }
        if (findItemSlot(mc, Items.TNT_MINECART) == -1) { say(mc, "Xbow: нет TNT-вагонетки в хотбаре"); return; }
        if (findChargedXbow(mc) == -1) { say(mc, "Xbow: нет заряженного арбалета в хотбаре"); return; }

        savedSlot = mc.player.getInventory().getSelectedSlot();
        phase = Phase.FIND_POSITION;
        railPos = null;
        age = delay = waitTicks = swapTries = 0;
        railAttempts = cartAttempts = shotTries = blockedTicks = stableTicks = 0;
        rotInit = false;
        active = true;
        say(mc, "Xbow: запуск");
    }

    private void finish(MinecraftClient mc) {
        if (mc.player != null && active && savedSlot >= 0 && savedSlot <= 8) {
            select(mc, savedSlot);
        }
        active = false;
        phase = null;
        railPos = null;
        savedSlot = -1;
        rotInit = false;
    }

    public void tick(MinecraftClient mc) {
        if (!active) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            active = false;
            phase = null;
            return;
        }
        if (++age > MAX_SEQUENCE_TICKS) {
            say(mc, "Xbow: таймаут");
            finish(mc);
            return;
        }
        if (delay > 0) {
            delay--;
            return;
        }

        switch (phase) {
            case FIND_POSITION -> {
                railPos = findRailPosition(mc);
                if (railPos == null) {
                    say(mc, "Xbow: нет подходящего места");
                    phase = Phase.FINISH;
                    return;
                }
                swapTries = 0;
                phase = Phase.SWAP_RAIL;
            }
            case SWAP_RAIL -> {
                int slot = findRailSlot(mc);
                if (slot == -1) { phase = Phase.FINISH; return; }
                select(mc, slot);
                pause();
                phase = Phase.PLACE_RAIL;
            }
            case PLACE_RAIL -> {
                if (!isRailStack(mc.player.getMainHandStack())) {
                    if (++swapTries > 3) { phase = Phase.FINISH; return; }
                    phase = Phase.SWAP_RAIL;
                    return;
                }
                if (isRail(mc, railPos)) {
                    waitTicks = 0;
                    phase = Phase.WAIT_RAIL;
                    return;
                }
                if (!canPlace(mc, railPos)) { phase = Phase.FINISH; return; }

                Vec3d hit = new Vec3d(railPos.getX() + 0.5, railPos.getY(), railPos.getZ() + 0.5);
                if (!rotateTo(mc, hit)) return;

                place(mc, hit, Direction.UP, railPos.down());
                railAttempts++;
                waitTicks = 0;
                pause();
                phase = Phase.WAIT_RAIL;
            }
            case WAIT_RAIL -> {
                if (isRail(mc, railPos)) {
                    swapTries = 0;
                    pause();
                    phase = Phase.SWAP_CART;
                } else if (++waitTicks > WAIT_LIMIT) {
                    phase = railAttempts < 2 ? Phase.PLACE_RAIL : Phase.FINISH;
                }
            }
            case SWAP_CART -> {
                int slot = findItemSlot(mc, Items.TNT_MINECART);
                if (slot == -1) { phase = Phase.FINISH; return; }
                select(mc, slot);
                pause();
                phase = Phase.PLACE_CART;
            }
            case PLACE_CART -> {
                if (!isRail(mc, railPos)) { phase = Phase.FINISH; return; }
                if (findCart(mc, railPos) != null) {
                    waitTicks = 0;
                    phase = Phase.WAIT_CART;
                    return;
                }
                if (!mc.player.getMainHandStack().isOf(Items.TNT_MINECART)) {
                    if (++swapTries > 3) { phase = Phase.FINISH; return; }
                    phase = Phase.SWAP_CART;
                    return;
                }
                Vec3d hit = new Vec3d(railPos.getX() + 0.5, railPos.getY() + 0.15, railPos.getZ() + 0.5);
                if (!rotateTo(mc, hit)) return;

                place(mc, hit, Direction.UP, railPos);
                cartAttempts++;
                waitTicks = 0;
                pause();
                phase = Phase.WAIT_CART;
            }
            case WAIT_CART -> {
                if (findCart(mc, railPos) != null) {
                    swapTries = 0;
                    pause();
                    phase = Phase.SWAP_XBOW;
                } else if (++waitTicks > WAIT_LIMIT) {
                    phase = cartAttempts < 2 ? Phase.PLACE_CART : Phase.FINISH;
                }
            }
            case SWAP_XBOW -> {
                int slot = findChargedXbow(mc);
                if (slot == -1) { phase = Phase.FINISH; return; }
                select(mc, slot);
                stableTicks = 0;
                blockedTicks = 0;
                pause();
                phase = Phase.AIM;
            }
            case AIM -> {
                TntMinecartEntity cart = findCart(mc, railPos);
                if (cart == null) { phase = Phase.FINISH; return; }
                if (!isChargedXbow(mc.player.getMainHandStack())) {
                    if (++swapTries > 3) { phase = Phase.FINISH; return; }
                    phase = Phase.SWAP_XBOW;
                    return;
                }
                Vec3d target = findAimPoint(mc, cart);
                if (target == null) {
                    if (++blockedTicks > 20) phase = Phase.FINISH;
                    return;
                }
                blockedTicks = 0;

                boolean reached = rotateTo(mc, target);
                if (reached && lookRayHitsCart(mc, cart)) {
                    if (++stableTicks >= 2) phase = Phase.SHOOT;
                } else {
                    stableTicks = 0;
                }
            }
            case SHOOT -> {
                TntMinecartEntity cart = findCart(mc, railPos);
                if (cart == null || !isChargedXbow(mc.player.getMainHandStack())) {
                    phase = Phase.FINISH;
                    return;
                }
                if (!lookRayHitsCart(mc, cart)) {
                    stableTicks = 0;
                    phase = Phase.AIM;
                    return;
                }
                ActionResult result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                if (result.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
                shotTries++;
                waitTicks = 0;
                phase = Phase.WAIT_SHOT;
            }
            case WAIT_SHOT -> {
                // Успех: арбалет больше не заряжен или вагонетка исчезла
                if (!isChargedXbow(mc.player.getMainHandStack()) || findCart(mc, railPos) == null) {
                    phase = Phase.FINISH;
                    return;
                }
                if (++waitTicks > WAIT_LIMIT) {
                    if (shotTries < 2) {
                        stableTicks = 0;
                        phase = Phase.AIM;
                    } else {
                        phase = Phase.FINISH;
                    }
                }
            }
            case FINISH -> finish(mc);
        }
    }

    // ------------------------------------------------------------------
    // Поиск позиции и проверки

    private BlockPos findRailPosition(MinecraftClient mc) {
        Vec3d eye = mc.player.getEyePos();
        double yawRad = Math.toRadians(mc.player.getYaw());
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);
        double perpX = -dirZ;
        double perpZ = dirX;

        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int[] yOffsets = {0, -1, 1};
        int[] lateral = {0, -1, 1};

        for (double d = MIN_DISTANCE; d <= MAX_DISTANCE; d += 0.5) {
            for (int lat : lateral) {
                for (int yo : yOffsets) {
                    double x = mc.player.getX() + dirX * d + perpX * lat;
                    double z = mc.player.getZ() + dirZ * d + perpZ * lat;
                    BlockPos pos = BlockPos.ofFloored(x, mc.player.getY() + yo, z);
                    if (!isValidRailPos(mc, pos, eye)) continue;

                    double score = -Math.abs(lat) * 1.5 - Math.abs(yo) + d * 0.2;
                    if (score > bestScore) {
                        bestScore = score;
                        best = pos.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean isValidRailPos(MinecraftClient mc, BlockPos pos, Vec3d eye) {
        Vec3d center = Vec3d.ofCenter(pos);
        double dx = mc.player.getX() - center.x;
        double dz = mc.player.getZ() - center.z;
        if (Math.sqrt(dx * dx + dz * dz) < MIN_DISTANCE) return false;

        Vec3d railHit = new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        if (eye.distanceTo(railHit) > MAX_DISTANCE) return false;

        if (isRail(mc, pos)) return false;
        if (!canPlace(mc, pos)) return false;

        Box posBox = new Box(pos);
        if (mc.player.getBoundingBox().intersects(posBox)) return false;
        if (!mc.world.getOtherEntities(null, posBox).isEmpty()) return false;

        if (!blockClear(mc, eye, new Vec3d(pos.getX() + 0.5, pos.getY() + 0.02, pos.getZ() + 0.5))) return false;
        return blockClear(mc, eye, new Vec3d(pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5));
    }

    private boolean canPlace(MinecraftClient mc, BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (!state.isAir() && !state.isReplaceable()) return false;
        BlockPos down = pos.down();
        return mc.world.getBlockState(down).isSideSolidFullSquare(mc.world, down, Direction.UP);
    }

    private boolean isRail(MinecraftClient mc, BlockPos pos) {
        return mc.world.getBlockState(pos).isIn(BlockTags.RAILS);
    }

    private boolean isRailStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.isIn(ItemTags.RAILS)
                || stack.isOf(Items.RAIL)
                || stack.isOf(Items.POWERED_RAIL)
                || stack.isOf(Items.DETECTOR_RAIL)
                || stack.isOf(Items.ACTIVATOR_RAIL);
    }

    private boolean isChargedXbow(ItemStack stack) {
        return stack != null && stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack);
    }

    private int findRailSlot(MinecraftClient mc) {
        for (int i = 0; i < 9; i++) {
            if (isRailStack(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private int findItemSlot(MinecraftClient mc, net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private int findChargedXbow(MinecraftClient mc) {
        for (int i = 0; i < 9; i++) {
            if (isChargedXbow(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private TntMinecartEntity findCart(MinecraftClient mc, BlockPos pos) {
        if (pos == null) return null;
        Box box = new Box(pos).expand(0.35, 1.0, 0.35);
        for (Entity entity : mc.world.getOtherEntities(null, box)) {
            if (entity instanceof TntMinecartEntity cart) return cart;
        }
        return null;
    }

    private boolean blockClear(MinecraftClient mc, Vec3d from, Vec3d to) {
        return mc.world.raycast(new RaycastContext(
                from, to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player
        )).getType() == HitResult.Type.MISS;
    }

    private Vec3d findAimPoint(MinecraftClient mc, TntMinecartEntity cart) {
        Vec3d eye = mc.player.getEyePos();
        double height = cart.getHeight();
        double[] fractions = {0.5, 0.65, 0.35, 0.8};
        for (double f : fractions) {
            Vec3d aim = new Vec3d(cart.getX(), cart.getY() + height * f, cart.getZ());
            if (blockClear(mc, eye, aim)) return aim;
        }
        return null;
    }

    private boolean lookRayHitsCart(MinecraftClient mc, TntMinecartEntity cart) {
        double yawRad = Math.toRadians(curYaw);
        double pitchRad = Math.toRadians(curPitch);
        Vec3d dir = new Vec3d(
                -Math.cos(pitchRad) * Math.sin(yawRad),
                -Math.sin(pitchRad),
                Math.cos(pitchRad) * Math.cos(yawRad)
        );
        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(dir.multiply(12.0));
        Box box = cart.getBoundingBox().expand(0.05);
        var hit = box.raycast(eye, end);
        return hit.isPresent() && blockClear(mc, eye, hit.get());
    }

    // ------------------------------------------------------------------
    // Поворот, действия, слоты

    /** Плавно поворачивает камеру к точке. Возвращает true, когда цель достигнута. */
    private boolean rotateTo(MinecraftClient mc, Vec3d target) {
        if (!rotInit) {
            curYaw = mc.player.getYaw();
            curPitch = mc.player.getPitch();
            rotInit = true;
        }

        Vec3d eye = mc.player.getEyePos();
        Vec3d d = target.subtract(eye);
        double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float targetPitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(d.y, horiz)), -90f, 90f);

        float dYaw = MathHelper.wrapDegrees(targetYaw - curYaw);
        float dPitch = targetPitch - curPitch;
        float dist = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);

        boolean reached;
        if (dist <= AIM_SPEED) {
            curYaw += dYaw;
            curPitch = targetPitch;
            reached = true;
        } else {
            float k = AIM_SPEED / dist;
            curYaw += dYaw * k;
            curPitch += dPitch * k;
            reached = false;
        }
        curPitch = MathHelper.clamp(curPitch, -90f, 90f);

        mc.player.setYaw(curYaw);
        mc.player.setPitch(curPitch);
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    curYaw, curPitch,
                    mc.player.isOnGround(),
                    mc.player.horizontalCollision
            ));
        }
        return reached;
    }

    private void place(MinecraftClient mc, Vec3d hit, Direction side, BlockPos block) {
        ActionResult result = mc.interactionManager.interactBlock(
                mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, side, block, false));
        if (result.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
    }

    // Пакет смены слота клиент отправляет сам перед interactBlock/interactItem
    private void select(MinecraftClient mc, int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return;
        if (mc.player.getInventory().getSelectedSlot() != slot) {
            mc.player.getInventory().setSelectedSlot(slot);
        }
    }

    private void pause() {
        delay = ACTION_DELAY;
    }

    private void say(MinecraftClient mc, String msg) {
        if (mc.player != null) mc.player.sendMessage(Text.literal(msg), true);
    }
}
