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
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;

import java.io.PrintWriter;

/**
 * Writes the route tree of every global clock net of a design as (net, node, parent node, incoming PIP)
 * rows, for parse_clock_trace.py --topology (resolves the parent context of nodes whose incoming PIP
 * Vivado's diagnostics do not print).
 *
 * <pre>Usage: VersalClockTopology design.dcp design.edf out.clocktopo.csv</pre>
 */
public class VersalClockTopology {
    public static void main(String[] args) throws Exception {
        Design design = new java.io.File(args[1]).exists() ? Design.readCheckpoint(args[0], args[1]) : Design.readCheckpoint(args[0]);   // "-": the DCP carries its own netlist
        com.xilinx.rapidwright.design.DesignTools.createMissingSitePinInsts(design);
        // the same tree the model walks: PIPs (reversed bidirectional ones honoured) plus the implicit hops
        // into hard-block clock pins that a checkpoint may not list (see VersalClockModel.analyze)
        VersalClockModel cm = new VersalClockModel();
        int rows = 0;
        try (PrintWriter pw = new PrintWriter(args[2])) {
            pw.println("net,node,parent,incoming_pip");
            for (Net net : design.getNets()) {
                if (!VersalClockModel.isGlobalClockNet(net)) continue;
                VersalClockModel.ClockTree t = cm.analyze(net);
                for (java.util.Map.Entry<Node, Node> e : t.parent.entrySet()) {
                    pw.println(net.getName() + "," + e.getKey() + "," + e.getValue() + "," + t.parentPip.get(e.getKey()));
                    rows++;
                }
                if (t.implicitHops > 0) System.out.println(net.getName() + ": " + t.implicitHops + " implicit hops added for sinks the PIPs do not reach");
            }
        }
        System.out.println("wrote " + rows + " rows to " + args[2]);
    }
}
