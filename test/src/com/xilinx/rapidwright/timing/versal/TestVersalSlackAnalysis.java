/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.timing.versal;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestVersalSlackAnalysis {

    @Test
    public void testEdgeRequirement() {
        // one clock: its period
        Assertions.assertEquals(2000f, VersalSlackAnalysis.edgeRequirementPs(2000f, 2000f));
        // the V80 shell's control clock (3 ns) into the array clock (2 ns) and back: launch at 3, capture at 4;
        // launch at 2, capture at 3
        Assertions.assertEquals(1000f, VersalSlackAnalysis.edgeRequirementPs(3000f, 2000f));
        Assertions.assertEquals(1000f, VersalSlackAnalysis.edgeRequirementPs(2000f, 3000f));
        // a multiple: the faster clock's period
        Assertions.assertEquals(2000f, VersalSlackAnalysis.edgeRequirementPs(4000f, 2000f));
        Assertions.assertEquals(2000f, VersalSlackAnalysis.edgeRequirementPs(2000f, 4000f));
        // 4 ns into 3 ns: launch at 8, capture at 9
        Assertions.assertEquals(1000f, VersalSlackAnalysis.edgeRequirementPs(4000f, 3000f));
    }
}
