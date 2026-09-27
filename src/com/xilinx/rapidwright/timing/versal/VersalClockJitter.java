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
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;

/**
 * Vivado's clock jitter (CJ) of a global clock on the xcv80, from the footprint of its tree: setup
 * uncertainty is CJ / 2 ({@code report_timing}: "Clock Uncertainty: (CJ)/2 + PE + PJ", with PE
 * and PJ 0 for a clock from a port through a BUFGCE). Vivado gives every path of a clock the same
 * CJ, and it depends only on how many clock-region rows the loads span and how far the loads
 * reach across from the root's vertical spine (in region columns, the root column and its
 * neighbour across the spine being 1): not on where the rows are (Y1..Y3 = Y6..Y8 = Y8..Y10),
 * which SLRs they are in, the trunk from the buffer to the root, the buffer column, the tree
 * type, the number or kind of loads, or where in a region they sit. In sweeps of Vivado-routed
 * trees at 8 periods (Sep 27) it followed one of four regimes:
 * <ul>
 * <li>up to 4 rows and a reach up to 3: a base plus one step per extra row and two per extra
 * region of reach (at 3 ns 81 ps + 10.7 per row + 21.5 per region: the mesh 4x4 113, the 8x8
 * 145);</li>
 * <li>up to 4 rows and a reach of 4 or more: one value (156 ps at 3 ns), rows adding nothing;</li>
 * <li>5 to 8 rows: one value (178 ps at 3 ns), whatever the reach;</li>
 * <li>9 rows or more: one value (201 ps at 3 ns).</li>
 * </ul>
 * The period moves every regime by a small amount of its own (1.5 ns adds about 16 ps, 2 ns 11 ps
 * on the small trees and 23 on the 5..8-row ones; from 3 ns up within 4 ps); between the sampled
 * periods the tables are interpolated. Values are in ps.
 */
public class VersalClockJitter {

    /** The periods (ns) the tables were measured at. */
    static final float[] PERIODS_NS = {1.5f, 2.0f, 2.5f, 3.0f, 4.0f, 6.0f, 10.0f, 13.333f};

    /** Small trees: one row, reach 1 (the base). */
    static final int[] BASE = { 97,  92,  82,  81,  78,  80,  81,  82};
    /** Small trees: four rows, reach 1 (the base plus three row steps). */
    static final int[] FOUR_ROWS = {130, 125, 115, 113, 111, 112, 114, 114};
    /** Small trees: one row, reach 3 (the base plus two region steps, each twice a row step). */
    static final int[] REACH_THREE = {141, 136, 126, 124, 122, 123, 125, 125};
    /** Up to 4 rows, reach 4 or more. */
    static final int[] WIDE = {173, 168, 158, 156, 154, 156, 157, 157};
    /** 5 to 8 rows. */
    static final int[] TALL = {194, 201, 178, 178, 179, 180, 181, 181};
    /** 9 rows or more. */
    static final int[] TALLER = {222, 218, 204, 201, 198, 197, 197, 197};

    private VersalClockJitter() {
    }

    /** Whether the tables apply to this device (they were measured on the xcv80). */
    public static boolean covers(Device device) {
        return device != null && device.getName().startsWith("xcv80");
    }

    /**
     * Clock jitter (ps) of a tree spanning {@code rows} clock-region rows whose loads reach
     * {@code spineDistance} region columns across from the root's spine, at the given period.
     */
    public static float jitterPs(int rows, int spineDistance, float periodPs) {
        float ns = periodPs / 1000f;
        rows = Math.max(1, rows);
        spineDistance = Math.max(1, spineDistance);
        if (rows >= 9) return interpolate(TALLER, ns);
        if (rows >= 5) return interpolate(TALL, ns);
        if (spineDistance >= 4) return interpolate(WIDE, ns);
        float base = interpolate(BASE, ns);
        float rowStep = (interpolate(FOUR_ROWS, ns) - base) / 3f;
        float regionStep = (interpolate(REACH_THREE, ns) - base) / 2f;
        return base + rowStep * (rows - 1) + regionStep * (spineDistance - 1);
    }

    private static float interpolate(int[] row, float periodNs) {
        if (periodNs <= PERIODS_NS[0]) return row[0];
        for (int i = 1; i < PERIODS_NS.length; i++) {
            if (periodNs <= PERIODS_NS[i]) {
                float t = (periodNs - PERIODS_NS[i - 1]) / (PERIODS_NS[i] - PERIODS_NS[i - 1]);
                return row[i - 1] + t * (row[i] - row[i - 1]);
            }
        }
        return row[row.length - 1];
    }

    /**
     * The tree's shape from a routed clock net: {rows spanned, furthest spine distance, root
     * column}, or null when the net has no clock root or no sinks. The root column is the clock
     * region column of the net's CLOCK_ROOT property (set by the router, RWRoute's or Vivado's);
     * its spine runs along the column's east edge on the xcv80, so the root column and the one
     * east of it are 1 region away, and each column beyond adds one.
     */
    public static int[] treeShape(Net clk) {
        Integer rootX = rootColumn(clk);
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, maxDist = 0;
        for (SitePinInst p : clk.getPins()) {
            if (p.isOutPin()) continue;
            ClockRegion cr = p.getTile().getClockRegion();
            if (cr == null) continue;
            minY = Math.min(minY, cr.getInstanceY());
            maxY = Math.max(maxY, cr.getInstanceY());
            if (rootX != null) {
                int x = cr.getInstanceX();
                maxDist = Math.max(maxDist, x <= rootX ? rootX - x + 1 : x - rootX);
            }
        }
        if (rootX == null || minY > maxY) return null;
        return new int[] {maxY - minY + 1, maxDist, rootX};
    }

    private static Integer rootColumn(Net clk) {
        EDIFPropertyValue root = clk.getLogicalNet() == null ? null : clk.getLogicalNet().getProperty("CLOCK_ROOT");
        if (root == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("X(\\d+)Y\\d+").matcher(root.getValue());
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /**
     * The setup uncertainty (ps) Vivado applies to paths clocked by a routed global clock at the
     * given period: half its clock jitter. NaN when the device is not covered or the tree's shape
     * is unknown (no clock root on the net).
     */
    public static float setupUncertaintyPs(Net clk, float periodPs) {
        if (clk == null || clk.getDesign() == null || !covers(clk.getDesign().getDevice())) return Float.NaN;
        int[] shape = treeShape(clk);
        return shape == null ? Float.NaN : jitterPs(shape[0], shape[1], periodPs) / 2f;
    }

    /** Prints the tree shape and the predicted jitter of a design's clock net: {@code <dcp> [edf] <clock net> <period ns>}. */
    public static void main(String[] args) {
        Design d = args.length > 3 ? Design.readCheckpoint(args[0], args[1]) : Design.readCheckpoint(args[0]);
        Net clk = d.getNet(args[args.length - 2]);
        float periodPs = Float.parseFloat(args[args.length - 1]) * 1000f;
        int[] shape = treeShape(clk);
        if (shape == null) {
            System.out.println("no clock root or sinks on " + clk);
            return;
        }
        float cj = jitterPs(shape[0], shape[1], periodPs);
        System.out.printf("%s: rows %d, spine distance %d (root column X%d) -> clock jitter %.0f ps, setup uncertainty %.1f ps%n",
                clk, shape[0], shape[1], shape[2], cj, cj / 2f);
    }
}
