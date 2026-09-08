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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives the intra-site clock delay (sink site pin to clocked BEL pin) of every clock sink of a
 * design: Vivado's full clock net delay to the cell pin ({@code <run>.clocksinks.csv}) minus the
 * model's interconnect arrival at the site pin node (exact for a traced design). Writes
 * {@code <out>.clocksite.csv} for fit_clock_model.py.
 *
 * <pre>Usage: VersalClockSinkTable design.dcp design.edf x.clocksinks.csv out.clocksite.csv [clock_delay_terms.txt]</pre>
 */
public class VersalClockSinkTable {
    private static Map<String, List<Cell>> macroChildrenCache;

    static Map<String, List<Cell>> macroChildren(Design design) {
        if (macroChildrenCache == null) {
            macroChildrenCache = new HashMap<>();
            for (Cell c : design.getCells()) {
                int i = c.getName().lastIndexOf('/');
                if (i > 0) macroChildrenCache.computeIfAbsent(c.getName().substring(0, i), k -> new ArrayList<>()).add(c);
            }
        }
        return macroChildrenCache;
    }

    public static void main(String[] args) throws IOException {
        Design design = new java.io.File(args[1]).exists() ? Design.readCheckpoint(args[0], args[1]) : Design.readCheckpoint(args[0]);   // "-": the DCP carries its own netlist
        com.xilinx.rapidwright.design.DesignTools.createMissingSitePinInsts(design);
        VersalClockModel cm = args.length > 4 ? new VersalClockModel(args[4]) : new VersalClockModel();
        List<String[]> rows = VersalTimingValidator.readCsv(args[2]);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < rows.get(0).length; i++) col.put(rows.get(0)[i], i);
        Map<Net, VersalClockModel.ClockTree> trees = new HashMap<>();
        int written = 0, skipped = 0;
        try (PrintWriter pw = new PrintWriter(args[3])) {
            pw.println("site_type,site_pin,bel_pin,d_slow_max,d_slow_min,d_fast_max,d_fast_min,sink_pin,mode,taps,implicit");
            for (String[] r : rows.subList(1, rows.size())) {
                Net net = design.getNet(r[col.get("net")]);
                String sinkPin = r[col.get("sink_pin")];
                int slash = sinkPin.lastIndexOf('/');
                Cell cell = design.getCell(sinkPin.substring(0, slash));
                String logical = sinkPin.substring(slash + 1);
                if (cell == null || cell.getPhysicalPinMapping(logical) == null) {
                    // macro pins (DSP58): the placed leaf primitive owns the pin
                    cell = null;
                    for (Cell c : macroChildren(design).getOrDefault(sinkPin.substring(0, slash), java.util.Collections.emptyList()))
                        if (c.getPhysicalPinMapping(logical) != null) { cell = c; break; }
                }
                if (net == null || cell == null) { skipped++; continue; }
                SitePinInst spi = cell.getSitePinFromLogicalPin(logical, null);
                if (spi == null && cell.getSiteInst() != null) {
                    String phys = cell.getPhysicalPinMapping(logical);
                    spi = phys == null ? null : cell.getSiteInst().getSitePinInst(phys);
                    if (spi == null || spi.getNet() != net) {
                        spi = null;
                        for (SitePinInst p : cell.getSiteInst().getSitePinInsts()) if (p.getNet() == net) { spi = spi == null ? p : null; if (spi == null) break; }
                    }
                }
                if (spi == null) { skipped++; continue; }
                VersalClockModel.ClockTree t = trees.computeIfAbsent(net, cm::analyze);
                float[] a = t.sinkArrival.get(spi);
                if (a == null) { skipped++; continue; }
                String phys = cell.getPhysicalPinMapping(sinkPin.substring(slash + 1));
                BELPin bp = phys == null ? null : cell.getBEL().getPin(phys);
                if (bp == null) { skipped++; continue; }
                float[] full = new float[4];
                String[] cols = {"slow_max", "slow_min", "fast_max", "fast_min"};
                boolean ok = true;
                for (int i = 0; i < 4; i++) {
                    String v = r[col.get(cols[i])];
                    if (v.isEmpty()) { ok = false; break; }
                    full[i] = Float.parseFloat(v);
                }
                if (!ok) { skipped++; continue; }
                Object[] leaf = VersalClockModel.leafClockDelay(cell);
                pw.printf("%s,%s,%s,%.1f,%.1f,%.1f,%.1f,%s,%s,%d,%d%n", spi.getSiteTypeEnum().name(), spi.getName(), bp.getBEL().getName() + "/" + bp.getName(),
                        full[0] - a[0], full[1] - a[1], full[2] - a[2], full[3] - a[3], sinkPin, leaf[0], (Integer) leaf[1],
                        VersalClockModel.isImplicitSink(t, spi) ? 1 : 0);
                written++;
            }
        }
        System.out.println("wrote " + written + " rows to " + args[3] + ", skipped " + skipped + "; clock model tiers " + cm.getTierUse());
    }
}
