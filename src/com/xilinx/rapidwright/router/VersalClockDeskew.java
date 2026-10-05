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
import java.util.ArrayList;
import java.util.List;
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
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Node;
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

    /**
     * Largest CLK_DLY_VAL Vivado honours. The field is 4 bits, but measured on xcv80 (Vivado
     * 2026.1) the timer credits 0..8 taps linearly (about 50 ps each above the DELAY-mode base),
     * 9 adds only ~15 ps more, and 10..15 are silently timed as if the slice were in BYPASS:
     * not even the DELAY-mode base delay is applied.
     */
    public static final int MAX_TAPS = 8;

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

    // ------------------------------------------------------------------ DSP58 clock delay
    /**
     * A DSP58's clock comes through the IRI_QUAD site of its interface tile (CLK -> CLKINV ->
     * IRI_FF_CLK_MOD -> CLK_O -> the DSP's CLK pin), whose clock modifier delays it as a slice's
     * FF_CLK_MOD does a slice's: CLK_SEL = DLYD_CLK with CLK_DLY_VAL_COE taps. Vivado reads the
     * setting from the checkpoint's BEL attributes (a Tcl set_property on the BEL is ignored).
     */
    public static final String IRI_FF_CLK_MOD = "IRI_FF_CLK_MOD";
    public static final String ATTR_IRI_CLK_SEL = "CLK_SEL";
    public static final String ATTR_IRI_CLK_DLY_VAL = "CLK_DLY_VAL_COE";
    public static final String ATTR_IRI_CLK_EN = "CLK_EN";
    /** Largest CLK_DLY_VAL_COE Vivado times: 10..15 add nothing, not even the delayed clock's base. */
    public static final int IRI_MAX_TAPS = 9;
    /**
     * Clock delay the delayed clock adds at the DSP58's clock pin, by tap value 0..9, at slow max,
     * slow min, fast max and fast min, ps: Vivado's clock net delay to the pin against the
     * unconfigured modifier, the same to 1 ps on every DSP58 measured (the GEMM tile's SLR crossing,
     * 32 DSP58s, each value on two).
     */
    public static final float[][] IRI_DELAY_PS = {
        {152, 163, 108, 110}, {196, 201, 144, 136}, {252, 250, 182, 170}, {302, 290, 214, 198}, {358, 341, 252, 232},
        {406, 381, 285, 258}, {458, 427, 316, 292}, {508, 467, 350, 318}, {558, 513, 393, 350}, {608, 553, 427, 378}};

    /** Whether the site is a DSP58 whose clock a {@link #IRI_FF_CLK_MOD} can delay. */
    public static boolean isDspSite(Site site) {
        return site != null && site.getSiteTypeEnum() == SiteTypeEnum.DSP58_PRIMARY;
    }

    /** Whether the site is a block RAM (RAMB36, RAMB18_L, RAMB18_U), whose clock pins each come through an {@link #IRI_FF_CLK_MOD} too. */
    public static boolean isBramSite(Site site) {
        return site != null && site.getSiteTypeEnum().name().startsWith("RAMB");
    }

    /**
     * Whether the site's clock comes through interface-tile clock modifiers: a DSP58 (one, on CLK) or
     * a block RAM (one per clock pin: CLKARDCLK and CLKBWRCLK, each L and U on a RAMB36). The delays
     * are the same ({@link #IRI_DELAY_PS}, to 1 ps on the mesh tile's 8 RAMB36s), and Vivado's router
     * sets them itself on block RAMs (the mesh tile's crossing came with two on tap 0, 152 ps).
     */
    public static boolean isIriClockedSite(Site site) {
        return isDspSite(site) || isBramSite(site) || isUramSite(site);
    }

    /** Whether the site is an UltraRAM (URAM288), whose CLK comes through an {@link #IRI_FF_CLK_MOD} as a DSP58's does. */
    public static boolean isUramSite(Site site) {
        return site != null && site.getSiteTypeEnum().name().startsWith("URAM288");
    }

    /** The site's clock input pins that come through a clock modifier. */
    public static List<String> getIriClockPins(Site block) {
        List<String> pins = new ArrayList<>();
        if (isDspSite(block) || isUramSite(block)) {
            pins.add("CLK");
        } else if (isBramSite(block)) {
            for (int i = 0; i < block.getSitePinCount(); i++) {
                String name = block.getPinName(i);
                if (name.startsWith("CLK") && block.isInputPin(name)) pins.add(name);
            }
        }
        return pins;
    }

    /** The IRI_QUAD site whose clock modifier feeds the site's clock pin {@code pinName}, or null. */
    public static Site getIriClockSite(Site block, String pinName) {
        Node pin = block.getConnectedNode(pinName);
        if (pin == null) return null;
        for (Node o : pin.getAllUphillNodes()) {
            for (Node p : o.getAllUphillNodes()) {
                SitePin sp = p.getSitePin();
                if (sp != null && sp.getPinName().equals("CLK_O") && sp.getSite().getBEL(IRI_FF_CLK_MOD) != null) return sp.getSite();
            }
        }
        return null;
    }

    /** The IRI_QUAD site whose clock modifier feeds the site's (first) clock pin, or null. */
    public static Site getIriClockSite(Site block) {
        for (String pin : getIriClockPins(block)) {
            Site iri = getIriClockSite(block, pin);
            if (iri != null) return iri;
        }
        return null;
    }

    /** The IRI_QUAD sites in front of the site's clock pins. */
    public static List<Site> getIriClockSites(Site block) {
        List<Site> sites = new ArrayList<>();
        for (String pin : getIriClockPins(block)) {
            Site iri = getIriClockSite(block, pin);
            if (iri != null && !sites.contains(iri)) sites.add(iri);
        }
        return sites;
    }

    /**
     * The DSP58 or block RAM site whose clock pin the IRI_QUAD site's CLK_O drives, or null. A block
     * RAM tile's pins belong to its RAMB36 and to its two RAMB18s: the one the design uses, when
     * {@code design} is given and uses one.
     */
    public static Site getBlockSiteOfIri(Design design, Site iri) {
        Node o = iri.getConnectedNode("CLK_O");
        if (o == null) return null;
        Site first = null;
        for (Node n : o.getAllDownhillNodes()) {
            for (Node pin : n.getAllDownhillNodes()) {
                for (Site s : pin.getTile().getSites()) {
                    if (!isIriClockedSite(s)) continue;
                    for (String name : getIriClockPins(s)) {
                        if (!pin.equals(s.getConnectedNode(name))) continue;
                        if (design == null || design.getSiteInstFromSite(s) != null) return s;
                        if (first == null) first = s;
                    }
                }
            }
        }
        return first;
    }

    /** As {@link #getBlockSiteOfIri(Design, Site)} without a design. */
    public static Site getDspSiteOfIri(Site iri) {
        return getBlockSiteOfIri(null, iri);
    }

    /** Delays the DSP58's or block RAM's clock (every clock pin alike) by {@code taps} (0..{@link #IRI_MAX_TAPS}) of its interface tiles' clock modifiers. */
    public static void setDspClockDelay(Design design, Net clk, Site block, int taps) {
        if (taps < 0 || taps > IRI_MAX_TAPS) throw new IllegalArgumentException("Invalid " + ATTR_IRI_CLK_DLY_VAL + " taps " + taps + " (0.." + IRI_MAX_TAPS + ") for " + block);
        List<Site> iris = getIriClockSites(block);
        if (iris.isEmpty()) throw new IllegalArgumentException("No IRI clock modifier in front of " + block);
        for (Site iri : iris) {
            BEL bel = iri.getBEL(IRI_FF_CLK_MOD);
            SiteTypeEnum type = iri.getSiteTypeEnum();
            design.addBELAttr(clk, iri, type, bel, ATTR_IRI_CLK_SEL, "DLYD_CLK");
            design.addBELAttr(clk, iri, type, bel, ATTR_IRI_CLK_DLY_VAL, "4'h" + Integer.toHexString(taps));
            design.addBELAttr(clk, iri, type, bel, ATTR_IRI_CLK_EN, "TRUE");
        }
    }

    /** The taps the modifier in IRI_QUAD site {@code iri} delays its clock by, or -1 when it is not in the delayed mode. */
    private static int getIriDelay(Design design, Site iri) {
        Map<Site, SiteConfig> attrs = design.getBELAttrs();
        SiteConfig sc = iri == null || attrs == null ? null : attrs.get(iri);
        if (sc == null) return -1;
        BEL bel = iri.getBEL(IRI_FF_CLK_MOD);
        BELAttr sel = sc.getBELAttribute(bel, ATTR_IRI_CLK_SEL);
        if (sel == null || !"DLYD_CLK".equals(sel.getValue())) return -1;
        BELAttr val = sc.getBELAttribute(bel, ATTR_IRI_CLK_DLY_VAL);
        return val == null ? 0 : parseTapValue(val.getValue());
    }

    /**
     * The taps the DSP58's or block RAM's clock is delayed by, or -1 when no modifier of its is in the
     * delayed mode; the most among a block RAM's pins when they differ (Vivado sets them one by one).
     */
    public static int getDspClockDelay(Design design, Site block) {
        Map<Site, SiteConfig> attrs = design.getBELAttrs();
        if (attrs == null || attrs.isEmpty()) return -1;
        int taps = -1;
        for (Site iri : getIriClockSites(block)) taps = Math.max(taps, getIriDelay(design, iri));
        return taps;
    }

    /** Takes the DSP58's or block RAM's clock modifiers back to their unconfigured state. */
    public static void clearDspClockDelay(Design design, Site block) {
        Map<Site, SiteConfig> attrs = design.getBELAttrs();
        if (attrs == null) return;
        for (Site iri : getIriClockSites(block)) {
            SiteConfig sc = attrs.get(iri);
            if (sc == null) continue;
            BEL bel = iri.getBEL(IRI_FF_CLK_MOD);
            for (String attr : new String[] {ATTR_IRI_CLK_SEL, ATTR_IRI_CLK_DLY_VAL, ATTR_IRI_CLK_EN}) sc.removeBELAttribute(bel, attr);
        }
    }

    /** The extra clock delay (4 corners, ps) the DSP58's programmed modifier adds, or null when it adds none. */
    public static float[] getDspClockDelayPs(Design design, Site dsp) {
        int taps = getDspClockDelay(design, dsp);
        return taps < 0 || taps > IRI_MAX_TAPS ? null : IRI_DELAY_PS[taps];
    }

    /** The extra clock delay (4 corners, ps) the programmed modifier in front of the site's clock pin {@code pinName} adds, or null when it adds none. */
    public static float[] getIriClockDelayPs(Design design, Site block, String pinName) {
        Map<Site, SiteConfig> attrs = design.getBELAttrs();
        if (attrs == null || attrs.isEmpty()) return null;
        int taps = getIriDelay(design, getIriClockSite(block, pinName));
        return taps < 0 || taps > IRI_MAX_TAPS ? null : IRI_DELAY_PS[taps];
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
     * Reads back the leaf clock delay currently programmed on a SLICE, or 0 if
     * none is set. Needed to accumulate taps across refinement passes, since a
     * timing report of an already-tapped design measures slack that includes
     * those taps.
     */
    public static int getLeafClockDelay(Design design, Site slice) {
        Map<Site, SiteConfig> attrs = design.getBELAttrs();
        if (attrs == null) {
            return 0;
        }
        SiteConfig sc = attrs.get(slice);
        if (sc == null) {
            return 0;
        }
        BEL bel = slice.getBEL(FF_CLK_MOD);
        if (bel == null) {
            return 0;
        }
        BELAttr attr = sc.getBELAttribute(bel, ATTR_CLK_DLY_VAL);
        if (attr == null) {
            return 0;
        }
        return parseTapValue(attr.getValue());
    }

    /**
     * Parses a Verilog-style 4-bit literal such as {@code 4'h3} into its
     * integer value.
     */
    public static int parseTapValue(String value) {
        if (value == null) {
            return 0;
        }
        int tick = value.indexOf('\'');
        String digits = tick == -1 ? value : value.substring(tick + 2);
        try {
            return Integer.parseInt(digits.trim(), 16);
        } catch (NumberFormatException e) {
            return 0;
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
