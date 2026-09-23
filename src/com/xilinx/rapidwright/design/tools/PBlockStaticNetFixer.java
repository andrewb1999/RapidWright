/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt, AMD Advanced Research and Development.
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

package com.xilinx.rapidwright.design.tools;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.xdc.ConstraintTools;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.rwroute.PartialRouter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Contains the static-net (VCC/GND) routing of a Vivado-routed precompile checkpoint inside its
 * pblock, so the module template can be stamped next to other modules without its constant
 * routing landing in their footprints. Vivado routes constants from tie-off sources anywhere on
 * the device, pblock or not, so the routing is rebuilt: every static route is dropped and every
 * in-pblock sink is re-routed by RWRoute with the pblock as its routing region, from tie-offs
 * inside it. Two kinds of route keep Vivado's version instead:
 * <ul>
 * <li>routes into NoC site pins ({@code NOC_NSU512}, {@code NOC_NMU512}): RWRoute cannot route a
 * static net into one, and such a pin also reports unrouted even when it is;</li>
 * <li>routes into other hard-block pins (DSP, RAM) that stay inside the pblock: RWRoute would
 * drive a GND pin there as VCC through the block's site inverter, and that pin is lost in the
 * checkpoint round trip;</li>
 * <li>routes to a sink RWRoute cannot reach inside the pblock, when Vivado's route to it stays
 * inside the pblock: restored, and reported. A sink whose only access is from outside (a pin at
 * the pblock edge fed through the neighbouring column) is left unrouted for the array-level
 * router, which is not confined.</li>
 * </ul>
 * The routes into NoC pins may leave the pblock by a few tiles; they are part of the module's footprint.
 * Out-of-pblock sinks (the inline-flop harness) are left unrouted; the harness is removed
 * afterwards. Static-source site instances outside the pblock are removed unless a kept route
 * starts at one.
 */
public class PBlockStaticNetFixer {

    private static boolean isNocSite(SitePinInst spi) {
        SiteInst si = spi.getSiteInst();
        return si != null && si.getSite() != null && si.getSite().getSiteTypeEnum().name().startsWith("NOC_");
    }

    /** A pin of a hard block (DSP, RAM, NoC, ...): anything but a slice. */
    private static boolean isHardBlockSite(SitePinInst spi) {
        SiteInst si = spi.getSiteInst();
        return si != null && si.getSite() != null && !si.getSite().getSiteTypeEnum().name().startsWith("SLICE");
    }

