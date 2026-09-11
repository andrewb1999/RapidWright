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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;

/**
 * Writes a copy of a checkpoint with every net but the global clocks unrouted, for
 * {@code dump_clock_trace.tcl traceonly}: Vivado's own {@code route_design -unroute} of the 74k
 * nets of the RapidSA 8x8 needs more than 37 GB, unrouting here costs nothing.
 * <p>
 * Usage: {@code VersalClockOnlyCheckpoint in.dcp [in.edf|-] out.dcp}
 */
public class VersalClockOnlyCheckpoint {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: VersalClockOnlyCheckpoint in.dcp [in.edf|-] out.dcp");
            return;
        }
        String edf = args.length > 2 && !args[1].equals("-") ? args[1] : null;
        String out = args[args.length - 1];
        Design d = edf == null ? Design.readCheckpoint(args[0]) : Design.readCheckpoint(args[0], edf);
        int kept = 0, unrouted = 0;
        for (Net n : d.getNets()) {
            if (n.isClockNet() || n.getName().equals("clk")) { kept++; continue; }
            if (n.hasPIPs()) { n.unroute(); unrouted++; }
        }
        System.out.printf("kept %d clock nets, unrouted %d nets%n", kept, unrouted);
        d.writeCheckpoint(out);
    }
}
