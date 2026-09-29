package com.nstut.explosion;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Lightweight calculation benchmark. This is informational rather than a wall-clock
 * assertion; correctness lives in FastExplosionEngineTest.
 */
class ExplosionBenchmarkTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void benchmarkRadius60Stone() {
        FastExplosionEngine.BlockView world = new FastExplosionEngine.BlockView() {
            @Override
            public boolean isInWorldBounds(int x, int y, int z) {
                return y >= -64 && y < 320;
            }

            @Override
            public BlockState getBlockState(int x, int y, int z) {
                return Blocks.STONE.defaultBlockState();
            }
        };

        for (int i = 0; i < 2; i++) {
            run(world, 60.0F, i == 0 ? "warmup" : "measured");
        }
    }


    @Test
    void benchmarkRadius10Forest() {
        FastExplosionEngine.BlockView world = view((x, y, z) -> {
            if (y > 64) return Blocks.AIR.defaultBlockState();
            if (y == 64) return Blocks.GRASS_BLOCK.defaultBlockState();
            return Blocks.DIRT.defaultBlockState();
        });
        run(world, new Vec3(0.5D, 64.5D, 0.5D), 10.0F, "radius-10 forest");
    }

    @Test
    void benchmarkRadius15Underground() {
        FastExplosionEngine.BlockView world = view((x, y, z) ->
            y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState()
        );
        run(world, new Vec3(0.5D, -9.5D, 0.5D), 15.0F, "radius-15 underground");
    }

    private interface StateAt {
        BlockState get(int x, int y, int z);
    }

    private static FastExplosionEngine.BlockView view(StateAt stateAt) {
        return new FastExplosionEngine.BlockView() {
            @Override
            public boolean isInWorldBounds(int x, int y, int z) {
                return y >= -64 && y < 320;
            }

            @Override
            public BlockState getBlockState(int x, int y, int z) {
                return stateAt.get(x, y, z);
            }
        };
    }
    private static void run(FastExplosionEngine.BlockView world, float power, String label) {
        run(world, new Vec3(0.5D, 0.5D, 0.5D), power, label);
    }

    private static void run(FastExplosionEngine.BlockView world, Vec3 center, float power, String label) {
        FastExplosionEngine.IncrementalCalculation calculation = FastExplosionEngine.create(
            world,
            center,
            power,
            RandomSource.create(0xB00B135L)
        );
        long start = System.nanoTime();
        calculation.processUntil(Long.MAX_VALUE);
        double ms = (System.nanoTime() - start) / 1_000_000.0D;
        System.out.printf(
            "Perfomant Boom %s: %.2fms, %d ray samples, %d unique blocks%n",
            label,
            ms,
            calculation.sampleCount(),
            calculation.blockCount()
        );
    }
}
