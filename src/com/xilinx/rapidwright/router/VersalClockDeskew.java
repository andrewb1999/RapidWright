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

package com.xilinx.rapidwright.router;

import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.design.BELAttr;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteConfig;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;

/**
 * Programs the Versal clock-network programmable delay elements that Vivado's
 * router uses for clock deskew ("Phase 8 Leaf Clock Prog Delay Opt"), via BEL
 * attributes that are carried in the DCP's BEL attribute database and honored
 * by Vivado's timing engine and bitstream generation.
 *
 * Two families of elements exist:
 * <ul>
 * <li>Per-slice leaf clock delay: the {@code FF_CLK_MOD} BEL at each SLICE
 * clock input has a 4-bit programmable delay ({@code CLK_DLY_VAL}, roughly
 * 65-70ps per tap) engaged by {@code IMUX_CLK_MODE=DELAY}. This retards the
 * clock arrival at all flops in that slice only, which shifts skew on
 * reg-to-reg paths into or out of that slice.</li>
 * <li>SSIT distribution delay: {@code GCLK_DELAY_SSIT} sites on the
 * SLR-crossing horizontal distribution taps, armed with
 * {@code ENABLE_OPT_DEL=TRUE} (Vivado's "optimized delay calculation").</li>
 * </ul>
 *
 * Tap values are the caller's responsibility (e.g. computed from a timing
 * report or a skew model); this class provides the mechanism.
 */
public class VersalClockDeskew {

    /** BEL name of the per-slice clock modifier that owns the delay taps. */
    public static final String FF_CLK_MOD = "FF_CLK_MOD";

    public static final String ATTR_CLK_DLY_VAL = "CLK_DLY_VAL";
    public static final String ATTR_IMUX_CLK_MODE = "IMUX_CLK_MODE";
    public static final String ATTR_FF_CLK_EN = "FF_CLK_EN";
    public static final String ATTR_FF_CLK_DUAL = "FF_CLK_DUAL";

    public static final String ATTR_INIT_TAPS = "INIT_TAPS";
    public static final String ATTR_ENABLE_OPT_DEL = "ENABLE_OPT_DEL";
    private static final String[] GCLK_IGNORE_ATTRS = {
            "IGNORE_TOP", "IGNORE_BOT", "IGNORE_LEFT", "IGNORE_RIGHT" };

    public static final int MAX_TAPS = 15;

    /**
     * Programs the leaf clock delay of a single SLICE: all flops in the slice
     * receive the given clock net delayed by {@code taps} (4-bit tap value,
     * ~65-70ps/tap).
     *
     * @param design The design to annotate.
     * @param clk    The (global clock) net the delay applies to.
     * @param slice  A SLICE site carrying loads of {@code clk}.
     * @param taps   Delay tap value, 0..15.
     */
    public static void setLeafClockDelay(Design design, Net clk, Site slice, int taps) {
        if (taps < 0 || taps > MAX_TAPS) {
            throw new IllegalArgumentException("Invalid CLK_DLY_VAL taps " + taps
                    + " (0.." + MAX_TAPS + ") for " + slice);
        }
        SiteTypeEnum type = slice.getSiteTypeEnum();
        if (type != SiteTypeEnum.SLICEL && type != SiteTypeEnum.SLICEM) {
            throw new IllegalArgumentException("Leaf clock delay requires a SLICE site, got "
                    + slice + " (" + type + ")");
        }
        BEL bel = slice.getBEL(FF_CLK_MOD);
        if (bel == null) {
            throw new IllegalStateException("No " + FF_CLK_MOD + " BEL on " + slice);
        }
        design.addBELAttr(clk, slice, type, bel, ATTR_FF_CLK_DUAL, "TRUE");
        design.addBELAttr(clk, slice, type, bel, ATTR_CLK_DLY_VAL, "4'h" + Integer.toHexString(taps));
        design.addBELAttr(clk, slice, type, bel, ATTR_IMUX_CLK_MODE, "DELAY");
        design.addBELAttr(clk, slice, type, bel, ATTR_FF_CLK_EN, "TRUE");
    }

    /**
     * Programs leaf clock delays for a set of SLICE sites.
     *
     * @param design The design to annotate.
     * @param clk    The (global clock) net the delays apply to.
     * @param taps   Map of SLICE site to CLK_DLY_VAL tap value (0..15).
     */
    public static void setLeafClockDelays(Design design, Net clk, Map<Site, Integer> taps) {
        for (Entry<Site, Integer> e : taps.entrySet()) {
            setLeafClockDelay(design, clk, e.getKey(), e.getValue());
        }
    }

