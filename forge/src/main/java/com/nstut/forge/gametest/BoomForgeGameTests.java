package com.nstut.forge.gametest;

import com.nstut.testing.BoomGameTestLogic;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("minecraft")
@PrefixGameTestTemplate(false)
public final class BoomForgeGameTests {
    private BoomForgeGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void explosionMaintainsWorldBookkeeping(GameTestHelper helper) {
        BoomGameTestLogic.explosionMaintainsWorldBookkeeping(helper);
    }
}
