/*
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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.RelocatableTileRectangle;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.device.SLR;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.eco.ECOPlacementHelper;
import com.xilinx.rapidwright.eco.ECOTools;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.util.Pair;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import java.util.Set;
import java.util.stream.Collectors;

public class FlopTreeTools {

    private static final Set<SiteTypeEnum> VALID_CENTROID_SITE_TYPES = EnumSet.of(
            SiteTypeEnum.SLICEL, SiteTypeEnum.SLICEM);

    /**
     * Approximate tile-row distance spanned by a single SLL super-long-line on
     * Versal devices. Used by {@link #insertSourceChainToSLR} to place the bottom
     * SLR-crossing flop one SLL hop away from the top one so that the boundary
     * crossing uses a single SLL wire rather than a multi-hop detour.
     */
    private static final int SLL_WIRE_LENGTH_ROWS = 87;

    /**
     * Max acceptable tile-row distance from the SLR boundary for the
     * {@code slr_xing_top} flop. Constrains the spiral search so this flop
     * lands close enough to the boundary to use an SLL endpoint.
     */
    private static final int MAX_SLR_XING_TOP_FROM_BOUNDARY_ROWS = 30;

    private static class PortInstQuadrants implements Iterable<List<EDIFHierPortInst>> {
        List<EDIFHierPortInst> topLeft;
        List<EDIFHierPortInst> topRight;
        List<EDIFHierPortInst> bottomLeft;
        List<EDIFHierPortInst> bottomRight;

        PortInstQuadrants() {
            topLeft = new ArrayList<>();
            topRight = new ArrayList<>();
            bottomLeft = new ArrayList<>();
            bottomRight = new ArrayList<>();
        }

        @Override
        public @NotNull Iterator<List<EDIFHierPortInst>> iterator() {
            return Arrays.asList(topLeft, topRight, bottomLeft, bottomRight).iterator();
        }
    }

    private static Pair<Cell, Net> createAndPlaceFlopForTree(Design design, EDIFHierNet logicalNet, String newNetName,
                                                             Pair<Site, BEL> loc, EDIFHierNet clk,
                                                             List<EDIFHierPortInst> portInsts) {
        Cell flop = design.createAndPlaceCell(design.getTopEDIFCell(), newNetName, Unisim.FDRE, loc.getFirst(),
                loc.getSecond());
        Net net = design.createNet(newNetName);
        net.connect(flop, "Q");
        design.getGndNet().connect(flop, "R");
        design.getVccNet().connect(flop, "CE");
        EDIFHierCellInst flopHierCellInst = flop.getEDIFHierCellInst();
        EDIFHierPortInst clkHierPortInst = flopHierCellInst.getPortInst("C");
        if (clkHierPortInst == null) {
            clk.getNet().createPortInst("C", flopHierCellInst.getInst());
            clkHierPortInst = flopHierCellInst.getPortInst("C");
        }
        EDIFTools.connectPortInstsThruHier(clk, clkHierPortInst, newNetName + "_clk");
        EDIFNet origNet = logicalNet.getNet();
        ECOTools.disconnectNet(design, portInsts.stream().filter(EDIFHierPortInst::isInput).collect(Collectors.toList()));
        Map<EDIFHierNet, List<EDIFHierPortInst>> netToPortInsts = new HashMap<>();
        netToPortInsts.put(net.getLogicalHierNet(), portInsts);
        ECOTools.connectNet(design, netToPortInsts, null);
        Net origPhysNet = design.getNet(logicalNet.getHierarchicalNetName());
        if (origPhysNet == null) {
            if (origNet.isGND()) {
                origPhysNet = design.getGndNet();
            } else if (origNet.isVCC()) {
                origPhysNet = design.getVccNet();
            } else {
                origPhysNet = design.createNet(logicalNet);
            }
        }
        origPhysNet.connect(flop, "D");
        return new Pair<>(flop, net);
    }

    private static Site findCentroidOfPortInsts(Design design, List<EDIFHierPortInst> portInsts) {
        List<Point> points = new ArrayList<>();
        for (EDIFHierPortInst leafInst : portInsts) {
            Cell cell = design.getCell(leafInst.getFullHierarchicalInstName());
            if (cell != null && cell.isPlaced()) {
                Tile t = cell.getTile();
                Point p = new Point(t.getColumn(), t.getRow());
                points.add(p);
            }
        }

        return ECOPlacementHelper.getCentroidOfPoints(design.getDevice(), points, VALID_CENTROID_SITE_TYPES);
    }

    private static PortInstQuadrants splitPortInstsIntoQuadrants(Design design, List<EDIFHierPortInst> portInsts,
                                                                 Site centroid) {
        PortInstQuadrants quadrants = new PortInstQuadrants();

        Tile tile = centroid.getTile();
        int tileColumn = tile.getColumn();
        int tileRow = tile.getRow();

        for (EDIFHierPortInst portInst : portInsts) {
            Cell cell = design.getCell(portInst.getFullHierarchicalInstName());
            if (cell != null && cell.isPlaced()) {
                Tile t = cell.getTile();
                int portColumn = t.getColumn();
                int portRow = t.getRow();

                if (portColumn <= tileColumn && portRow < tileRow) {
                    quadrants.topLeft.add(portInst);
                } else if (portColumn < tileColumn) {
                    quadrants.bottomLeft.add(portInst);
                } else if (portColumn > tileColumn && portRow <= tileRow) {
                    quadrants.topRight.add(portInst);
                } else {
                    quadrants.bottomRight.add(portInst);
                }
            }
        }

        return quadrants;
    }

    private static Map<SLR, List<EDIFHierPortInst>> splitPortInstsBySLR(Design design,
                                                                        List<EDIFHierPortInst> portInsts) {
        Map<SLR, List<EDIFHierPortInst>> portInstMap = new HashMap<>();

        for (EDIFHierPortInst portInst : portInsts) {
            Cell cell = portInst.getPhysicalCell(design);
            if (cell == null || !cell.isPlaced()) {
                throw new RuntimeException("Port inst: " + portInst + " is not placed");
            }
            Tile t = cell.getTile();
            SLR slr = t.getSLR();
            portInstMap.computeIfAbsent(slr, k -> new ArrayList<>()).add(portInst);
        }

        return portInstMap;
    }

    /**
     * A no-go test over tiles from a list of rectangles: the tiles inside them, collected once
     * (a footprint list of a few hundred placed modules covers ~100k tiles; testing every
     * candidate site of a spiral search against every rectangle is what made a tree of a few
     * thousand flops take minutes). Null or empty means no restriction.
     */
    public static Predicate<Tile> noGoPredicate(Device device, List<RelocatableTileRectangle> noGoBboxes) {
        if (noGoBboxes == null || noGoBboxes.isEmpty()) return t -> false;
        Set<Tile> tiles = new HashSet<>();
        for (RelocatableTileRectangle bb : noGoBboxes) {
            for (int r = bb.getMinRow(); r <= bb.getMaxRow(); r++) {
                for (int c = bb.getMinColumn(); c <= bb.getMaxColumn(); c++) {
                    Tile t = device.getTile(r, c);
                    if (t != null) tiles.add(t);
                }
            }
        }
        return tiles::contains;
    }

    /**
     * Wraps a site iterator so that only sites whose tile {@code noGo} rejects are yielded.
     */
    private static Iterator<Site> applyNoGoFilter(Iterator<Site> base, Predicate<Tile> noGo) {
        return new Iterator<Site>() {
            private Site nextSite;
            private boolean exhausted = false;

            private void advance() {
                while (base.hasNext()) {
                    Site s = base.next();
                    if (!noGo.test(s.getTile())) {
                        nextSite = s;
                        return;
                    }
                }
                exhausted = true;
            }

            @Override
            public boolean hasNext() {
                if (nextSite == null && !exhausted) advance();
                return nextSite != null;
            }

            @Override
            public Site next() {
                if (!hasNext()) throw new NoSuchElementException();
                Site s = nextSite;
                nextSite = null;
                return s;
            }
        };
    }

    private static Iterator<Site> sitesWithinRowRange(Iterator<Site> base,
                                                      int minRow, int maxRow, int maxScans) {
        return new Iterator<Site>() {
            private Site nextSite;
            private int scanned = 0;
            private boolean exhausted = false;

            private void advance() {
                while (base.hasNext() && scanned < maxScans) {
                    Site s = base.next();
                    scanned++;
                    int r = s.getTile().getRow();
                    if (r >= minRow && r <= maxRow) {
                        nextSite = s;
                        return;
                    }
                }
                exhausted = true;
            }

            @Override
            public boolean hasNext() {
                if (nextSite == null && !exhausted) advance();
                return nextSite != null;
            }

            @Override
            public Site next() {
                if (!hasNext()) throw new NoSuchElementException();
                Site s = nextSite;
                nextSite = null;
                return s;
            }
        };
    }

    private static Pair<Site, BEL> nextAvailFlopPlacement(Design design, Iterator<Site> itr, SLR slr) {
        while (itr.hasNext()) {
            Site curr = itr.next();
            if (slr != null && curr.getTile().getSLR() != slr) {
                continue;
            }
            SiteInst candidate = design.getSiteInstFromSite(curr);
            Set<String> usedBelNames = new HashSet<>();
            if (candidate != null) {
                for (Cell c : candidate.getCells()) {
                    if (c.isPlaced() && c.getBEL() != null) {
                        usedBelNames.add(c.getBEL().getName());
                    }
                }
            }
            for (BEL b : curr.getBELs()) {
                if (!b.isFF() || b.isAnyIMR()) continue;
                if (usedBelNames.contains(b.getName())) continue;
                if (!isControlSetCompatibleForInsertedFDRE(candidate, b)) continue;
                String belName = b.getName();
                if (candidate != null) {
                    // Verify the FF's output site pin is not already in use. Relies on the
                    // SLICE FF naming convention (BEL "AFF"->"AQ", "AFF2"->"AQ2", etc.) since
                    // there is no direct BEL-pin -> site-pin accessor to derive it from.
                    String sitePinName = null;
                    if (belName.length() >= 3 && belName.charAt(1) == 'F' && belName.charAt(2) == 'F') {
                        sitePinName = belName.charAt(0) + "Q" + belName.substring(3);
                    }
                    if (sitePinName != null && candidate.getSitePinInst(sitePinName) != null) {
                        continue;
                    }
                }
                return new Pair<>(curr, b);
            }
        }
        return null;
    }

    private static boolean isExpectedStaticControlNet(Net net, boolean expectGnd) {
        if (net == null) {
            return true;
        }
        return expectGnd ? net.isGNDNet() : net.isVCCNet();
    }

    private static boolean isExpectedStaticControlNet(SiteInst candidate, String sitePinName, boolean expectGnd) {
        SitePinInst sitePin = candidate.getSitePinInst(sitePinName);
        return sitePin == null || isExpectedStaticControlNet(sitePin.getNet(), expectGnd);
    }

    /**
     * An inserted FDRE buffer ties its clock enable high and set/reset low, and
     * inherits the candidate SLICE's shared control set. The site is only usable
     * if its clock-enable site pin is driven by VCC (or absent) and its set/reset
     * site pin is driven by GND (or absent); otherwise connecting the new FDRE's
     * CE/R would collide with the control net already on that site pin.
     * <p>
     * Only applies on Versal; returns {@code true} for other series (no control-set
     * guard), since the CE/SR site-pin mapping used here is Versal-specific.
     */
    static boolean isControlSetCompatibleForInsertedFDRE(SiteInst candidate, BEL bel) {
        if (candidate == null || bel == null || candidate.getDesign().getSeries() != Series.Versal) {
            return true;
        }
        Pair<String, String> sitePinNames =
                DesignTools.belTypeSitePinNameMapping.get(Series.Versal).get(bel.getName());
        if (sitePinNames == null) {
            return true;
        }
        // first = clock-enable site pin (expect VCC), second = set/reset site pin (expect GND)
        return isExpectedStaticControlNet(candidate, sitePinNames.getFirst(), false)
                && isExpectedStaticControlNet(candidate, sitePinNames.getSecond(), true);
    }

    private static Pair<Site, Net> placeFlopNearCentroidOfPortInsts(Design design, String clkName, Net inputNet,
                                                                    String newNetName, List<EDIFHierPortInst> portInsts,
                                                                    Set<SiteInst> siteInstsToRoute, SLR requiredSLR,
                                                                    Predicate<Tile> noGo) {
        Site centroid = findCentroidOfPortInsts(design, portInsts);

        if (centroid == null) {
            throw new RuntimeException("Failed to find centroid of net " + inputNet);
        }

        Iterator<Site> siteItr = applyNoGoFilter(ECOPlacementHelper.spiralOutFrom(centroid).iterator(), noGo);
        Pair<Site, BEL> loc = nextAvailFlopPlacement(design, siteItr, requiredSLR);
        if (loc == null) {
            throw new RuntimeException("Failed to find location to place flop in flop tree"
                    + (requiredSLR != null ? " (required SLR " + requiredSLR.getId() + ")" : ""));
        }
        if (requiredSLR != null && loc.getFirst().getTile().getSLR() != requiredSLR) {
            throw new RuntimeException("Placed flop " + newNetName + " at site " + loc.getFirst()
                    + " in SLR " + loc.getFirst().getTile().getSLR().getId()
                    + " but required SLR " + requiredSLR.getId());
        }
        Pair<Cell, Net> flopNetPair = createAndPlaceFlopForTree(design, inputNet.getLogicalHierNet(), newNetName, loc,
                design.getNetlist().getHierNetFromName(clkName), portInsts);
        if (requiredSLR != null) {
            Site placed = flopNetPair.getFirst().getSiteInst().getSite();
            if (placed.getTile().getSLR() != requiredSLR) {
                throw new RuntimeException("Flop tree flop " + newNetName + " landed at " + placed
                        + " (SLR " + placed.getTile().getSLR().getId() + ") but required SLR "
                        + requiredSLR.getId());
            }
        }
        siteInstsToRoute.add(flopNetPair.getFirst().getSiteInst());
        return new Pair<>(centroid, flopNetPair.getSecond());
    }

    private static void insertFlopTreeForNetInSLR(Design design, SLR slr, String netName, String clkName, int depth,
                                                  List<EDIFHierPortInst> sinkHierPortInsts,
                                                  Set<SiteInst> siteInstsToRoute,
                                                  Predicate<Tile> noGo) {
        Net topNet = design.getNet(netName);
        List<Pair<Net, List<EDIFHierPortInst>>> currPortInstList = new ArrayList<>();
        currPortInstList.add(new Pair<>(topNet, sinkHierPortInsts));

        List<Pair<Net, List<EDIFHierPortInst>>> nextPortInstList = new ArrayList<>();

        for (int currDepth = 0; currDepth < depth; currDepth++) {
            int i = 0;
            for (Pair<Net, List<EDIFHierPortInst>> pair : currPortInstList) {
                Net net = pair.getFirst();
                List<EDIFHierPortInst> portInsts = pair.getSecond();
                String newNetName = netName.replace(EDIFTools.EDIF_HIER_SEP, "_") + "_slr" + slr.getId() + "_d" + currDepth + "_" + i;
                Pair<Site, Net> centroidNetPair = placeFlopNearCentroidOfPortInsts(design, clkName, net, newNetName,
                        portInsts, siteInstsToRoute, slr, noGo);

                Site centroid = centroidNetPair.getFirst();
                Net newNet = centroidNetPair.getSecond();
                PortInstQuadrants quadrants = splitPortInstsIntoQuadrants(design, portInsts, centroid);
                for (List<EDIFHierPortInst> quadrant : quadrants) {
                    if (!quadrant.isEmpty()) {
                        nextPortInstList.add(new Pair<>(newNet, quadrant));
                    }
                }
                i++;
            }
            currPortInstList = nextPortInstList;
            nextPortInstList = new ArrayList<>();
        }
    }

    private static Site getNearestValidSite(Design design, int row, int col) {
        return ECOPlacementHelper.getCentroidOfPoints(design.getDevice(),
                Collections.singletonList(new Point(col, row)), VALID_CENTROID_SITE_TYPES);
    }

    /**
     * Builds a per-destination chain of SLR crossings from the source SLR down to
     * a single {@code targetSLR}, with the given source-side pacing depth in each
     * pre-crossing segment. Returns the bottom flop's net of the final crossing
     * (the tap usable as the in-target-SLR fanout source).
     * <p>
     * Currently only handles downward traversal (sinks physically below source
     * = larger tile rows). {@code srcSegmentDepths.length} must equal the number
     * of crossings (i.e. {@code |targetSLR.id - sourceSLR.id|}).
     */
    private static Net insertSourceChainToSLR(Design design, Net net, String clkName,
                                              int[] srcSegmentDepths,
                                              SLR targetSLR,
                                              List<EDIFHierPortInst> targetSLRPortInsts,
                                              Set<SiteInst> siteInstsToRoute,
                                              Predicate<Tile> noGo) {
        int numCrossings = srcSegmentDepths.length;
        if (numCrossings == 0) return net;

        Cell sourceCell = net.getLogicalHierNet().getSourcePortInsts(false).get(0).getPhysicalCell(design);
        Site sourceSite = sourceCell.getSite();
        SLR sourceSLR = sourceSite.getTile().getSLR();
        String slrCrossingNamePrefix = net.getName().replace(EDIFTools.EDIF_HIER_SEP, "_")
                + "_slr_xing_to_slr" + targetSLR.getId();

        Net currentNet = net;
        int currentRow = sourceSite.getTile().getRow();
        int currentCol = sourceSite.getTile().getColumn();
        SLR currentSLR = sourceSLR;

        for (int crossingIdx = 0; crossingIdx < numCrossings; crossingIdx++) {
            int boundaryRow = currentSLR.getLowerRight().getRow();
            int thisChainDepth = srcSegmentDepths[crossingIdx];

            // Source-side pacing chain for THIS segment: from currentRow → boundaryRow.
            for (int i = 0; i < thisChainDepth; i++) {
                double frac = (double) (i + 1) / (thisChainDepth + 1);
                int row = (int) Math.round(currentRow + frac * (boundaryRow - currentRow));
                List<Point> points = new ArrayList<>();
                points.add(new Point(currentCol, row));
                Site target = ECOPlacementHelper.getCentroidOfPoints(design.getDevice(), points, VALID_CENTROID_SITE_TYPES);
                Iterator<Site> chainItr = applyNoGoFilter(ECOPlacementHelper.spiralOutFrom(target).iterator(), noGo);
                Pair<Site, BEL> chainLoc = nextAvailFlopPlacement(design, chainItr, currentSLR);
                if (chainLoc == null) {
                    throw new RuntimeException("Failed to place src pacing flop in SLR " + currentSLR.getId()
                            + " for crossing " + crossingIdx + " of net " + net.getName());
                }
                Pair<Cell, Net> chainPair = createAndPlaceFlopForTree(design, currentNet.getLogicalHierNet(),
                        slrCrossingNamePrefix + "_xing" + crossingIdx + "_src_ff" + i, chainLoc,
                        design.getNetlist().getHierNetFromName(clkName), targetSLRPortInsts);
                siteInstsToRoute.add(chainPair.getFirst().getSiteInst());
                currentNet = chainPair.getSecond();
            }

            // Top flop at the SLR boundary, constrained to land within
            // MAX_SLR_XING_TOP_FROM_BOUNDARY_ROWS tile rows of the boundary.
            String topName = slrCrossingNamePrefix + (numCrossings == 1
                    ? ""
                    : "_xing" + crossingIdx) + "_top";
            Site firstSLRSite = getNearestValidSite(design, boundaryRow, currentCol);
            Iterator<Site> siteItr = applyNoGoFilter(ECOPlacementHelper.spiralOutFrom(firstSLRSite).iterator(), noGo);
            Iterator<Site> boundedItr = sitesWithinRowRange(siteItr,
                    boundaryRow - MAX_SLR_XING_TOP_FROM_BOUNDARY_ROWS,
                    boundaryRow,
                    100_000);
            Pair<Site, BEL> loc = nextAvailFlopPlacement(design, boundedItr, null);
            if (loc == null) {
                throw new RuntimeException("Could not place " + topName + " within "
                        + MAX_SLR_XING_TOP_FROM_BOUNDARY_ROWS
                        + " rows of SLR boundary at row " + boundaryRow
                        + " (col " + currentCol + "); consider relaxing MAX_SLR_XING_TOP_FROM_BOUNDARY_ROWS"
                        + " or freeing sites near the boundary");
            }
            Pair<Cell, Net> topNetCellPair = createAndPlaceFlopForTree(design, currentNet.getLogicalHierNet(),
                    topName, loc,
                    design.getNetlist().getHierNetFromName(clkName), targetSLRPortInsts);
            siteInstsToRoute.add(topNetCellPair.getFirst().getSiteInst());

            // Bottom flop one SLL hop below where the top flop actually landed.
            Site placedTopSite = topNetCellPair.getFirst().getSiteInst().getSite();
            int actualTopRow = placedTopSite.getTile().getRow();
            int actualTopCol = placedTopSite.getTile().getColumn();
            int bottomTargetRow = actualTopRow + SLL_WIRE_LENGTH_ROWS;

            String bottomName = slrCrossingNamePrefix + (numCrossings == 1
                    ? ""
                    : "_xing" + crossingIdx) + "_bottom";
            Site secondSLRSite = getNearestValidSite(design, bottomTargetRow, actualTopCol);
            Iterator<Site> bottomItr = applyNoGoFilter(ECOPlacementHelper.spiralOutFrom(secondSLRSite).iterator(), noGo);
            Pair<Site, BEL> bottomLoc = nextAvailFlopPlacement(design, bottomItr, null);
            if (bottomLoc == null) {
                throw new RuntimeException("Failed to place " + bottomName + " for crossing " + crossingIdx
                        + " of net " + net.getName());
            }
            Pair<Cell, Net> bottomNetCellPair = createAndPlaceFlopForTree(design,
                    topNetCellPair.getSecond().getLogicalHierNet(), bottomName, bottomLoc,
                    design.getNetlist().getHierNetFromName(clkName), targetSLRPortInsts);
            siteInstsToRoute.add(bottomNetCellPair.getFirst().getSiteInst());

            // Advance state for the next crossing iteration.
            currentNet = bottomNetCellPair.getSecond();
            Site placedBottomSite = bottomNetCellPair.getFirst().getSiteInst().getSite();
            if (placedBottomSite.getTile().getSLR() == currentSLR) {
                throw new RuntimeException("Bottom crossing flop " + bottomName
                        + " did not cross into a new SLR; landed in SLR " + currentSLR.getId());
            }
            currentRow = placedBottomSite.getTile().getRow();
            currentCol = placedBottomSite.getTile().getColumn();
            currentSLR = placedBottomSite.getTile().getSLR();
        }

        if (currentSLR != targetSLR) {
            throw new RuntimeException("Source chain to SLR " + targetSLR.getId()
                    + " ended in SLR " + currentSLR.getId() + " for net " + net.getName());
        }
        return currentNet;
    }

    /**
     * Inserts a chain of {@code depth} flops between the source of {@code net}
     * and {@code portInsts}, evenly spaced along the line from the source to the
     * sinks' centroid. Returns the net driven by the final flop of the chain.
     */
    public static Net insertFlopChain(Design design, Net net, String clkName, int depth,
                                      List<EDIFHierPortInst> portInsts, Set<SiteInst> siteInstsToRoute) {
        return insertFlopChain(design, net, clkName, depth, portInsts, siteInstsToRoute,
                Collections.emptyList());
    }

    /**
     * As {@link #insertFlopChain(Design, Net, String, int, List, Set)}, but the
     * inserted flops will not be placed inside any of {@code noGoBboxes} (may be
     * null or empty for no restriction).
     */
    public static Net insertFlopChain(Design design, Net net, String clkName, int depth,
                                      List<EDIFHierPortInst> portInsts, Set<SiteInst> siteInstsToRoute,
                                      List<RelocatableTileRectangle> noGoBboxes) {
        return insertFlopChain(design, net, clkName, depth, portInsts, siteInstsToRoute,
                noGoPredicate(design.getDevice(), noGoBboxes));
    }

    /** As above, with the no-go test as a predicate over tiles (see {@link #noGoPredicate}). */
    public static Net insertFlopChain(Design design, Net net, String clkName, int depth,
                                      List<EDIFHierPortInst> portInsts, Set<SiteInst> siteInstsToRoute,
                                      Predicate<Tile> noGo) {
        List<EDIFHierPortInst> sources = net.getLogicalHierNet().getLeafHierPortInsts(true, false);
        if (sources.isEmpty()) {
            throw new RuntimeException("Net " + net.getName() + " does not have a source");
        }
        Cell sourceCell = sources.get(0).getPhysicalCell(design);
        Site sourceSite = sourceCell.getSite();
        Site portInstCentroid = findCentroidOfPortInsts(design, portInsts);

        if (portInstCentroid == null) {
            throw new RuntimeException("Failed to find centroid of sinks for net " + net.getName());
        }

        int srcCol = sourceSite.getTile().getColumn();
        int srcRow = sourceSite.getTile().getRow();
        int dstCol = portInstCentroid.getTile().getColumn();
        int dstRow = portInstCentroid.getTile().getRow();

        List<Point> targets = chainStageTargets(design.getDevice(), net.getName(), srcRow, srcCol, dstRow, dstCol, depth);

        Net currentNet = net;
        EDIFNetlist netlist = design.getNetlist();
        for (int i = 0; i < depth; i++) {
            Point pt = targets.get(i);
            Site target = getNearestValidSite(design, pt.y, pt.x);

            // Prefer slices nothing else uses, so two chains placed along the same line (e.g. a
            // request and its reply) never share a slice: the deskew must be able to move each
            // stage on its own. Fall back to any free flop BEL if no empty slice is in reach.
            Iterator<Site> siteItr = applyNoGoFilter(unusedSitesOnly(design,
                    ECOPlacementHelper.spiralOutFrom(target).iterator()), noGo);
            Pair<Site, BEL> loc = nextAvailFlopPlacement(design, siteItr, null);
            if (loc == null) {
                siteItr = applyNoGoFilter(ECOPlacementHelper.spiralOutFrom(target).iterator(), noGo);
                loc = nextAvailFlopPlacement(design, siteItr, null);
            }

            if (loc == null) {
                throw new RuntimeException("Failed to find location to place chain flop " + i
                        + " for net " + currentNet.getName());
            }

            Pair<Cell, Net> netCellPair = createAndPlaceFlopForTree(design, currentNet.getLogicalHierNet(),
                    currentNet.getName().replace(EDIFTools.EDIF_HIER_SEP, "_") + "_ff" + i, loc,
                    netlist.getHierNetFromName(clkName), portInsts);

            siteInstsToRoute.add(netCellPair.getFirst().getSiteInst());
            currentNet = netCellPair.getSecond();
        }

        return currentNet;
    }

    /** Wraps a site iterator so it only yields sites with no SiteInst in the design. */
    private static Iterator<Site> unusedSitesOnly(Design design, Iterator<Site> base) {
        return new Iterator<Site>() {
            private Site nextSite;
            private boolean exhausted = false;
            private void advance() {
                while (nextSite == null && !exhausted) {
                    if (!base.hasNext()) { exhausted = true; return; }
                    Site s = base.next();
                    if (design.getSiteInstFromSite(s) == null) nextSite = s;
                }
            }
            @Override public boolean hasNext() { advance(); return nextSite != null; }
            @Override public Site next() {
                advance();
                if (nextSite == null) throw new NoSuchElementException();
                Site s = nextSite; nextSite = null; return s;
            }
        };
    }

    /** Fallback tile-row span of one SLL when the device query finds no crossing UBUMP node. */
    private static final int DEFAULT_SLL_SPAN_ROWS = 94;

    private static SLR slrOfRow(Device dev, int row) {
        for (SLR slr : dev.getSLRs()) {
            if (row >= slr.getUpperLeft().getRow() && row <= slr.getLowerRight().getRow()) return slr;
        }
        return null;
    }

    /**
     * Stage positions (x = tile column, y = tile row) for a {@code depth}-stage chain from
     * (srcRow, srcCol) to (dstRow, dstCol). Within one SLR the stages are evenly spaced along
     * the straight line. Where the line crosses an SLR boundary, two consecutive stages are
     * pinned to the two ends of an SLL: one next to the SLL column on the near side, the next
     * exactly one SLL span away on the far side, so the crossing hop is the SLL itself plus a
     * few local hops rather than the SLL wrapped in tens of rows of ordinary routing. The
     * remaining stages are spread evenly over the rest of the line on each side.
     */
    static List<Point> chainStageTargets(Device dev, String netName, int srcRow, int srcCol,
                                         int dstRow, int dstCol, int depth) {
        List<Point> out = new ArrayList<>();
        int dir = Integer.signum(dstRow - srcRow);
        // boundaries the line crosses, as the last near-side row, in walking order
        List<Integer> nearRows = new ArrayList<>();
        if (dir != 0) {
            SLR prev = slrOfRow(dev, srcRow);
            for (int r = srcRow + dir; r != dstRow + dir; r += dir) {
                SLR cur = slrOfRow(dev, r);
                if (cur != null && prev != null && cur != prev) nearRows.add(r - dir);
                if (cur != null) prev = cur;
            }
        }
        if (nearRows.isEmpty() || depth < 2 * nearRows.size()) {
            if (!nearRows.isEmpty()) {
                System.out.println("[FlopChain] " + netName + ": " + nearRows.size() + " SLR crossing(s) but only "
                        + depth + " stages; falling back to even spacing");
            }
            for (int i = 0; i < depth; i++) {
                double frac = (double) (i + 1) / (depth + 1);
                out.add(new Point((int) Math.round(srcCol + frac * (dstCol - srcCol)),
                                  (int) Math.round(srcRow + frac * (dstRow - srcRow))));
            }
            return out;
        }

        int placed = 0;
        double posRow = srcRow;
        for (int b = 0; b < nearRows.size(); b++) {
            int nearRow = nearRows.get(b);
            int remainingCross = nearRows.size() - b;
            int remainingStages = depth - placed;
            // hops left: remainingStages + 1 (to dst), of which remainingCross are SLLs
            int plainHops = remainingStages + 1 - remainingCross;
            double h = Math.max(0, (Math.abs(dstRow - posRow) - remainingCross * DEFAULT_SLL_SPAN_ROWS)) / Math.max(1, plainHops);
            // choose the launch as the evenly spaced position whose distance to the boundary is
            // closest to half an SLL span, so launch and landing sit symmetrically about it
            int stagesToBoundary = Math.max(1, (int) Math.round(Math.abs(nearRow - posRow) / Math.max(1e-6, h)));
            int bestK = -1; double bestScore = Double.MAX_VALUE;
            for (int k = 1; k <= Math.min(stagesToBoundary + 1, remainingStages - 1 - 2 * (remainingCross - 1)); k++) {
                double launch = posRow + dir * k * h;
                double dist = Math.abs(nearRow - launch) + 0.5;   // rows from the launch to the far side
                if (dir * (nearRow - launch) < 0 || dist > DEFAULT_SLL_SPAN_ROWS - 1) continue;
                double score = Math.abs(dist - DEFAULT_SLL_SPAN_ROWS / 2.0);
                if (score < bestScore) { bestScore = score; bestK = k; }
            }
            if (bestK < 0) bestK = 1;
            double launchRowIdeal = Math.min(Math.max(posRow + dir * bestK * h, Math.min(posRow, nearRow)), Math.max(posRow, nearRow));
            // stages before the launch, evenly spaced from the current position to the launch
            for (int k = 1; k < bestK; k++) {
                double frac = (double) k / bestK;
                out.add(new Point((int) Math.round(colAt(srcRow, srcCol, dstRow, dstCol, posRow + (launchRowIdeal - posRow) * frac)),
                                  (int) Math.round(posRow + (launchRowIdeal - posRow) * frac)));
                placed++;
            }
            // the SLL: nearest SLL column to the line at the launch row, actual span from the device
            int lineCol = (int) Math.round(colAt(srcRow, srcCol, dstRow, dstCol, launchRowIdeal));
            int[] sll = findSLL(dev, (int) Math.round(launchRowIdeal), lineCol, dir, nearRow);
            int launchRow = sll[0], landRow = sll[1], sllCol = sll[2];
            System.out.printf("[FlopChain] %s: SLR crossing %d/%d at rows %d->%d (boundary after row %d), SLL column %d, span %d rows; stages %d and %d%n",
                    netName, b + 1, nearRows.size(), launchRow, landRow, nearRow, sllCol, Math.abs(landRow - launchRow), placed, placed + 1);
            out.add(new Point(sllCol, launchRow)); placed++;
            out.add(new Point(sllCol, landRow)); placed++;
            posRow = landRow;
            // after the last crossing the columns drift back towards the line from the SLL column
            if (b == nearRows.size() - 1) {
                int rest = depth - placed;
                for (int k = 1; k <= rest; k++) {
                    double frac = (double) k / (rest + 1);
                    out.add(new Point((int) Math.round(sllCol + frac * (dstCol - sllCol)),
                                      (int) Math.round(posRow + frac * (dstRow - posRow))));
                    placed++;
                }
            } else {
                srcRow = landRow; srcCol = sllCol;   // the line to the next boundary starts at the landing
            }
        }
        return out;
    }

    private static double colAt(int r0, int c0, int r1, int c1, double row) {
        if (r1 == r0) return c0;
        return c0 + (c1 - c0) * (row - r0) / (double) (r1 - r0);
    }

    /**
     * Finds an SLL crossing the boundary after {@code nearRow} in the SLL column nearest to
     * {@code col}, launching from a tile at or near {@code wantRow} on the near side. Returns
     * {launchRow, landingRow, sllColumn}; falls back to the default span if the device query
     * finds no UBUMP node there.
     */
    private static int[] findSLL(Device dev, int wantRow, int col, int dir, int nearRow) {
        int sllCol = -1;
        for (int d = 0; d < 400 && sllCol < 0; d++) {
            for (int c : new int[] {col - d, col + d}) {
                Tile t = dev.getTile(nearRow, c);
                if (t != null && t.getName().startsWith("SLL_")) { sllCol = c; break; }
            }
        }
        SLR near = slrOfRow(dev, nearRow);
        if (sllCol >= 0) {
            // search rows around the wanted launch row, staying on the near side
            for (int d = 0; d < DEFAULT_SLL_SPAN_ROWS; d++) {
                for (int r : new int[] {wantRow - d, wantRow + d}) {
                    if (slrOfRow(dev, r) != near) continue;
                    Tile t = dev.getTile(r, sllCol);
                    if (t == null || !t.getName().startsWith("SLL_")) continue;
                    for (int w = 0; w < t.getWireCount(); w++) {
                        if (!t.getWireName(w).startsWith("UBUMP")) continue;
                        Node n = Node.getNode(t, w);
                        if (n == null) continue;
                        for (Wire x : n.getAllWiresInNode()) {
                            Tile xt = x.getTile();
                            if (xt.getSLR() != near && Integer.signum(xt.getRow() - r) == dir) {
                                return new int[] {r, xt.getRow(), sllCol};
                            }
                        }
                    }
                }
            }
        }
        int launch = wantRow;
        return new int[] {launch, launch + dir * DEFAULT_SLL_SPAN_ROWS, sllCol >= 0 ? sllCol : col};
    }

    /**
     * Inserts a pipelined fanout tree of {@code depth} flop levels between the
     * source of {@code netName} and all of its sinks. Sinks in other SLRs are
     * reached through SLR-crossing flop chains; within each SLR a quadrant-based
     * tree of at most {@code maxDepthPerSLR} levels is built, with any remaining
     * depth inserted as pacing chains.
     */
    public static void insertFlopTreeForNet(Design design, String netName, String clkName, int depth,
                                            int maxDepthPerSLR) {
        insertFlopTreeForNet(design, netName, clkName, depth, maxDepthPerSLR, Collections.emptyList());
    }

    /**
     * As {@link #insertFlopTreeForNet(Design, String, String, int, int)}, but the
     * inserted flops will not be placed inside any of {@code noGoBboxes} — typically
     * the bounding boxes of placed kernel/array modules, inside which the local INT
     * routing is too congested to escape from a fresh flop's site pin (may be null
     * or empty for no restriction).
     */
    public static void insertFlopTreeForNet(Design design, String netName, String clkName, int depth,
                                            int maxDepthPerSLR,
                                            List<RelocatableTileRectangle> noGoBboxes) {
        insertFlopTreeForNet(design, netName, clkName, depth, maxDepthPerSLR, noGoPredicate(design.getDevice(), noGoBboxes));
    }

    /**
     * As above, with the no-go test as a predicate over tiles: a caller inserting trees on many
     * nets builds it once with {@link #noGoPredicate}.
     */
    public static void insertFlopTreeForNet(Design design, String netName, String clkName, int depth,
                                            int maxDepthPerSLR, Predicate<Tile> noGo) {
        insertFlopTreeForNet(design, netName, clkName, depth, maxDepthPerSLR, noGo, null);
    }

    /**
     * As above, but with {@code siteInstsToRoute} non-null the new flops' sites are added to it
     * instead of being site-routed here, for the caller to route after all its trees: the first
     * site route after an ECO regenerates the netlist's parent-net map, a pass over the whole
     * netlist, so routing per net costs a pass per net (about 1.5 s each on a 500k-cell design).
     */
    public static void insertFlopTreeForNet(Design design, String netName, String clkName, int depth,
                                            int maxDepthPerSLR, Predicate<Tile> noGo, Set<SiteInst> siteInstsToRoute) {
        if (noGo == null) noGo = t -> false;
        boolean routeHere = siteInstsToRoute == null;
        if (routeHere) siteInstsToRoute = new HashSet<>();
        EDIFNetlist netlist = design.getNetlist();
        EDIFHierNet parentNet = netlist.getHierNetFromName(netName).getLeafSourcePortInst().getHierarchicalNet();
        Net topNet = design.getNet(parentNet.getHierarchicalNetName());
        List<EDIFHierPortInst> sinkHierPortInsts = topNet.getLogicalHierNet().getLeafHierPortInsts(false);

        Map<SLR, List<EDIFHierPortInst>> slrPortInstMap = splitPortInstsBySLR(design, sinkHierPortInsts);
        List<EDIFHierPortInst> sourcePortInsts = topNet.getLogicalHierNet().getSourcePortInsts(false);
        if (sourcePortInsts.isEmpty()) {
            throw new RuntimeException("Net " + netName + " does not have a source");
        }
        if (sourcePortInsts.size() > 1) {
            throw new RuntimeException("Net " + netName + " has multiple sources");
        }
        EDIFHierPortInst sourcePortInst = sourcePortInsts.get(0);
        Cell sourceCell = sourcePortInst.getPhysicalCell(design);
        if (sourceCell == null || !sourceCell.isPlaced()) {
            throw new RuntimeException("Source cell of net " + netName + " must be placed to create flop tree");
        }
        Site sourceSite = sourceCell.getSite();
        SLR sourceSLR = sourceSite.getTile().getSLR();

        for (Map.Entry<SLR, List<EDIFHierPortInst>> slrPortInsts : slrPortInstMap.entrySet()) {
            SLR slr = slrPortInsts.getKey();
            List<EDIFHierPortInst> portInsts = slrPortInsts.getValue();
            int slrDistFromSource = Math.abs(slr.getId() - sourceSLR.getId());
            int newDepth = depth - (2 * slrDistFromSource);
            int extra = Math.max(0, newDepth - maxDepthPerSLR);
            int treeDepth = Math.min(newDepth, maxDepthPerSLR);

            // Even split across (slrDist + 1) segments
            int totalSegments = slrDistFromSource + 1;
            int basePerSegment = extra / totalSegments;
            int remainder = extra % totalSegments;
            int[] srcSegmentDepths = new int[slrDistFromSource];
            for (int i = 0; i < slrDistFromSource; i++) {
                srcSegmentDepths[i] = basePerSegment + (i < remainder ? 1 : 0);
            }
            int dstChainDepth = basePerSegment + (slrDistFromSource < remainder ? 1 : 0);

            Net slrCrossedNet;
            if (slrDistFromSource == 0) {
                slrCrossedNet = topNet;
            } else {
                slrCrossedNet = insertSourceChainToSLR(design, topNet, clkName, srcSegmentDepths,
                        slr, portInsts, siteInstsToRoute, noGo);
            }
            if (dstChainDepth > 0) {
                slrCrossedNet = insertFlopChain(design, slrCrossedNet, clkName, dstChainDepth, portInsts,
                        siteInstsToRoute, noGo);
            }
            insertFlopTreeForNetInSLR(design, slr, slrCrossedNet.getName(), clkName, treeDepth, portInsts,
                    siteInstsToRoute, noGo);
        }

        if (routeHere) {
            for (SiteInst si : siteInstsToRoute) {
                si.routeSite();
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 4 || args.length > 6) {
            System.out.println("USAGE : <input.dcp> <output.dcp> <netName> <clkName> [depth=4] [maxDepthPerSLR=3]");
            return;
        }
        int depth = args.length > 4 ? Integer.parseInt(args[4]) : 4;
        int maxDepthPerSLR = args.length > 5 ? Integer.parseInt(args[5]) : 3;

        Design d = Design.readCheckpoint(args[0]);

        EDIFTools.uniqueifyNetlist(d);
        insertFlopTreeForNet(d, args[2], args[3], depth, maxDepthPerSLR);

        d.writeCheckpoint(args[1]);
    }
}
