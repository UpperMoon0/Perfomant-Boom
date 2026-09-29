package com.nstut.explosion.terrain;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Resumable terrain passes. The owner persists nextIndex and controls stage transitions.
 * Readiness callbacks must not synchronously load chunks. Boundary readiness includes all
 * six adjacent chunks. Positions passed to callbacks are immutable and safe to retain.
 */
public final class TerrainPasses {
    private TerrainPasses() {}
    public record Progress(long nextIndex, boolean done, boolean changed) {}

    /** Mutates the cursor in place; a blocked chunk retains the unprocessed position. */
    public static void clearSphere(ServerLevel level, BlockPos center, int targetRadius, SphereShellCursor scan,
            Predicate<BlockPos> ready, BooleanSupplier cancelled) {
        if (targetRadius < 0 || targetRadius > 128) throw new IllegalArgumentException("Invalid radius");
        ImpactWorkBudget budget = ImpactWorkBudget.forTick(level, level.getGameTime());
        while (scan.radius() <= targetRadius && !cancelled.getAsBoolean() && budget.tryScan()) {
            BlockPos pos = center.offset(scan.x(), scan.y(), scan.z());
            if (!level.isOutsideBuildHeight(pos)) {
                if (!ready.test(pos)) return;
                if (!level.getBlockState(pos).isAir()
                        && TerrainOperations.replaceWithoutDrops(level, pos, Blocks.AIR.defaultBlockState())) budget.changed();
            }
            scan.advance();
        }
    }

    public static Progress purgeFluids(ServerLevel level, BlockPos center, int radius, long index,
            Predicate<BlockPos> ready, BooleanSupplier cancelled) {
        FluidPurgeCursor scan = new FluidPurgeCursor(radius, index);
        ImpactWorkBudget budget = ImpactWorkBudget.forTick(level, level.getGameTime());
        boolean changed = false;
        while (!scan.done() && !cancelled.getAsBoolean() && budget.tryScan()) {
            BlockPos pos = center.offset(scan.x(), scan.y(), scan.z());
            if (scan.inside() && !level.isOutsideBuildHeight(pos)) {
                if (!ready.test(pos)) break;
                var state = level.getBlockState(pos);
                if (!state.getFluidState().isEmpty()) {
                    var dry = state.hasProperty(BlockStateProperties.WATERLOGGED)
                            ? state.setValue(BlockStateProperties.WATERLOGGED, false) : Blocks.AIR.defaultBlockState();
                    if (TerrainOperations.replaceWithoutDrops(level, pos, dry)) { budget.changed(); changed = true; }
                }
            }
            scan.advance();
        }
        return new Progress(scan.index(), scan.done(), changed);
    }

    public static Progress reconcileBoundary(ServerLevel level, BlockPos center, int radius, long index,
            Predicate<BlockPos> ready, BooleanSupplier cancelled) {
        SphereBoundaryCursor scan = new SphereBoundaryCursor(radius, index);
        ImpactWorkBudget budget = ImpactWorkBudget.forTick(level, level.getGameTime());
        boolean changed = false;
        while (!scan.done() && !cancelled.getAsBoolean() && budget.tryScan()) {
            BlockPos pos = center.offset(scan.x(), scan.y(), scan.z());
            if (scan.valid() && !level.isOutsideBuildHeight(pos)) {
                if (!ready.test(pos)) break;
                var state = level.getBlockState(pos);
                if (!state.isAir()) {
                    var next = TerrainOperations.reconcileBoundary(level, pos, state);
                    if (next != state && TerrainOperations.replaceWithoutDrops(level, pos, next)) {
                        budget.changed(); changed = true;
                    }
                }
            }
            scan.advance();
        }
        return new Progress(scan.index(), scan.done(), changed);
    }
}