    private static Node connectedNode(SitePinInst spi) {
        try {
            return spi.getConnectedNode();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The original route into {@code sink}, walked back through {@code incoming} until a node that
     * the net currently drives ({@code driven}: end nodes of its present PIPs or source-pin nodes)
     * or the root of the original tree. Returns the PIPs to add; the root node is put in
     * {@code rootOut[0]} when the walk reached one.
     */
    private static List<PIP> chainTo(Node sink, Map<Node, PIP> incoming, Set<Node> driven, Node[] rootOut) {
        List<PIP> chain = new ArrayList<>();
        Set<Node> seen = new HashSet<>();
        Node node = sink;
        rootOut[0] = null;
        while (node != null && seen.add(node)) {
            PIP pip = incoming.get(node);
            if (pip == null) {
                rootOut[0] = node;
                break;
            }
            chain.add(pip);
            node = pip.getStartNode();
            if (driven.contains(node)) break;
        }
        return chain;
    }

    /**
     * Rebuilds the static-net routing inside the pblocks as described for the class, and removes
     * the out-of-pblock static sources that no kept route starts from.
     */
    public static void fix(Design design, Collection<PBlock> pblocks) {
        if (pblocks == null || pblocks.isEmpty()) {
            throw new IllegalArgumentException("At least one pblock is required");
        }
        Set<Tile> pblockTiles = pblockUnionTiles(pblocks);
        Net[] statics = {design.getGndNet(), design.getVccNet()};
        // The static sinks the array-level router will see (RWRoute.preprocess): a LUT input tied
        // to a constant is not always a site pin in Vivado's checkpoint, and one found only later
        // would find its bounce node taken by the other static net's re-route here. And the
        // unrouted signal nets (port nets whose proxy flops were removed) get their sink site pins,
        // so that the router preserves the way into them.
        DesignTools.createPossiblePinsToStaticNets(design);
        DesignTools.createMissingSitePinInsts(design);

        // Per net: Vivado's routing, indexed by end node, and the site instances that source it.
        Map<Net, Map<Node, PIP>> incomingByNet = new HashMap<>();
        Map<Net, Map<Node, SiteInst>> sourceByNode = new HashMap<>();
        Set<SiteInst> keepSources = new HashSet<>();
        List<SitePinInst> orphans = new ArrayList<>();
        Map<Net, List<SitePinInst>> candidates = new HashMap<>();
        for (Net net : statics) {
            if (net == null) continue;
            Map<Node, PIP> incoming = new HashMap<>();
            for (PIP pip : net.getPIPs()) {
                Node end = pip.getEndNode();
                if (end != null) incoming.put(end, pip);
            }
            incomingByNet.put(net, incoming);
            Map<Node, SiteInst> sources = new HashMap<>();
            for (SitePinInst spi : net.getPins()) {
                if (spi.isOutPin()) {
                    Node n = connectedNode(spi);
                    if (n != null && spi.getSiteInst() != null) sources.put(n, spi.getSiteInst());
                }
            }
            sourceByNode.put(net, sources);

            // 1. Keep the routes into NoC pins, whole chains back to their roots, and the routes into
            // other hard-block pins (DSP, RAM) when they stay inside the pblock: RWRoute would drive
            // such a GND pin as VCC through the block's site inverter, and that pin does not survive
            // the checkpoint round trip (the DSP58 ASYNC_RST pins of the GEMM tile were lost that way).
            Set<PIP> keep = new LinkedHashSet<>();
            Set<Node> keptSinks = new HashSet<>();
            int nocPins = 0, hardPins = 0;
            for (SitePinInst spi : net.getPins()) {
                if (spi.isOutPin() || !isHardBlockSite(spi)) continue;
                Node n = connectedNode(spi);
                if (n == null) continue;
                Node[] root = new Node[1];
                List<PIP> chain = chainTo(n, incoming, new HashSet<>(), root);
                boolean noc = isNocSite(spi);
                if (!noc) {
                    boolean inside = !chain.isEmpty();
                    for (PIP pip : chain) {
                        if (!pblockTiles.contains(pip.getTile())) { inside = false; break; }
                    }
                    if (inside && root[0] != null && !pblockTiles.contains(root[0].getTile())) inside = false;
                    if (!inside) continue;
                    hardPins++;
                } else {
                    nocPins++;
                }
                keep.addAll(chain);
                keptSinks.add(n);
                SiteInst src = root[0] == null ? null : sources.get(root[0]);
                if (src != null) keepSources.add(src);
            }
            // 2. Drop everything else.
            int before = net.getPIPs().size();
            net.setPIPs(new ArrayList<>(keep));
            System.out.println("** PBlockStaticNetFixer: " + net.getName() + ": dropped " + (before - keep.size())
                    + " of " + before + " PIPs" + (keep.isEmpty() ? "" : ", kept " + keep.size() + " on the routes into "
                    + nocPins + " NoC site pins and " + hardPins + " other hard-block pins"));

            // 3. Every in-pblock sink not on a kept route is re-routed; out-of-pblock ones are left.
            Set<Node> driven = new HashSet<>();
            for (PIP pip : keep) driven.add(pip.getEndNode());
            List<SitePinInst> mine = new ArrayList<>();
            int outOfPblock = 0;
            for (SitePinInst spi : net.getPins()) {
                if (spi.isOutPin() || isNocSite(spi)) continue;
                Node n = connectedNode(spi);
                if (n != null && (driven.contains(n) || keptSinks.contains(n))) continue;
                SiteInst si = spi.getSiteInst();
                Tile t = (si != null && si.getSite() != null) ? si.getSite().getTile() : null;
                if (t != null && pblockTiles.contains(t)) mine.add(spi);
                else outOfPblock++;
            }
            candidates.put(net, mine);
            orphans.addAll(mine);
            System.out.println("** PBlockStaticNetFixer: " + net.getName() + ": " + mine.size() + " in-pblock sinks to route, "
                    + outOfPblock + " out-of-pblock sinks left unrouted");
        }

        // 4. Re-route inside the pblock.
        if (!orphans.isEmpty()) {
            String pblockString = unionPBlockString(pblocks);
            System.out.println("** PBlockStaticNetFixer: running PartialRouter with --pblock \"" + pblockString + "\" on "
                    + orphans.size() + " static sinks");
            PartialRouter.routeDesignWithUserDefinedArguments(design,
                    new String[] {"--pblock", pblockString, "--useUTurnNodes", "--nonTimingDriven"},
                    orphans, /*softPreserve=*/ false);
        }

        // 5. A sink RWRoute could not reach keeps Vivado's route, joined to the current routing,
        // when that route stays inside the pblock; otherwise it is left for the array-level router,
        // which is not confined (a route outside the footprint would land in a neighbouring module).
        Map<Net, Set<Node>> drivenByNet = new HashMap<>();
        for (Net net : statics) {
            if (net == null) continue;
            Set<Node> driven = new HashSet<>();
            for (PIP pip : net.getPIPs()) driven.add(pip.getEndNode());
            for (SitePinInst spi : net.getPins()) {
                if (spi.isOutPin()) {
                    Node n = connectedNode(spi);
                    if (n != null) driven.add(n);
                }
            }
            drivenByNet.put(net, driven);
        }
        for (Net net : statics) {
            if (net == null) continue;
            Set<Node> driven = drivenByNet.get(net);
            List<PIP> restored = new ArrayList<>();
            List<String> restoredPins = new ArrayList<>();
            List<String> leftPins = new ArrayList<>();
            int movedBack = 0;
            for (SitePinInst spi : candidates.get(net)) {
                Node n = connectedNode(spi);
                if (n == null) continue;
                Net now = spi.getNet();
                // RWRoute may have moved a GND pin to VCC (inverted at the LUT); routed there, it is done.
                if (now != null && now != net && drivenByNet.containsKey(now) && drivenByNet.get(now).contains(n)) continue;
                if (now != net) {
                    // Moved but not routed: a pin off its net is dropped when the checkpoint is
                    // written, so put it back before restoring its route or leaving it.
                    if (now != null) now.removePin(spi, true);
                    net.addPin(spi);
                    movedBack++;
                }
                if (driven.contains(n)) continue;
                String pinName = spi.getSiteInst().getSiteName() + "." + spi.getName();
                Node[] root = new Node[1];
                List<PIP> chain = chainTo(n, incomingByNet.get(net), driven, root);
                boolean inside = !chain.isEmpty();
                for (PIP pip : chain) {
                    if (!pblockTiles.contains(pip.getTile())) { inside = false; break; }
                }
                if (inside && root[0] != null && !pblockTiles.contains(root[0].getTile())) inside = false;
                if (!inside) {
                    leftPins.add(pinName);
                    continue;
                }
                restored.addAll(chain);
                for (PIP pip : chain) driven.add(pip.getEndNode());
                restoredPins.add(pinName);
                SiteInst src = root[0] == null ? null : sourceByNode.get(net).get(root[0]);
                if (src != null) keepSources.add(src);
            }
            if (!restored.isEmpty()) {
                List<PIP> pips = new ArrayList<>(net.getPIPs());
                pips.addAll(restored);
                net.setPIPs(pips);
                System.out.println("** PBlockStaticNetFixer: " + net.getName() + ": RWRoute could not reach "
                        + restoredPins.size() + " sinks; Vivado's in-pblock routes to them restored ("
                        + restored.size() + " PIPs): " + restoredPins);
            }
            if (!leftPins.isEmpty()) {
                System.out.println("** PBlockStaticNetFixer: " + net.getName() + ": " + leftPins.size()
                        + " sinks unreachable inside the pblock, left for the array-level router: " + leftPins);
            }
            if (movedBack > 0) {
                System.out.println("** PBlockStaticNetFixer: " + net.getName() + ": " + movedBack
                        + " sinks RWRoute had moved to the other static net without routing them put back");
            }
        }

        // 6. Remove the out-of-pblock static sources no kept route starts from. Removing a source
        // pin unroutes its net in RapidWright, so the routing is put back afterwards.
        List<SiteInst> toRemove = new ArrayList<>();
        for (Net net : statics) {
            if (net == null) continue;
            for (SitePinInst spi : net.getPins()) {
                if (!spi.isOutPin()) continue;
                SiteInst si = spi.getSiteInst();
                if (si == null || si.getSite() == null || pblockTiles.contains(si.getSite().getTile())) continue;
                if (keepSources.contains(si) || toRemove.contains(si)) continue;
                toRemove.add(si);
            }
        }
        Map<Net, List<PIP>> snapshot = new HashMap<>();
        for (Net net : statics) if (net != null) snapshot.put(net, new ArrayList<>(net.getPIPs()));
        for (SiteInst si : toRemove) {
            for (SitePinInst pin : new ArrayList<>(si.getSitePinInsts())) {
                if (pin.getNet() != null) pin.getNet().removePin(pin, true);
            }
            design.removeSiteInst(si);
        }
        for (Map.Entry<Net, List<PIP>> e : snapshot.entrySet()) {
            if (e.getKey().getPIPs().size() != e.getValue().size()) e.getKey().setPIPs(e.getValue());
        }
        if (!toRemove.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (SiteInst si : toRemove) names.add(si.getSiteName());
            System.out.println("** PBlockStaticNetFixer: removed " + toRemove.size() + " out-of-pblock static source SiteInsts: " + names);
        }
        List<String> kept = new ArrayList<>();
        for (SiteInst si : keepSources) if (!pblockTiles.contains(si.getSite().getTile())) kept.add(si.getSiteName());
        if (!kept.isEmpty()) {
            System.out.println("** PBlockStaticNetFixer: kept " + kept.size() + " out-of-pblock static sources that start kept routes: " + kept);
        }
        DesignTools.updatePinsIsRouted(design);
    }

    /**
     * Reads pblocks from the design's XDC and runs {@link #fix(Design, Collection)}.
     */
    public static void fix(Design design) {
        Map<String, PBlock> pblockMap = ConstraintTools.getPBlocksFromXDC(design);
        if (pblockMap.isEmpty()) {
            throw new RuntimeException("No pblocks found in design XDC");
        }
        System.out.println("** PBlockStaticNetFixer: found " + pblockMap.size() + " pblock(s) in XDC: "
                + pblockMap.keySet());
        fix(design, pblockMap.values());
    }

    /**
     * Reparents SitePinInsts on physical-only "static-tie" Nets — Vivado /
     * RapidWright per-cell internal constant nets that come back from
     * Module relocation or readCheckpoint as orphans named like
     * {@code <cellPath>/VCC_1} / {@code <cellPath>/GND_0} with no EDIF
     * logical net — onto the design's actual VCC/GND nets, then drops the
     * orphan Nets. Safe to call multiple times in a flow; orphans tend to
     * appear during ModuleInst placement and again after RWRoute's
     * {@code createPossiblePinsToStaticNets} materialization.
     *
     * @return Number of SitePinInsts moved.
     */
    public static int mergeOrphanStaticTieNetsIntoGlobals(Design design) {
        Net vcc = design.getVccNet();
        Net gnd = design.getGndNet();
        com.xilinx.rapidwright.edif.EDIFNetlist netlist = design.getNetlist();

        java.util.List<Net> orphans = new java.util.ArrayList<>();
        for (Net n : design.getNets()) {
            if (n == vcc || n == gnd) continue;
            if (netlist.getHierNetFromName(n.getName()) != null) continue;
            String simpleTail = n.getName().substring(n.getName().lastIndexOf('/') + 1);
            if (!simpleTail.matches("VCC(_\\d+)?|GND(_\\d+)?")) continue;
            orphans.add(n);
        }

        int moved = 0;
        for (Net orphan : orphans) {
            String simpleTail = orphan.getName().substring(orphan.getName().lastIndexOf('/') + 1);
            Net target = simpleTail.startsWith("VCC") ? vcc : gnd;
            for (SitePinInst spi : new ArrayList<>(orphan.getPins())) {
                orphan.removePin(spi);
                target.addPin(spi);
                moved++;
            }
            design.removeNet(orphan);
        }
        return moved;
    }

    /**
     * The pblocks' ranges with every {@code SLICE_XaYb:SLICE_XcYd} rectangle grown by {@code margin}
     * slices on each side (clamped to existing sites) and the IRI_QUAD interconnect sites of the
     * grown rectangle added; all other ranges (NoC units, RAMs, DSPs) verbatim.
     */
    public static String expandedPBlockString(com.xilinx.rapidwright.device.Device device, Collection<PBlock> pblocks, int margin) {
        java.util.regex.Pattern slice = java.util.regex.Pattern.compile("SLICE_X(\\d+)Y(\\d+):SLICE_X(\\d+)Y(\\d+)");
        StringBuilder sb = new StringBuilder();
        for (PBlock p : pblocks) {
            for (String range : p.toString().split(" ")) {
                java.util.regex.Matcher m = slice.matcher(range);
                if (sb.length() > 0) sb.append(' ');
                if (!m.matches()) {
                    if (!range.startsWith("IRI_QUAD")) sb.append(range);   // re-derived below
                    else sb.setLength(Math.max(0, sb.length() - 1));
                    continue;
                }
                int x0 = Integer.parseInt(m.group(1)), y0 = Integer.parseInt(m.group(2));
                int x1 = Integer.parseInt(m.group(3)), y1 = Integer.parseInt(m.group(4));
                int lo = Math.min(x0, x1), hi = Math.max(x0, x1), bot = Math.min(y0, y1), top = Math.max(y0, y1);
                int gx0 = lo - margin, gx1 = hi + margin, gy0 = bot - margin, gy1 = top + margin;
                while (gx0 < lo && device.getSite("SLICE_X" + gx0 + "Y" + bot) == null) gx0++;
                while (gx1 > hi && device.getSite("SLICE_X" + gx1 + "Y" + bot) == null) gx1--;
                while (gy0 < bot && device.getSite("SLICE_X" + lo + "Y" + gy0) == null) gy0++;
                while (gy1 > top && device.getSite("SLICE_X" + lo + "Y" + gy1) == null) gy1--;
                Tile t1 = device.getSite("SLICE_X" + gx0 + "Y" + gy0).getTile(), t2 = device.getSite("SLICE_X" + gx1 + "Y" + gy1).getTile();
                int ix0 = Integer.MAX_VALUE, ix1 = -1, iy0 = Integer.MAX_VALUE, iy1 = -1;
                for (int r = Math.min(t1.getRow(), t2.getRow()); r <= Math.max(t1.getRow(), t2.getRow()); r++) {
                    for (int c = Math.min(t1.getColumn(), t2.getColumn()); c <= Math.max(t1.getColumn(), t2.getColumn()); c++) {
                        Tile t = device.getTile(r, c);
                        if (t == null) continue;
                        for (com.xilinx.rapidwright.device.Site st : t.getSites()) {
                            if (!st.getSiteTypeEnum().name().startsWith("IRI_QUAD")) continue;
                            ix0 = Math.min(ix0, st.getInstanceX()); ix1 = Math.max(ix1, st.getInstanceX());
                            iy0 = Math.min(iy0, st.getInstanceY()); iy1 = Math.max(iy1, st.getInstanceY());
                        }
                    }
                }
                sb.append("SLICE_X" + gx0 + "Y" + gy0 + ":SLICE_X" + gx1 + "Y" + gy1);
                if (ix1 >= 0) sb.append(" IRI_QUAD_X" + ix0 + "Y" + iy0 + ":IRI_QUAD_X" + ix1 + "Y" + iy1);
            }
        }
        return sb.toString();
    }

    private static Set<Tile> pblockUnionTiles(Collection<PBlock> pblocks) {
        Set<Tile> tiles = new HashSet<>();
        for (PBlock p : pblocks) {
            tiles.addAll(p.getAllTiles());
        }
        return tiles;
    }

    /**
     * Concatenates each pblock's range string with spaces, the format the
     * {@code PBlock(Device, String)} constructor expects.
     */
    public static String unionPBlockString(Collection<PBlock> pblocks) {
        StringBuilder sb = new StringBuilder();
        for (PBlock p : pblocks) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(p.toString());
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("USAGE: <input.dcp> <output.dcp>");
            System.out.println("  Reads pblocks from the input DCP's XDC, strips out-of-pblock static-net");
            System.out.println("  (VCC/GND) PIPs and STATIC_SOURCE SiteInsts, then reroutes only the");
            System.out.println("  in-pblock sinks left orphaned via PartialRouter --pblock. Vivado's");
            System.out.println("  complete in-pblock static routing is preserved. Out-of-pblock sinks");
            System.out.println("  (e.g. inline-flop control pins) are left unrouted.");
            System.exit(1);
        }
        String inputDcp = args[0];
        String outputDcp = args[1];

        System.out.println("** PBlockStaticNetFixer: reading " + inputDcp);
        Design design = Design.readCheckpoint(inputDcp);

        fix(design);

        System.out.println("** PBlockStaticNetFixer: removing inline flops");
        InlineFlopTools.removeInlineFlops(design);

        System.out.println("** PBlockStaticNetFixer: removing BUFGs");
        ArrayBuilder.removeBUFGs(design);

        System.out.println("** PBlockStaticNetFixer: writing " + outputDcp);
        design.writeCheckpoint(outputDcp);
        System.out.println("** PBlockStaticNetFixer: done");
    }
}