    /**
     * Arms Vivado's optimized delay calculation on the GCLK_DELAY_SSIT
     * distribution delay sites of the tiles the given clock net's routing
     * passes through (matching what Vivado's router emits alongside its leaf
     * clock delay optimization). Idempotent per site.
     *
     * @param design The design to annotate.
     * @param clk    The routed global clock net.
     * @return Number of GCLK_DELAY sites armed.
     */
    public static int enableOptimizedDelay(Design design, Net clk) {
        Set<Tile> tiles = new HashSet<>();
        for (PIP p : clk.getPIPs()) {
            tiles.add(p.getTile());
        }
        Set<Site> armed = new HashSet<>();
        for (Tile t : tiles) {
            for (Site s : t.getSites()) {
                SiteTypeEnum type = s.getSiteTypeEnum();
                if (type != SiteTypeEnum.GCLK_DELAY_SSIT && type != SiteTypeEnum.GCLK_DELAY
                        && type != SiteTypeEnum.GCLK_DELAY_SSIT2) {
                    continue;
                }
                // Only arm sites whose delay element is on this net's route.
                if (!siteTouchesNet(s, clk)) {
                    continue;
                }
                if (!armed.add(s)) {
                    continue;
                }
                enableOptimizedDelay(design, clk, s);
            }
        }
        return armed.size();
    }

    /**
     * Arms Vivado's optimized delay calculation on one GCLK_DELAY site.
     */
    public static void enableOptimizedDelay(Design design, Net clk, Site site) {
        SiteTypeEnum type = site.getSiteTypeEnum();
        BEL bel = site.getBEL(type.name());
        if (bel == null) {
            BEL[] bels = site.getBELs();
            if (bels == null || bels.length == 0) {
                throw new IllegalStateException("No BELs on " + site);
            }
            bel = bels[0];
        }
        design.addBELAttr(clk, site, type, bel, ATTR_INIT_TAPS, "0");
        for (String attr : GCLK_IGNORE_ATTRS) {
            design.addBELAttr(clk, site, type, bel, attr, "TRUE");
        }
        design.addBELAttr(clk, site, type, bel, ATTR_ENABLE_OPT_DEL, "TRUE");
    }

    /**
     * Returns true if any of the site's pins connects to a node used by the
     * net's routing.
     */
    private static boolean siteTouchesNet(Site site, Net net) {
        Set<com.xilinx.rapidwright.device.Node> netNodes = new HashSet<>();
        for (PIP p : net.getPIPs()) {
            netNodes.add(p.getStartNode());
            netNodes.add(p.getEndNode());
        }
        for (int i = 0; i < site.getSitePinCount(); i++) {
            com.xilinx.rapidwright.device.Node n = site.getConnectedNode(i);
            if (n != null && netNodes.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copies all BEL attributes associated with the named net from one design
     * to another (e.g. to transplant a Vivado-computed deskew solution onto a
     * RapidWright-routed design with identical placement).
     *
     * @return Number of attributes copied.
     */
    public static int copyBELAttrs(Design src, Design dst, String netName) {
        Net dstNet = dst.getNet(netName);
        if (dstNet == null) {
            throw new IllegalArgumentException("Net " + netName + " not found in destination design");
        }
        int count = 0;
        Map<Site, SiteConfig> attrs = src.getBELAttrs();
        if (attrs == null) {
            return 0;
        }
        for (Entry<Site, SiteConfig> e : attrs.entrySet()) {
            SiteConfig sc = e.getValue();
            Map<BEL, Map<String, BELAttr>> belMap = sc.getBELAttributes();
            if (belMap == null) {
                continue;
            }
            for (Entry<BEL, Map<String, BELAttr>> be : belMap.entrySet()) {
                for (Entry<String, BELAttr> ae : be.getValue().entrySet()) {
                    BELAttr a = ae.getValue();
                    if (a.getNet() == null || !netName.equals(a.getNet().getName())) {
                        continue;
                    }
                    dst.addBELAttr(dstNet, e.getKey(), sc.getSiteTypeEnum(), be.getKey(),
                            a.getName(), a.getValue());
                    count++;
                }
            }
        }
        return count;
    }
}
