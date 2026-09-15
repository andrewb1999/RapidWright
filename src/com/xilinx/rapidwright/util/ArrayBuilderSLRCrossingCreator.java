/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
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
package com.xilinx.rapidwright.util;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetTools;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockRange;
import com.xilinx.rapidwright.design.blocks.PBlockSide;
import com.xilinx.rapidwright.design.tools.ArrayBuilder;
import com.xilinx.rapidwright.design.tools.InlineFlopTools;
import com.xilinx.rapidwright.design.xdc.ConstraintTools;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SLR;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import java.io.IOException;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.VivadoTools;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class ArrayBuilderSLRCrossingCreator {
    public static final String PE_RUN_DIR = "PerformanceExplorer";
    private static final String PE_CLOCK_BUFG_NAME = "pe_clk_bufg";
    private static final String PE_CLOCK_INPUT_NET_NAME = "clk_in";
    private static final List<String> INPUT_KERNEL_OPTS = Arrays.asList("k", "kernel");
    private static final List<String> CROSSING_DESIGN_OPTS = Arrays.asList("c", "crossing");
    private static final List<String> TOP_INST_NAME_OPTS = Arrays.asList("t", "top-inst");
    private static final List<String> BOT_INST_NAME_OPTS = Arrays.asList("b", "bot-inst");
    private static final List<String> SIDE_MAP_OPTS = Arrays.asList("s", "side-map");
    private static final List<String> OUTPUT_OPTS = Arrays.asList("o", "output");
    private static final List<String> OUTPUT_CROSSING_MAP_OPTS = Arrays.asList("m", "crossing-map");
    private static final List<String> HELP_OPTS = Arrays.asList("?", "h", "help");
    private static final double DEFAULT_MIN_CLK_UNCERT = -0.100;
    private static final double DEFAULT_MAX_CLK_UNCERT = 0.250;
    private static final double DEFAULT_STEP_CLK_UNCERT = 0.025;
    private static final double DEFAULT_BASE_CLK_UNCERT = 0.300;
    private static final double DEFAULT_SLR_CROSSING_MAX_DELAY = 1.600;
    private static final double DEFAULT_SLR_CROSSING_MIN_DELAY = 0.250;
    private static final int MAX_SLR_CROSSING_PBLOCK_SEPARATION_ROWS = 200;
    public static final String DEFAULT_HD_CLK_SRC = "BUFGCE_X2Y0";

    private static OptionParser createOptionParser() {
        return new OptionParser() {
            {
                acceptsAll(INPUT_KERNEL_OPTS, "Input Kernel Design (*.dcp)").withRequiredArg();
                acceptsAll(CROSSING_DESIGN_OPTS, "Top Crossing Design of SLR Crossing (*.dcp)").withRequiredArg();
                acceptsAll(TOP_INST_NAME_OPTS, "Name of the top instance in the synthesized SLR Crossing design").withRequiredArg();
                acceptsAll(BOT_INST_NAME_OPTS, "Name of the bottom instance in the synthesized SLR Crossing design").withRequiredArg();
                acceptsAll(SIDE_MAP_OPTS, "Side Map for Kernel Design").withRequiredArg();
                acceptsAll(OUTPUT_OPTS, "Output SLR Crossing Design (*.dcp)").withRequiredArg();
                acceptsAll(HELP_OPTS, "Print this help message").forHelp();
            }
        };
    }

    private static PBlock copyPBlock(PBlock pblock) {
        return new PBlock(pblock.getDevice(), pblock.toString());
    }

    private static PBlock movePBlockCopy(PBlock pblock, int dy) {
        PBlock moved = copyPBlock(pblock);
        return moved.movePBlock(0, dy) ? moved : null;
    }

    private static boolean pblockCornerTilesAreInSLR(PBlock pblock, SLR slr) {
        return slr.containsTile(pblock.getTopLeftTile())
                && slr.containsTile(pblock.getTopRightTile())
                && slr.containsTile(pblock.getBottomLeftTile())
                && slr.containsTile(pblock.getBottomRightTile());
    }

    /**
     * Move {@code pblockTemplate} so its bottom edge is as close as possible
     * to the bottom of {@code topSLR} while the whole PBlock remains inside
     * that SLR. This deliberately depends on PBlock geometry, not on the
     * module's last valid anchor row. Small slice-only modules (for example
     * BufferTile) can have valid anchors far below where a larger crossing
     * PBlock can legally fit, which otherwise pushes the top PBlock too low.
     */
    private static PBlock createTopPBlockAtBottomOfSLR(PBlock pblockTemplate, SLR topSLR) {
        int originalBottomRow = pblockTemplate.getBottomLeftTile().getRow();
        int slrBottomRow = topSLR.getLowerRight().getRow();
        int slrTopRow = topSLR.getUpperLeft().getRow();

        for (int targetBottomRow = slrBottomRow; targetBottomRow >= slrTopRow; targetBottomRow--) {
            int dy = targetBottomRow - originalBottomRow;
            PBlock candidate = movePBlockCopy(pblockTemplate, dy);
            if (candidate != null && pblockCornerTilesAreInSLR(candidate, topSLR)) {
                return candidate;
            }
        }
        throw new RuntimeException("Failed to move top pblock inside SLR " + topSLR.getId()
                + " using template " + pblockTemplate);
    }

    private static List<PBlock> getCandidateBottomPBlocks(PBlock topPBlock) {
        List<PBlock> candidates = new ArrayList<>();
        int pBlockHeight = topPBlock.getBottomLeftTile().getRow() - topPBlock.getTopLeftTile().getRow();
        SLR topSLR = topPBlock.getTopLeftTile().getSLR();
        for (int yOffsetFromTop = pBlockHeight + 1;
             yOffsetFromTop <= MAX_SLR_CROSSING_PBLOCK_SEPARATION_ROWS;
             yOffsetFromTop++) {
            PBlock bottomPBlock = movePBlockCopy(topPBlock, yOffsetFromTop);
            if (bottomPBlock == null) {
                continue;
            }
            if (bottomPBlock.getTopLeftTile().getSLR() == topSLR) {
                continue;
            }
            candidates.add(bottomPBlock);
        }
        return candidates;
    }

    private static void addPBlockToDesign(Design design, PBlock pblock) {
        addPBlockToDesign(design, pblock, true);
    }

    /**
     * @param containRouting whether the pblock also contains routing. Only the spanning
     *        pblock should: a net between the two tiles has cells in both tile pblocks,
     *        and Vivado then waives containment for it entirely, letting hold-fix detours
     *        of the crossing nets wander into the neighbouring tiles' area.
     */
    private static void addPBlockToDesign(Design design, PBlock pblock, boolean containRouting) {
        pblock.setContainRouting(containRouting);
        pblock.setIsSoft(false);
        for (String tclCmd : pblock.getTclConstraints()) {
            design.addXDCConstraint(ConstraintGroup.LATE, tclCmd);
        }
        if (!containRouting) {
            // PBlock only emits IS_SOFT when it is set; Vivado's default is soft, and it was
            // CONTAIN_ROUTING that used to force these pblocks hard.
            design.addXDCConstraint(ConstraintGroup.LATE, "set_property IS_SOFT 0 [get_pblocks " + pblock.getName() + "]");
        }
    }

    private static PBlock createSpanningPBlock(PBlock topPBlock, PBlock bottomPBlock) {
        Map<String, NamespaceRows> rowsByPrefix = new TreeMap<>();
        addRelevantPrefixes(rowsByPrefix, topPBlock);
        addRelevantPrefixes(rowsByPrefix, bottomPBlock);

        int minRow = Math.min(topPBlock.getTopLeftTile().getRow(), bottomPBlock.getTopLeftTile().getRow());
        int maxRow = Math.max(topPBlock.getBottomRightTile().getRow(), bottomPBlock.getBottomRightTile().getRow());
        int minCol = Math.min(topPBlock.getTopLeftTile().getColumn(), bottomPBlock.getTopLeftTile().getColumn());
        int maxCol = Math.max(topPBlock.getBottomRightTile().getColumn(), bottomPBlock.getBottomRightTile().getColumn());

        for (int row = minRow; row <= maxRow; row++) {
            for (int col = minCol; col <= maxCol; col++) {
                Tile tile = topPBlock.getDevice().getTile(row, col);
                if (tile == null || tile.getSites() == null) {
                    continue;
                }
                for (Site site : tile.getSites()) {
                    NamespaceRows namespaceRows = rowsByPrefix.get(site.getNameSpacePrefix());
                    if (namespaceRows != null) {
                        namespaceRows.include(site);
                    }
                }
            }
        }

        PBlock spanningPBlock = new PBlock(topPBlock.getDevice(), "");
        for (NamespaceRows namespaceRows : rowsByPrefix.values()) {
            namespaceRows.addRangesTo(spanningPBlock);
        }
        return spanningPBlock;
    }

    private static void addRelevantPrefixes(Map<String, NamespaceRows> rowsByPrefix, PBlock pblock) {
        for (PBlockRange range : pblock) {
            if (!range.isSiteRange()) {
                throw new RuntimeException("Expected site-based SLR crossing pblock range, got: " + range);
            }
            Site lowerLeft = range.getLowerLeftSite();
            Site upperRight = range.getUpperRightSite();
            String prefix = lowerLeft.getNameSpacePrefix();
            if (!prefix.equals(upperRight.getNameSpacePrefix())) {
                throw new RuntimeException("Mismatched pblock range namespaces: " + range);
            }

            if (!rowsByPrefix.containsKey(prefix)) {
                rowsByPrefix.put(prefix, new NamespaceRows(prefix, lowerLeft.getName(), pblock.getDevice()));
            }
        }
    }

    private static class NamespaceRows {
        private final String prefix;
        private final String templateSiteName;
        private final com.xilinx.rapidwright.device.Device device;
        private final TreeMap<Integer, RowBounds> rows = new TreeMap<>();

        private NamespaceRows(String prefix, String templateSiteName,
                              com.xilinx.rapidwright.device.Device device) {
            this.prefix = prefix;
            this.templateSiteName = templateSiteName;
            this.device = device;
        }

        private void include(Site site) {
            rows.computeIfAbsent(site.getInstanceY(), RowBounds::new).include(site);
        }

        private void addRangesTo(PBlock pblock) {
            RowBounds open = null;
            for (RowBounds row : rows.values()) {
                if (open == null) {
                    open = row.copy();
                } else if (open.canMerge(row)) {
                    open.maxY = row.maxY;
                } else {
                    addRange(pblock, open);
                    open = row.copy();
                }
            }
            if (open != null) {
                addRange(pblock, open);
            }
        }

        private void addRange(PBlock pblock, RowBounds row) {
            String lowerLeft = PBlockRange.replaceXY(templateSiteName, row.minX, row.minY);
            String upperRight = PBlockRange.replaceXY(templateSiteName, row.maxX, row.maxY);
            pblock.add(new PBlockRange(device, lowerLeft + ":" + upperRight));
        }
    }

    private static class RowBounds {
        private final int minY;
        private int maxY;
        private int minX = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;

        private RowBounds(int y) {
            this.minY = y;
            this.maxY = y;
        }

        private void include(Site site) {
            minX = Math.min(minX, site.getInstanceX());
            maxX = Math.max(maxX, site.getInstanceX());
        }

        private boolean canMerge(RowBounds other) {
            return maxY + 1 == other.minY && minX == other.minX && maxX == other.maxX;
        }

        private RowBounds copy() {
            RowBounds copy = new RowBounds(minY);
            copy.maxY = maxY;
            copy.minX = minX;
            copy.maxX = maxX;
            return copy;
        }
    }

    private static List<Node> getSLLsStartingInTile(Tile tile) {
        List<Node> sllNodes = new ArrayList<>();
        for (int i = 0; i < tile.getWireCount(); i++) {
            Node node = Node.getNode(tile, i);
            if (node == null) {
                continue;
            }
            IntentCode ic = node.getIntentCode();
            if (ic == IntentCode.NODE_SLL_DATA) {
                if (node.getAllUphillNodes().size() < 2 && node.getAllDownhillNodes().size() < 2) {
                    continue;
                }
                sllNodes.add(node);
            }
        }
        return sllNodes;
    }

    private static List<Node> getAllSLLsStartingInPBlock(PBlock pblock) {
        List<Node> sllNodes = new ArrayList<>();
        for (Tile t : pblock.getAllTiles()) {
            if (!Utils.isInterConnect(t.getTileTypeEnum())) {
                continue;
            }
            List<Node> tileSllNodes = getSLLsStartingInTile(t);
            sllNodes.addAll(tileSllNodes);
        }
        for (Node sll : sllNodes) {
            System.out.print(sll + " ");
        }
        System.out.println();
        return sllNodes;
    }

    private static int countNumSLLsEndingInPBlock(PBlock pblock, List<Node> sllNodes) {
        Set<Tile> tiles = pblock.getAllTiles();
        int i = 0;
        for (Node sll : sllNodes) {
            boolean downHillNodeInPBlock = false;
            for (Node downhillNode : sll.getAllDownhillNodes()) {
                if (tiles.contains(downhillNode.getTile())) {
                    downHillNodeInPBlock = true;
                }
            }
            if (tiles.contains(sll.getTile()) || downHillNodeInPBlock) {
                i++;
            }
        }
        return i;
    }

    private static PBlock chooseBestCandidate(PBlock topPBlock, List<PBlock> candidates) {
        List<Node> sllNodesStartingInTopPBlock = getAllSLLsStartingInPBlock(topPBlock);
        int bestSLLCount = 0;
        PBlock bestCandidate = null;
        for (PBlock candidate : candidates) {
            int numSLLs = countNumSLLsEndingInPBlock(candidate, sllNodesStartingInTopPBlock);
            if (numSLLs > bestSLLCount) {
                bestSLLCount = numSLLs;
                bestCandidate = candidate;
            }
        }
        if (bestSLLCount < 64) {
            throw new RuntimeException("Only found " + bestSLLCount + " SLLs between pblocks");
        }
        return bestCandidate;
    }

    private static void explorePerformance(Design design, String runDirectory, boolean reuse, double clkPeriod,
                                           boolean noExplore) {
        PerformanceExplorer pe = new PerformanceExplorer(design, runDirectory, "clk", clkPeriod);
        if (noExplore) {
            pe.setPlacerDirectives(Arrays.asList(PlacerDirective.Explore));
            pe.setRouterDirectives(Arrays.asList(RouterDirective.Explore));
            pe.setClockUncertaintyValues(Arrays.asList(0.0));
        } else {
            pe.setMinClockUncertainty(DEFAULT_MIN_CLK_UNCERT);
            pe.setMaxClockUncertainty(DEFAULT_MAX_CLK_UNCERT);
            pe.setClockUncertaintyStep(DEFAULT_STEP_CLK_UNCERT);
            pe.updateClockUncertaintyValues();
        }
        pe.setBaseClockUncertainty(DEFAULT_BASE_CLK_UNCERT);
        pe.setGetBestPerPBlock(true);
        pe.setReusePreviousResults(reuse);
        pe.setLockPlacement(true);
        pe.explorePerformance();
        pe.getBestDesignPerPBlock();
    }

    public static void createSLRCrossing(Design kernelDesign, Design topDesign,
                                         Map<EDIFPort, PBlockSide> sideMap,
                                         String topInstName, String bottomInstName,
                                         String outputPath) {
        createSLRCrossing(kernelDesign, topDesign, sideMap, topInstName, bottomInstName, outputPath, null, 1.6, false, false);
    }

    public static void createSLRCrossing(Design kernelDesign, Design topDesign,
                                         Map<EDIFPort, PBlockSide> sideMap,
                                         String topInstName, String bottomInstName,
                                         String outputPath, PBlock pblockOverride) {
        createSLRCrossing(kernelDesign, topDesign, sideMap, topInstName, bottomInstName, outputPath, pblockOverride, 1.6, false, false);
    }

    public static void createSLRCrossing(Design kernelDesign, Design topDesign,
                                         Map<EDIFPort, PBlockSide> sideMap,
                                         String topInstName, String bottomInstName,
                                         String outputPath, PBlock pblockOverride,
                                         double clkPeriod) {
        createSLRCrossing(kernelDesign, topDesign, sideMap, topInstName, bottomInstName, outputPath, pblockOverride, clkPeriod, false, false);
    }

    public static void createSLRCrossing(Design kernelDesign, Design topDesign,
                                         Map<EDIFPort, PBlockSide> sideMap,
                                         String topInstName, String bottomInstName,
                                         String outputPath, PBlock pblockOverride,
                                         double clkPeriod, boolean reusePreviousResults) {
        createSLRCrossing(kernelDesign, topDesign, sideMap, topInstName, bottomInstName,
                outputPath, pblockOverride, clkPeriod, reusePreviousResults, false);
    }

    public static void createSLRCrossing(Design kernelDesign, Design topDesign,
                                         Map<EDIFPort, PBlockSide> sideMap,
                                         String topInstName, String bottomInstName,
                                         String outputPath, PBlock pblockOverride,
                                         double clkPeriod, boolean reusePreviousResults,
                                         boolean disableHoldTiming) {
        createSLRCrossing(kernelDesign, topDesign, sideMap, topInstName, bottomInstName,
                outputPath, pblockOverride, clkPeriod, reusePreviousResults, disableHoldTiming, false);
    }

    /**
     * @param noExplore when true, the PerformanceExplorer pass runs a single
     *        Explore/Explore implementation at clock uncertainty 0.0 instead of
     *        the full cross product of placer/router directives and the
     *        clock-uncertainty sweep.
     */
    public static void createSLRCrossing(Design kernelDesign, Design topDesign,
                                         Map<EDIFPort, PBlockSide> sideMap,
                                         String topInstName, String bottomInstName,
                                         String outputPath, PBlock pblockOverride,
                                         double clkPeriod, boolean reusePreviousResults,
                                         boolean disableHoldTiming, boolean noExplore) {
        Options options = new Options()
                .setClkPeriod(clkPeriod)
                .setReusePreviousResults(reusePreviousResults)
                .setDisableHoldTiming(disableHoldTiming)
                .setNoExplore(noExplore);
        createSLRCrossing(kernelDesign, topDesign, sideMap, topInstName, bottomInstName, outputPath,
                pblockOverride, options);
    }

    /**
     * Settings for one SLR-crossing variant. The defaults reproduce the historical
     * behaviour: the crossing is built at the bottom of the first SLR of the
     * kernel's placement grid, Vivado chooses the clock root, and the top-to-bottom
     * paths carry the max/min delay guardband (hold checks are kept unless
     * {@link #setDisableHoldTiming(boolean)} adds the hold false path).
     * <p>
     * A crossing that will sit some clock-region rows below the array's clock root
     * sees a much larger launch-to-capture clock skew than one adjacent to the root
     * (about 0.45-0.6 ns against 0.1 ns at the hold corner on xcv80). To make Vivado
     * pad the crossing paths for that environment, build the variant at the matching
     * SLR boundary ({@link #setTopSlrId(int)}) and reproduce the array's clock tree:
     * Versal clock trees are templates chosen by the clock expansion window's span, each
     * with a fixed set of legal roots, and the placer replaces a USER_CLOCK_ROOT that
     * is not legal for the template (Place 30-1175). So the window is widened upward
     * ({@link #setClockWindowRowsAboveTop(int)}) until the template whose root is the
     * array's applies, and the root is pinned there ({@link #setClockRootRowsAboveTop(int)}).
     * Hold timing stays enabled, and the routed design gets its own top cell name so
     * it can coexist with the default variant in one array netlist.
     */
    public static class Options {
        private double clkPeriod = 1.6;
        private boolean reusePreviousResults;
        private boolean disableHoldTiming;
        private boolean noExplore;
        private double maxDelay = DEFAULT_SLR_CROSSING_MAX_DELAY;
        private double minDelay = DEFAULT_SLR_CROSSING_MIN_DELAY;
        private int topSlrId = -1;
        private int clockRootRowsAboveTop = -1;
        private int clockWindowRowsAboveTop = -1;
        private int clockRootColumnX = -1;
        private String topCellName;

        public double getClkPeriod() { return clkPeriod; }
        public Options setClkPeriod(double clkPeriod) { this.clkPeriod = clkPeriod; return this; }
        public boolean isReusePreviousResults() { return reusePreviousResults; }
        public Options setReusePreviousResults(boolean reuse) { this.reusePreviousResults = reuse; return this; }
        public boolean isDisableHoldTiming() { return disableHoldTiming; }
        public Options setDisableHoldTiming(boolean disable) { this.disableHoldTiming = disable; return this; }
        public boolean isNoExplore() { return noExplore; }
        public Options setNoExplore(boolean noExplore) { this.noExplore = noExplore; return this; }
        /** set_max_delay applied to every top-register to bottom-register path (ns). */
        public double getMaxDelay() { return maxDelay; }
        public Options setMaxDelay(double maxDelay) { this.maxDelay = maxDelay; return this; }
        /**
         * set_min_delay applied to every top-register to bottom-register path (ns). Vivado
         * treats it as the ordinary hold check plus this margin, so it is the hold slack the
         * crossing paths are padded to in the precompile's clock environment.
         */
        public double getMinDelay() { return minDelay; }
        public Options setMinDelay(double minDelay) { this.minDelay = minDelay; return this; }
        /** SLR whose bottom edge hosts the top tile; -1 picks the first SLR of the placement grid. */
        public int getTopSlrId() { return topSlrId; }
        public Options setTopSlrId(int topSlrId) { this.topSlrId = topSlrId; return this; }
        /**
         * When non-negative, pins USER_CLOCK_ROOT this many clock-region rows above the top
         * tile's clock region. Needs a window ({@link #setClockWindowRowsAboveTop(int)}) tall
         * enough for a template with that root, or the placer moves it.
         */
        public int getClockRootRowsAboveTop() { return clockRootRowsAboveTop; }
        public Options setClockRootRowsAboveTop(int rows) { this.clockRootRowsAboveTop = rows; return this; }
        /**
         * When non-negative, sets USER_CLOCK_EXPANSION_WINDOW from the bottom tile's clock
         * region up to this many rows above the top tile's, over the pblocks' columns.
         */
        public int getClockWindowRowsAboveTop() { return clockWindowRowsAboveTop; }
        public Options setClockWindowRowsAboveTop(int rows) { this.clockWindowRowsAboveTop = rows; return this; }
        /**
         * Clock-region column of the pinned root; -1 uses the right-most column of the top
         * pblock, which is the column Vivado's own root used here. A column without a
         * vertical clock route from the clock source is rejected (Place 30-5145).
         */
        public int getClockRootColumnX() { return clockRootColumnX; }
        public Options setClockRootColumnX(int x) { this.clockRootColumnX = x; return this; }
        /** Renames the routed design's netlist and top cell before it is written; null keeps the name. */
        public String getTopCellName() { return topCellName; }
        public Options setTopCellName(String topCellName) { this.topCellName = topCellName; return this; }
    }

    public static void createSLRCrossing(Design kernelDesign, Design topDesign,
                                         Map<EDIFPort, PBlockSide> sideMap,
                                         String topInstName, String bottomInstName,
                                         String outputPath, PBlock pblockOverride,
                                         Options options) {
        double clkPeriod = options.getClkPeriod();
        boolean reusePreviousResults = options.isReusePreviousResults();
        boolean disableHoldTiming = options.isDisableHoldTiming();
        boolean noExplore = options.isNoExplore();
        if (!kernelDesign.getDevice().getName().equals("xcv80")) {
            System.out.println("SLRCrossing creator currently only tested for xcv80");
        }

        PBlock pblock = pblockOverride;
        if (pblock == null) {
            Map<String, PBlock> pblocks = ConstraintTools.getPBlocksFromXDC(kernelDesign);
            if (pblocks.isEmpty()) {
                throw new RuntimeException("Provided kernel design does not contain a PBlock");
            }

            if (pblocks.size() > 1) {
                throw new RuntimeException("Kernel design should only contain 1 PBlock but currently contains "
                        + pblocks.size());
            }

            pblock = pblocks.values().iterator().next();
        }

        List<String> clockNets = ConstraintTools.getClockNetPortNamesFromXDC(kernelDesign);

        assert(clockNets.size() == 1);

        ArrayBuilder.removeBUFGs(kernelDesign);

        Module module = new Module(kernelDesign, false);

        module.getNet(clockNets.get(0)).unroute();
        module.setPBlock(pblock.toString());
        module.calculateAllValidPlacements(kernelDesign.getDevice());
        List<List<Site>> validPlacementGrid = ArrayBuilder.getValidPlacementGrid(module);

        SLR firstSLR = validPlacementGrid.get(0).get(0).getTile().getSLR();

        // Merge encrypted cells
        List<String> encryptedCells = module.getNetlist().getEncryptedCells();
        if (!encryptedCells.isEmpty()) {
            System.out.println("Encrypted cells merged");
            topDesign.getNetlist().addEncryptedCells(encryptedCells);
        }

        EDIFHierCellInst topHierInst = topDesign.getNetlist().getHierCellInstFromName(topInstName);
        if (topHierInst == null) {
            throw new RuntimeException("Instance name " + topInstName + " is invalid");
        }
        EDIFTools.removeVivadoBusPreventionAnnotations(kernelDesign.getNetlist());

        EDIFHierCellInst bottomHierInst = topDesign.getNetlist().getHierCellInstFromName(bottomInstName);
        if (bottomHierInst == null) {
            throw new RuntimeException("Instance name " + bottomInstName + " is invalid");
        }

        SLR topSLR = firstSLR;
        if (options.getTopSlrId() >= 0) {
            topSLR = kernelDesign.getDevice().getSLR(options.getTopSlrId());
            if (topSLR == null) {
                throw new RuntimeException("No SLR with id " + options.getTopSlrId() + " on "
                        + kernelDesign.getDevice().getName());
            }
        }
        PBlock topPBlock = createTopPBlockAtBottomOfSLR(pblock, topSLR);
        topPBlock.setName("pblock_0");
        System.out.println("[SLR-CROSSING] firstSLR=" + firstSLR.getId() + " topSLR=" + topSLR.getId()
                + " moduleAnchor=" + module.getAnchor()
                + " topPBlockRows=" + topPBlock.getTopLeftTile().getRow()
                + ".." + topPBlock.getBottomLeftTile().getRow());

        List<PBlock> candidates = getCandidateBottomPBlocks(topPBlock);
        System.out.println("[SLR-CROSSING] bottom pblock candidates=" + candidates.size());
        PBlock bottomPBlock = chooseBestCandidate(topPBlock, candidates);
        bottomPBlock.setName("pblock_1");

        PBlock overallPBlock = createSpanningPBlock(topPBlock, bottomPBlock);
        overallPBlock.setName("pblock_2");

        addPBlockToDesign(topDesign, overallPBlock, true);
        addPBlockToDesign(topDesign, topPBlock, false);
        addPBlockToDesign(topDesign, bottomPBlock, false);

        topDesign.addXDCConstraint(ConstraintGroup.LATE, "add_cells_to_pblock pblock_2 -top");
        topDesign.addXDCConstraint(ConstraintGroup.LATE, "add_cells_to_pblock pblock_0 [get_cells " + topInstName + "]");
        topDesign.addXDCConstraint(ConstraintGroup.LATE, "add_cells_to_pblock pblock_1 [get_cells " + bottomInstName + "]");

        topDesign.getNetlist().consolidateAllToWorkLibrary();
        topDesign.flattenDesign();
        topDesign.setDesignOutOfContext(true);
        topDesign.setAutoIOBuffers(false);

        // Get black-box port maps
        EDIFNetlist netlist = topDesign.getNetlist();
        Map<String, String> topBBPortMap = getBlackBoxToTopLevelMap(netlist, topInstName);
        Map<String, String> bottomBBPortMap = getBlackBoxToTopLevelMap(netlist, bottomInstName);

        EDIFCell topCell = netlist.getTopCell();
        EDIFCellInst topBBInst = netlist.getCellInstFromHierName(topInstName);
        Map<EDIFPort, PBlockSide> topSideMap = new HashMap<>();
        for (Map.Entry<String, String> portPair : topBBPortMap.entrySet()) {
            PBlockSide originalSide = sideMap.get(topBBInst.getPort(portPair.getKey()));
            if (originalSide == null || originalSide == PBlockSide.BOTTOM) {
                continue;
            }
            topSideMap.put(topCell.getPort(portPair.getValue()), originalSide);
        }

        InlineFlopTools.createAndPlacePortFlopsOnSide(topDesign, "clk", topPBlock, topSideMap);
        topDesign.getNetlist().resetParentNetMap();

        Map<EDIFPort, PBlockSide> bottomSideMap = new HashMap<>();
        EDIFCellInst bottomBBInst = netlist.getCellInstFromHierName(bottomInstName);
        for (Map.Entry<String, String> portPair : bottomBBPortMap.entrySet()) {
            EDIFPort bbPort = bottomBBInst.getPort(portPair.getKey());
            PBlockSide originalSide = sideMap.get(bbPort);
            if (originalSide == null || originalSide == PBlockSide.TOP) {
                continue;
            }
            EDIFPort topCellPort = topCell.getPort(portPair.getValue());
            bottomSideMap.put(topCellPort, originalSide);
        }
        bottomSideMap.keySet().removeAll(topSideMap.keySet());

        InlineFlopTools.createAndPlacePortFlopsOnSide(topDesign, "clk", bottomPBlock, bottomSideMap);
        netlist.resetParentNetMap();

        insertPEClockBUFGCE(topDesign);
        EDIFTools.ensurePreservedInterfaceVivado(topDesign.getNetlist());
        addSLRCrossingTimingGuardbandConstraint(topDesign, topInstName, bottomInstName,
                options.getMaxDelay(), options.getMinDelay());
        if (disableHoldTiming) {
            addSLRCrossingHoldFalsePathConstraint(topDesign, topInstName, bottomInstName);
        }
        addNoReplicateConstraintsForSLRCrossingNets(topDesign, topInstName, bottomInstName);
        addClockTreeConstraints(topDesign, topPBlock, bottomPBlock, options);

        String runDirectory = Paths.get(outputPath).getParent().resolve(PE_RUN_DIR).toString();
        explorePerformance(topDesign, runDirectory, reusePreviousResults, clkPeriod, noExplore);
        Design bestDesign = Design.readCheckpoint(Paths.get(runDirectory, "pblock0_best.dcp").toString());
        List<String> spilled = netsLeavingRectangle(bestDesign, overallPBlock);
        if (!spilled.isEmpty()) {
            bestDesign = rerouteSpilledNetsWithoutHoldPadding(runDirectory, spilled);
        }
        EDIFTools.removeVivadoBusPreventionAnnotations(bestDesign.getNetlist());
        removePEClockBUFGCE(bestDesign);
        InlineFlopTools.removeInlineFlops(bestDesign);
        NetTools.unrouteTopLevelNetsThatLeavePBlock(bestDesign, topPBlock);
        NetTools.unrouteTopLevelNetsThatLeavePBlock(bestDesign, bottomPBlock);
        List<String> stillSpilled = netsLeavingRectangle(bestDesign, overallPBlock);
        if (!stillSpilled.isEmpty()) {
            // Last resort: an unrouted crossing net cannot be completed by RWRoute (it does not
            // route through the SLL), so this should not happen after the re-route pass.
            int unrouted = 0, kept = 0;
            for (String name : stillSpilled) {
                Net n = bestDesign.getNet(name);
                boolean crossesSlr = false;
                for (PIP p : n.getPIPs()) {
                    if (p.getTile().getTileTypeEnum() == TileTypeEnum.SLL) {
                        crossesSlr = true;
                        break;
                    }
                }
                if (crossesSlr) {
                    kept++;
                } else {
                    n.unroute();
                    unrouted++;
                }
            }
            System.out.println("[SLR-CROSSING] WARNING: " + stillSpilled.size()
                    + " nets still route outside the spanning pblock after the re-route pass: unrouted " + unrouted
                    + ", kept " + kept + " SLR-crossing nets routed (their routes may stray a little into the neighbouring tiles): "
                    + stillSpilled.subList(0, Math.min(8, stillSpilled.size())));
        }
        DesignTools.createPossiblePinsToStaticNets(bestDesign);
        DesignTools.createMissingSitePinInsts(bestDesign);
        removeConstraintsContaining(bestDesign, USER_CLOCK_ROOT);
        removeConstraintsContaining(bestDesign, USER_CLOCK_EXPANSION_WINDOW);
        if (options.getTopCellName() != null) {
            bestDesign.getNetlist().renameNetlistAndTopCell(options.getTopCellName());
        }

        bestDesign.writeCheckpoint(outputPath);
    }

    private static void insertPEClockBUFGCE(Design design) {
        EDIFCell topCell = design.getTopEDIFCell();
        EDIFNet clkNet = topCell.getNet("clk");
        if (clkNet == null) {
            throw new RuntimeException("Expected top-level clk net for SLR-crossing PE BUFG insertion");
        }
        EDIFPortInst clkPortInst = clkNet.getPortInst(null, "clk");
        if (clkPortInst == null) {
            throw new RuntimeException("Expected top-level clk port-inst on clk net");
        }

        clkNet.removePortInst(clkPortInst);
        EDIFNet clkInNet = topCell.createNet(PE_CLOCK_INPUT_NET_NAME);
        clkInNet.addPortInst(clkPortInst);

        Site bufgSite = design.getDevice().getSite(DEFAULT_HD_CLK_SRC);
        if (bufgSite == null) {
            throw new RuntimeException("Unable to find PE BUFG site "
                    + DEFAULT_HD_CLK_SRC);
        }
        Cell bufg = ArrayBuilder.createBUFGCE(design, topCell, PE_CLOCK_BUFG_NAME, bufgSite);

        Net physicalClk = design.getNet("clk");
        if (physicalClk == null) {
            physicalClk = design.createNet("clk");
        }
        physicalClk.connect(bufg, "O");

        Net physicalClkIn = design.getNet(PE_CLOCK_INPUT_NET_NAME);
        if (physicalClkIn == null) {
            physicalClkIn = design.createNet(PE_CLOCK_INPUT_NET_NAME);
        }
        physicalClkIn.connect(bufg, "I");
    }

    private static void removePEClockBUFGCE(Design design) {
        ArrayBuilder.removeBUFGs(design);
        removeConstraintsContaining(design, PE_CLOCK_BUFG_NAME);
        removeConstraintsContaining(design, PE_CLOCK_INPUT_NET_NAME);
    }

    private static void removeConstraintsContaining(Design design, String token) {
        for (ConstraintGroup cg : ConstraintGroup.values()) {
            List<String> constraints = design.getXDCConstraints(cg);
            if (constraints.isEmpty()) continue;
            List<String> filtered = new ArrayList<>();
            for (String line : constraints) {
                if (!line.contains(token)) {
                    filtered.add(line);
                }
            }
            if (filtered.size() != constraints.size()) {
                design.setXDCConstraints(filtered, cg);
            }
        }
    }

    private static void addSLRCrossingTimingGuardbandConstraint(Design design, String topInstName,
                                                                 String bottomInstName,
                                                                 double maxDelay, double minDelay) {
        String topRegs = "[get_cells -hier -quiet -filter {NAME =~ " + topInstName
                + "/* && IS_SEQUENTIAL}]";
        String bottomRegs = "[get_cells -hier -quiet -filter {NAME =~ " + bottomInstName
                + "/* && IS_SEQUENTIAL}]";
        design.addXDCConstraint(ConstraintGroup.LATE,
                "set_max_delay " + maxDelay
                        + " -from " + topRegs + " -to " + bottomRegs);
        design.addXDCConstraint(ConstraintGroup.LATE,
                "set_min_delay " + minDelay
                        + " -from " + topRegs + " -to " + bottomRegs);
    }

    /**
     * Names of the routed, non-static, non-clock nets that have PIPs outside the tile
     * rectangle of the given pblock. Vivado's hold-fix detours do not always respect
     * CONTAIN_ROUTING: with hold timing enabled across the crossing, some padded nets
     * wander outside the spanning pblock's columns, where they would collide with the
     * neighbouring tiles once the module is relocated into an array (and break Module
     * relocation altogether).
     */
    /**
     * Rows the crossing's routing may extend beyond the spanning pblock: the SLL tiles the
     * crossing goes through sit at the SLR boundary and, depending on where the boundary
     * falls relative to the tile rows, a few of them lie just outside the pblock. Columns
     * get no tolerance, since the neighbouring array columns abut the pblock.
     */
    private static final int ROUTING_ROW_TOLERANCE = 8;

    private static List<String> netsLeavingRectangle(Design design, PBlock pblock) {
        int minCol = Integer.MAX_VALUE, maxCol = Integer.MIN_VALUE, minRow = Integer.MAX_VALUE, maxRow = Integer.MIN_VALUE;
        for (Tile t : pblock.getAllTiles()) {
            minCol = Math.min(minCol, t.getColumn());
            maxCol = Math.max(maxCol, t.getColumn());
            minRow = Math.min(minRow, t.getRow());
            maxRow = Math.max(maxRow, t.getRow());
        }
        minRow -= ROUTING_ROW_TOLERANCE;
        maxRow += ROUTING_ROW_TOLERANCE;
        List<String> names = new ArrayList<>();
        for (Net n : design.getNets()) {
            if (n.isStaticNet() || n.isClockNet() || !n.hasPIPs()) continue;
            for (PIP p : n.getPIPs()) {
                Tile t = p.getTile();
                if (t.getColumn() < minCol || t.getColumn() > maxCol || t.getRow() < minRow || t.getRow() > maxRow) {
                    names.add(n.getName());
                    break;
                }
            }
        }
        System.out.println("[SLR-CROSSING] " + names.size() + " nets route outside the spanning pblock (cols "
                + minCol + ".." + maxCol + ", rows " + minRow + ".." + maxRow + ")"
                + (names.isEmpty() ? "" : ": " + names.subList(0, Math.min(8, names.size()))));
        return names;
    }

    /**
     * Re-routes the spilled nets in Vivado with their hold checks false-pathed, so they
     * get short, contained routes (their hold is then left to the array's hold fixer);
     * every other net keeps its routing. Returns the re-read design.
     */
    private static Design rerouteSpilledNetsWithoutHoldPadding(String runDirectory, List<String> spilled) {
        Path in = Paths.get(runDirectory, "pblock0_best.dcp");
        Path out = Paths.get(runDirectory, "pblock0_best_contained.dcp");
        Path script = Paths.get(runDirectory, "contain_spilled_nets.tcl");
        Path log = Paths.get(runDirectory, "contain_spilled_nets.log");
        List<String> tcl = new ArrayList<>();
        tcl.add("open_checkpoint " + in);
        tcl.add("set names [list]");
        for (String name : spilled) {
            tcl.add("lappend names {" + name + "}");
        }
        tcl.add("set bad [get_nets -hier -quiet $names]");
        tcl.add("puts \"spilled nets resolved: [llength $bad] of " + spilled.size() + "\"");
        tcl.add("route_design -unroute -nets $bad");
        tcl.add("set_false_path -hold -through $bad");
        tcl.add("route_design -nets $bad");
        tcl.add("report_route_status");
        tcl.add("write_checkpoint -force " + out);
        FileTools.writeLinesToTextFile(tcl, script.toString());
        System.out.println("[SLR-CROSSING] re-routing " + spilled.size()
                + " spilled nets in Vivado with hold false-pathed (log " + log + ")");
        VivadoTools.runTcl(log, script, true);
        if (!out.toFile().exists()) {
            throw new RuntimeException("Vivado re-route of spilled nets did not produce " + out);
        }
        return Design.readCheckpoint(out.toString());
    }

    private static final String USER_CLOCK_ROOT = "USER_CLOCK_ROOT";
    private static final String USER_CLOCK_EXPANSION_WINDOW = "USER_CLOCK_EXPANSION_WINDOW";

    private static ClockRegion clockRegionOf(Tile t) {
        ClockRegion cr = t.getClockRegion();
        if (cr == null) {
            throw new RuntimeException("No clock region for pblock corner tile " + t);
        }
        return cr;
    }

    /**
     * Reproduces the array's clock tree in the PE run: the expansion window spans from
     * the bottom tile's clock region up to the requested rows above the top tile's, and
     * the root is pinned the requested rows above the top tile. See {@link Options}.
     */
    private static void addClockTreeConstraints(Design design, PBlock topPBlock, PBlock bottomPBlock,
                                                Options options) {
        if (options.getClockRootRowsAboveTop() < 0 && options.getClockWindowRowsAboveTop() < 0) {
            return;
        }
        // Clock-region extents of every placed clock load (tile cells and port flops), which
        // is what Vivado derives its own window from; the global buffer is excluded.
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, topY = Integer.MIN_VALUE;
        for (SiteInst si : design.getSiteInsts()) {
            if (si.getSite() == null || si.getSiteTypeEnum().name().startsWith("BUFG")) continue;
            ClockRegion cr = si.getTile().getClockRegion();
            if (cr == null) continue;
            minX = Math.min(minX, cr.getInstanceX());
            maxX = Math.max(maxX, cr.getInstanceX());
            minY = Math.min(minY, cr.getInstanceY());
            topY = Math.max(topY, cr.getInstanceY());
        }
        if (minX > maxX) {
            throw new RuntimeException("No placed clock loads to derive the clock expansion window from");
        }
        ClockRegion topLeft = clockRegionOf(topPBlock.getTopLeftTile());
        int numRows = design.getDevice().getNumOfClockRegionRows();
        if (options.getClockWindowRowsAboveTop() >= 0) {
            int maxY = Math.min(numRows - 1, topY + options.getClockWindowRowsAboveTop());
            String window = "CLOCKREGION_X" + minX + "Y" + minY + ":CLOCKREGION_X" + maxX + "Y" + maxY;
            System.out.println("[SLR-CROSSING] " + USER_CLOCK_EXPANSION_WINDOW + " " + window);
            design.addXDCConstraint(ConstraintGroup.LATE,
                    "set_property " + USER_CLOCK_EXPANSION_WINDOW + " " + window + " [get_nets clk]");
        }
        if (options.getClockRootRowsAboveTop() >= 0) {
            int x = options.getClockRootColumnX() >= 0 ? options.getClockRootColumnX() : maxX;
            int y = topY + options.getClockRootRowsAboveTop();
            String root = "X" + x + "Y" + y;
            if (design.getDevice().getClockRegion(root) == null) {
                throw new RuntimeException("Clock region " + root + " does not exist");
            }
            System.out.println("[SLR-CROSSING] " + USER_CLOCK_ROOT + " " + root + " (top tile clock region "
                    + topLeft.getName() + ")");
            design.addXDCConstraint(ConstraintGroup.LATE,
                    "set_property " + USER_CLOCK_ROOT + " " + root + " [get_nets clk]");
        }
    }

    private static void addSLRCrossingHoldFalsePathConstraint(Design design, String topInstName,
                                                               String bottomInstName) {
        String topRegs = "[get_cells -hier -quiet -filter {NAME =~ " + topInstName
                + "/* && IS_SEQUENTIAL}]";
        String bottomRegs = "[get_cells -hier -quiet -filter {NAME =~ " + bottomInstName
                + "/* && IS_SEQUENTIAL}]";
        design.addXDCConstraint(ConstraintGroup.LATE,
                "set_false_path -hold -from " + topRegs + " -to " + bottomRegs);
    }

    private static void addNoReplicateConstraintsForSLRCrossingNets(Design design, String topInstName,
                                                                     String bottomInstName) {
        String crossingNets = "[get_nets -hier -quiet -filter {NAME =~ " + topInstName + "/*}]";
        String crossingSegments = "[get_nets -quiet -segments " + crossingNets + "]";
        String crossingDrivers = "[get_cells -quiet -of_objects [get_pins -quiet -filter {DIRECTION == OUT} "
                + "-of_objects " + crossingSegments + "]]";
        design.addXDCConstraint(ConstraintGroup.LATE,
                "set_property DONT_TOUCH true " + crossingSegments);
        design.addXDCConstraint(ConstraintGroup.LATE,
                "set_property DONT_TOUCH true " + crossingDrivers);
    }

    public static Map<String, String> getBlackBoxToTopLevelMap(EDIFNetlist netlist, String blackBoxName) {
        Map<String, String> bbToTopLevelMap = new HashMap<>();
        EDIFCellInst bbCellInst = netlist.getHierCellInstFromName(blackBoxName).getInst();
        EDIFCell bbCell = bbCellInst.getCellType();
        for (EDIFPort port : bbCell.getPorts()) {
            EDIFPort topPort = null;
            for (int i : port.getBitBlastedIndices()) {
                EDIFPortInst portInst = bbCellInst.getPortInst(port.getPortInstNameFromPort(i));
                List<EDIFPortInst> topLevelPortInsts = portInst.getNet().getAllTopLevelPortInsts();
                int numSrcs = topLevelPortInsts.size();
                if (numSrcs == 0) {
                    continue;
                }
                assert numSrcs == 1;
                if (topPort != null && topPort != topLevelPortInsts.get(0).getPort()) {
                    throw new RuntimeException("Port insts don't match");
                }
                topPort = topLevelPortInsts.get(0).getPort();
            }
            if (topPort != null) {
                bbToTopLevelMap.put(port.getBusName(), topPort.getBusName());
            }
        }
        return bbToTopLevelMap;
    }

    public static void main(String[] args) {
        OptionParser p = createOptionParser();
        OptionSet options = p.parse(args);

        if (options.has(HELP_OPTS.get(0))) {
            MessageGenerator.printHeader("ArrayBuilderSLRCrossingCreator");
            System.out.println("Generates an optimized SLR crossing of a given kernel to be used in ArrayBuilder.");
            try {
                p.printHelpOn(System.out);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        String kernelDesignPath;
        if (options.has(INPUT_KERNEL_OPTS.get(0))) {
            kernelDesignPath = (String) options.valueOf(INPUT_KERNEL_OPTS.get(0));
        } else {
            throw new RuntimeException("No input design found. "
                    + "Please specify an input kernel (*.dcp) using options "
                    + INPUT_KERNEL_OPTS);
        }

        String topDesignPath;
        if (options.has(CROSSING_DESIGN_OPTS.get(0))) {
            topDesignPath = (String) options.valueOf(CROSSING_DESIGN_OPTS.get(0));
        } else {
            throw new RuntimeException("No top crossing design found. "
                    + "Please specify a top crossing design (*.dcp) using options "
                    + CROSSING_DESIGN_OPTS);
        }

        String outputPath;
        if (options.has(OUTPUT_OPTS.get(0))) {
            outputPath = (String) options.valueOf(OUTPUT_OPTS.get(0));
        } else {
            throw new RuntimeException("No output path provided. "
                    + "Please specify an output path (*.dcp) using options "
                    + OUTPUT_OPTS);
        }

        String sideMapPath;
        if (options.has(SIDE_MAP_OPTS.get(0))) {
            sideMapPath = (String) options.valueOf(SIDE_MAP_OPTS.get(0));
        } else {
            throw new RuntimeException("No side map path provided. "
                    + "Please specify a side map path using options "
                    + SIDE_MAP_OPTS);
        }

        String topInstName;
        if (options.has(TOP_INST_NAME_OPTS.get(0))) {
            topInstName = (String) options.valueOf(TOP_INST_NAME_OPTS.get(0));
        } else {
            throw new RuntimeException("No top instance name provided. "
                    + "Please specify a top instance name using options "
                    + TOP_INST_NAME_OPTS);
        }

        String bottomInstName;
        if (options.has(BOT_INST_NAME_OPTS.get(0))) {
            bottomInstName = (String) options.valueOf(BOT_INST_NAME_OPTS.get(0));
        } else {
            throw new RuntimeException("No top instance name provided. "
                    + "Please specify a top instance name using options "
                    + BOT_INST_NAME_OPTS);
        }

        Path kernelFile = Paths.get(kernelDesignPath);
        Design kernelDesign = Design.readCheckpoint(kernelFile);

        Path topFile = Paths.get(topDesignPath);
        Design topDesign = Design.readCheckpoint(topFile);
        Map<EDIFPort, PBlockSide> sideMap = InlineFlopTools.parseSideMap(kernelDesign.getNetlist(), sideMapPath);
        createSLRCrossing(kernelDesign, topDesign, sideMap, topInstName, bottomInstName, outputPath);
    }
}
