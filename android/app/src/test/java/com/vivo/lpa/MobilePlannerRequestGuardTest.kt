package com.vivo.lpa

import org.junit.Assert.assertTrue
import org.junit.Test

class MobilePlannerRequestGuardTest {
    @Test
    fun localRecolorWordingIsAnExplicitEditRequest() {
        assertTrue(MobilePlanner.isExplicitEditQuery(
            "帮我把live中滑滑板的人调成红色么"
        ))
    }
}
