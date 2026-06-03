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

package com.xilinx.rapidwright.rapidsa;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.RelocatableTileRectangle;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.tools.ArrayBuilder;
import com.xilinx.rapidwright.design.tools.ArrayBuilderConfig;
import com.xilinx.rapidwright.design.tools.FlopTreeTools;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.SLR;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rapidsa.components.DrainTile;
import com.xilinx.rapidwright.rapidsa.components.EdgeBufferTile;
import com.xilinx.rapidwright.rapidsa.components.GEMMTile;
import com.xilinx.rapidwright.rapidsa.components.BufferTile;
import com.xilinx.rapidwright.rapidsa.components.MM2SNOCChannel;
import com.xilinx.rapidwright.rapidsa.components.RapidComponent;
import com.xilinx.rapidwright.rapidsa.components.S2MMNOCChannel;
import com.xilinx.rapidwright.rwroute.PartialCUFR;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.VivadoTools;
import joptsimple.OptionParser;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class RapidSA {
    private static final int ID_WIDTH = 8;
    private static final int DCU_UNITS_PER_TILE = 4;
    private static final int MULTI_SA_BASE_ROW_OFFSET = 5;
    private static final int MULTI_SA_DOWNSTREAM_ROW_OFFSET = 40;

    public static void main(String[] args) {
        String partName = "xcv80-lsva4737-2MHP-e-S";
        Part part = PartNameTools.getPart(partName);

        OptionParser parser = new OptionParser();
        parser.accepts("precompile", "Run precompilation of all RapidSA components and exit");
        parser.accepts("precompile-slr-crossings-only", "Generate only SLR crossing artifacts for applicable precompiled RapidSA components and exit");
        parser.accepts("precompile-no-explore", "When precompiling, run a single Explore/Explore PerformanceExplorer implementation (clk uncertainty 0.0) instead of the full 60-run sweep");
        parser.accepts("rows", "Number of GEMM tile rows in the systolic array").withRequiredArg().ofType(Integer.class).defaultsTo(8);
        parser.accepts("cols", "Number of GEMM tile columns in the systolic array").withRequiredArg().ofType(Integer.class).defaultsTo(8);
        parser.accepts("route", "After building the array, run RWRoute partial route + HoldFixer in-process and write a routed DCP");
        parser.accepts("vivado-route", "After building the array, shell out to Vivado to route the design and write a routed DCP");
        parser.accepts("report-timing", "After routing, open the routed DCP in Vivado and run report_timing_summary + report_timing -nworst 10. Combined with --vivado-route in a single Vivado session.");
        parser.accepts("flop-tree-depth", "Flop tree depth for control fanout (default: scales with array size)").withRequiredArg().ofType(Integer.class);
        parser.accepts("max-depth-per-slr", "Max flop tree depth within a single SLR (default: scales with flop-tree-depth)").withRequiredArg().ofType(Integer.class);
        parser.accepts("handshake-chain-depth", "Flop chain depth for s2mm_start/s2mm_done handshake nets that span the full array (default: scales with nRows)").withRequiredArg().ofType(Integer.class);
        parser.accepts("accel-json", "Path to accel.json from pytorch-bridge; selects multi-SA flow").withRequiredArg();
        parser.accepts("out-dcp", "Output DCP path for --accel-json multi-SA flow").withRequiredArg().defaultsTo("multi_sa.dcp");
        parser.accepts("single-mm2s", "Test: attach/place only SA[0]'s MM2S (skip MM2S for SAs[1..]); their weight EB chain s_data inputs are left dangling.");
        parser.accepts("disable-global-control", "Test: tie off output_wr_en, accum_shift, and MM2S/S2MM handshake; skip global control flop trees/chains.");
        parser.accepts("disable-hold-timing", "Test: add a design-level set_false_path -hold between all registers before route/timing.");
        parser.accepts("help", "Print help message");
        joptsimple.OptionSet options = parser.parse(args);

        if (options.has("help")) {
            try {
                parser.printHelpOn(System.out);
            } catch (java.io.IOException e) {
                e.printStackTrace();
            }
            return;
        }

        if (options.has("precompile-slr-crossings-only")) {
            RapidSAPrecompile.precompileRapidSAComponents("RapidSA", part, 2.0, true,
                    options.has("precompile-no-explore"));
            return;
        }

        if (options.has("precompile")) {
            RapidSAPrecompile.precompileRapidSAComponents("RapidSA", part, 2.0, false,
                    options.has("precompile-no-explore"));
            return;
        }

        if (options.has("accel-json")) {
            try {
                runMultiSAFlow(options, partName);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            return;
        }

        int nRows = (Integer) options.valueOf("rows");
        int nCols = (Integer) options.valueOf("cols");
        boolean disableGlobalControl = options.has("disable-global-control");
        boolean disableHoldTiming = options.has("disable-hold-timing");
        if (disableGlobalControl) {
            System.out.println("** --disable-global-control: tying off output_wr_en, accum_shift, "
                    + "and MM2S/S2MM handshake; skipping control flop trees/chains.");
        }
        if (disableHoldTiming) {
            System.out.println("** --disable-hold-timing: adding set_false_path -hold between all registers.");
        }

        CodePerfTracker t = new CodePerfTracker(ArrayBuilder.class.getName());
        t.start("Init");

        Design sa = RapidSANetlistBuilder.createSystolicArrayNetlist(nRows, nCols, partName, "RapidSA");

        GEMMTile tile = new GEMMTile(4, 4);

        String compOutputDir = "RapidSA" + File.separator + tile.getComponentName();
        Design kernel = Design.readCheckpoint(compOutputDir + File.separator + "pnr.dcp");
        EDIFTools.removeVivadoBusPreventionAnnotations(kernel.getNetlist());

        ArrayBuilderConfig config = new ArrayBuilderConfig(kernel, sa);
        config.setTopClockName("clk");
        config.setKernelClockName("clk");
        config.setOutOfContext(false);
        config.setRouteClock(false);
        config.setFlipPlacementHorizontally(true);
        config.setRowOffset(5);
        config.setColumnOffset(1);

        String slrCrossingDir = compOutputDir + File.separator + RapidSAPrecompile.SLR_CROSSING_RUN_DIR;
        File slrCrossingDcp = new File(slrCrossingDir + File.separator + RapidSAPrecompile.SLR_CROSSING_DCP_NAME);
        File slrCrossingSynthDcp = new File(slrCrossingDir + File.separator + RapidSAPrecompile.SLR_CROSSING_SYNTH_DCP_NAME);
        if (slrCrossingDcp.exists() && slrCrossingSynthDcp.exists()) {
            Design slrCrossing = Design.readCheckpoint(slrCrossingDcp.getPath());
            EDIFTools.removeVivadoBusPreventionAnnotations(slrCrossing.getNetlist());
            Design slrCrossingSynth = Design.readCheckpoint(slrCrossingSynthDcp.getPath());
            EDIFTools.removeVivadoBusPreventionAnnotations(slrCrossingSynth.getNetlist());
            config.setSlrCrossing(slrCrossing);
            config.setSlrCrossingSynth(slrCrossingSynth);
            config.setSlrCrossingTopInstName(RapidSAPrecompile.SLR_CROSSING_TOP_INST_NAME);
            config.setSlrCrossingBottomInstName(RapidSAPrecompile.SLR_CROSSING_BOTTOM_INST_NAME);
            System.out.println("  ** Using precompiled GEMM SLR crossing from " + slrCrossingDir);
        } else {
            System.out.println("  ** No precompiled GEMM SLR crossing found at " + slrCrossingDir
                    + " (array will fail if it crosses SLRs)");
        }

        // Create array builder with config
        ArrayBuilder ab = new ArrayBuilder(config);
        ab.initializeArrayBuilder();

        ab.createArray();

        // Place Weight Edge Buffer tiles above the array
        Module weightEbModule = loadRelocatableModule("RapidSA", new EdgeBufferTile(8, EdgeBufferTile.Type.WEIGHT), ab.getArray());
        placeWeightDCUTiles(ab, nCols, weightEbModule);

        // Place Input Edge Buffer tiles right of the array
        Module inputEbModule = loadRelocatableModule("RapidSA", new EdgeBufferTile(8, EdgeBufferTile.Type.INPUT), ab.getArray());
        placeInputDCUTiles(ab, nRows, inputEbModule);

        // Place DrainTiles below the array
        int accumCount = 4 * 4; // nCols * nRows for GEMM tile accumulator count
        Module drainModule = loadRelocatableModule("RapidSA", new DrainTile(accumCount, 8), ab.getArray());
        placeDrainTiles(ab, nCols, drainModule, accumCount);

        Design arrayDesign = ab.getArray();

        String[] inputEbRoots = computeFirstEbPerSLR(arrayDesign, "input_eb_y", nRows);
        String[] weightEbRoots = computeFirstEbPerSLR(arrayDesign, "weight_eb_x", nCols);

        RapidSANetlistBuilder.attachS2MMNOCChannel(arrayDesign, "RapidSA", "s2mm", "drain_x0");
        // Primary MM2S in SLR 0 drives weight EBs, accum_shift, output_wr_en, done,
        // s2mm handshake, AND only the SLR 0 input EB root.
        RapidSANetlistBuilder.attachMM2SNOCChannel(arrayDesign, "RapidSA", "mm2s",
                createTileInstanceNames(nRows, nCols),
                weightEbRoots,
                new String[]{inputEbRoots[0]},
                createIndexedInstanceNames("drain_x", nCols),
                "done",
                ab.getMergedTileMap());
        if (disableGlobalControl) {
            RapidSANetlistBuilder.tieOffDirectFlowControlPins(arrayDesign,
                    createTileInstanceNames(nRows, nCols),
                    createIndexedInstanceNames("drain_x", nCols),
                    ab.getMergedTileMap(),
                    "mm2s",
                    "s2mm");
        } else {
            RapidSANetlistBuilder.connectIngressEgressChannelHandshake(arrayDesign, "mm2s", "s2mm");
        }
        // One additional input-EB-only MM2S per remaining SLR root.
        for (int i = 1; i < inputEbRoots.length; i++) {
            RapidSANetlistBuilder.attachSecondaryInputMM2SNOCChannel(arrayDesign, "RapidSA",
                    "mm2s_" + i, inputEbRoots[i]);
        }

        // Look up the SLR each input EB root landed in, so we can place the
        // secondary MM2S(es) in the same SLR as the EB they drive.
        SLR[] inputEbRootSLRs = new SLR[inputEbRoots.length];
        for (int i = 0; i < inputEbRoots.length; i++) {
            for (ModuleInst mi : arrayDesign.getModuleInsts()) {
                if (mi.getName().equals(inputEbRoots[i]) && mi.isPlaced()) {
                    inputEbRootSLRs[i] = mi.getPlacement().getTile().getSLR();
                    break;
                }
            }
            System.out.println("[MM2S-PLACE] inputEbRoots[" + i + "]=" + inputEbRoots[i]
                    + " landed in SLR id=" + (inputEbRootSLRs[i] == null ? "NULL" : inputEbRootSLRs[i].getId()));
        }

        // Place primary MM2S NOC channel at the top-right of the array
        Module mm2sModule = loadRelocatableModule("RapidSA", new MM2SNOCChannel(), arrayDesign);
        placeMM2SNOCChannel(ab, mm2sModule);
        // Place each secondary MM2S at the top-right of its SLR
        for (int i = 1; i < inputEbRoots.length; i++) {
            placeMM2SNOCChannelInSLR(ab, mm2sModule, "mm2s_" + i, inputEbRootSLRs[i]);
        }

        // Place S2MM channel at the top-right of the array
        Module s2mmModule = loadRelocatableModule("RapidSA", new S2MMNOCChannel(), arrayDesign);
        placeS2MMChannel(ab, s2mmModule);

        // Update registers before flattening (deep hierarchy won't survive flatten)
        // Set byte lane registers for Edge Buffer tiles
        int unitsPerTile = 4;
        for (int col = 0; col < nCols; col++) {
            int colOffset = col * unitsPerTile;
            for (int u = 0; u < unitsPerTile; u++) {
                EdgeBufferTile.setByteLane(arrayDesign, "weight_eb_x" + col, u, (colOffset + u) % 16);
                EdgeBufferTile.setWordIndex(arrayDesign, "weight_eb_x" + col, u, (colOffset + u) / 16);
            }
        }
        for (int row = 0; row < nRows; row++) {
            int rowOffset = row * unitsPerTile;
            for (int u = 0; u < unitsPerTile; u++) {
                EdgeBufferTile.setByteLane(arrayDesign, "input_eb_y" + row, u, (rowOffset + u) % 16);
                EdgeBufferTile.setWordIndex(arrayDesign, "input_eb_y" + row, u, (rowOffset + u) / 16);
            }
        }

        // Update FSM registers
        // TODO: uncomment after verifying hierarchy paths in Vivado
//        String fsmPrefix = "mm2s/mm2s_channel_i/sa_fsm_0/inst/inst";
//        SAControlFSM.setSAWidth(arrayDesign, fsmPrefix, 8);
//        SAControlFSM.setSAHeight(arrayDesign, fsmPrefix, 8);
//        SAControlFSM.setKDim(arrayDesign, fsmPrefix, 8);
//        SAControlFSM.setAccumShiftPipelineLatency(arrayDesign, fsmPrefix, flopTreeDepth);
//        SAControlFSM.setOutputWrPipelineLatency(arrayDesign, fsmPrefix, flopTreeDepth);
//        SAControlFSM.setDcuChainLatency(arrayDesign, fsmPrefix, dcuChainLatency);
        // Defaults derived from array size:
        //   maxDepthPerSLR  = ceil(log4(totalSinks))         (logical fanout per SLR)
        //   chainSlack      = max(3, ceil(nRows / 3))        (pacing for source<->boundary
        //                                                     and boundary<->dest hops; one
        //                                                     stage per ~3 array rows, so on
        //                                                     xcv80 the source-to-boundary
        //                                                     hop fits a per-flop span of
        //                                                     ~40-50 tile rows).
        //   flopTreeDepth   = maxDepthPerSLR + 2 + chainSlack (2 reserved for SLR crossing)
        // Picking maxDepthPerSLR independently of flopTreeDepth ensures there is
        // always a positive chain budget (flopTreeDepth - 2 - maxDepthPerSLR) that
        // insertFlopTreeForNet can split between source-side and dest-side pacing.
        int totalSinks = nRows * nCols;
        int defaultMaxDepthPerSLR = Math.max(2,
                (int) Math.ceil(Math.log(totalSinks) / Math.log(4)));
        int chainSlack = Math.max(3, (nRows + 2) / 3);
        int defaultFlopTreeDepth = defaultMaxDepthPerSLR + 2 + chainSlack;

        int flopTreeDepth = options.has("flop-tree-depth")
                ? (Integer) options.valueOf("flop-tree-depth")
                : defaultFlopTreeDepth;
        int maxDepthPerSLR = options.has("max-depth-per-slr")
                ? (Integer) options.valueOf("max-depth-per-slr")
                : defaultMaxDepthPerSLR;
        int chainBudget = Math.max(0, flopTreeDepth - 2 - maxDepthPerSLR);
        // Handshake chain (s2mm_start / s2mm_done) spans roughly the full array
        // height (MM2S near top of array, S2MM near bottom), so it needs roughly
        // 2x the per-half pacing. Default ~1 stage per 2 array rows, floor 5.
        int defaultHandshakeChainDepth = Math.max(5, (nRows + 1) / 2);
        int handshakeChainDepth = options.has("handshake-chain-depth")
                ? (Integer) options.valueOf("handshake-chain-depth")
                : defaultHandshakeChainDepth;
        if (!disableGlobalControl) {
            System.out.println("** Flop tree: depth=" + flopTreeDepth
                    + ", maxDepthPerSLR=" + maxDepthPerSLR
                    + ", chainBudget=" + chainBudget
                    + ", handshakeChainDepth=" + handshakeChainDepth
                    + " (defaults from " + nRows + "x" + nCols
                    + ": depth=" + defaultFlopTreeDepth
                    + ", maxDepthPerSLR=" + defaultMaxDepthPerSLR
                    + ", handshakeChainDepth=" + defaultHandshakeChainDepth + ")");
        }

        // Build the no-go bbox list BEFORE flatten — flattenDesign() collapses
        // the post-flatten ModuleInsts (weight EBs, input EBs, drain tiles, MM2S,
        // S2MM placements) so we can't query them afterward. The pre-flatten
        // GEMM tile + SLR-crossing bboxes come from ArrayBuilder's retained list.
        List<RelocatableTileRectangle> noGoBboxes = new ArrayList<>(ab.getPlacedArrayBoundingBoxes());
        for (ModuleInst mi : arrayDesign.getModuleInsts()) {
            if (mi.isPlaced()) {
                Module mod = mi.getModule();
                noGoBboxes.add(mod.getBoundingBox()
                        .getCorresponding(mi.getPlacement().getTile(), mod.getAnchor().getTile()));
            }
        }

        arrayDesign.flattenDesign();
        EDIFTools.uniqueifyNetlist(arrayDesign);
//        DesignTools.createMissingSitePinInsts(arrayDesign);

        if (!disableGlobalControl) {
            FlopTreeTools.insertFlopTreeForNet(arrayDesign, "sa_accum_shift", "clk", flopTreeDepth, maxDepthPerSLR, noGoBboxes);
            FlopTreeTools.insertFlopTreeForNet(arrayDesign, "output_wr_en", "clk", flopTreeDepth, maxDepthPerSLR, noGoBboxes);
            Set<SiteInst> chainSiteInsts = new HashSet<>();
            Net s2mmStartNet = arrayDesign.getNet("s2mm/s2mm_start");
            // Filter to only remote sinks (in s2mm, not mm2s)
            List<EDIFHierPortInst> s2mmStartRemoteSinks = new ArrayList<>();
            for (EDIFHierPortInst p : s2mmStartNet.getLogicalHierNet().getLeafHierPortInsts(false)) {
                if (p.getFullHierarchicalInstName().startsWith("s2mm/")) {
                    s2mmStartRemoteSinks.add(p);
                }
            }
            FlopTreeTools.insertFlopChain(arrayDesign, s2mmStartNet, "clk", handshakeChainDepth,
                    s2mmStartRemoteSinks, chainSiteInsts, noGoBboxes);
            Net s2mmDoneNet = arrayDesign.getNet("mm2s/s2mm_done");
            // Filter to only remote sinks (in mm2s, not s2mm)
            List<EDIFHierPortInst> s2mmDoneRemoteSinks = new ArrayList<>();
            for (EDIFHierPortInst p : s2mmDoneNet.getLogicalHierNet().getLeafHierPortInsts(false)) {
                if (p.getFullHierarchicalInstName().startsWith("mm2s/")) {
                    s2mmDoneRemoteSinks.add(p);
                }
            }
            FlopTreeTools.insertFlopChain(arrayDesign, s2mmDoneNet, "clk", handshakeChainDepth,
                    s2mmDoneRemoteSinks, chainSiteInsts, noGoBboxes);

            for (SiteInst si : chainSiteInsts) {
                si.routeSite();
            }
        }

        // Insert the top-level BUFGCE just before write/route, so it doesn't
        // perturb the earlier peripheral placement (which iterates the design's
        // SiteInsts to derive array top/bottom row constraints) and so the
        // BUFGCE site doesn't sit inside the design throughout the build.
        insertTopLevelBUFGCE(arrayDesign);

        // Add clock constraint
        arrayDesign.addXDCConstraint("create_clock -period 2.0 -name clk [get_ports clk_in]");
        if (disableHoldTiming) {
            arrayDesign.addXDCConstraint("set_false_path -hold -from [all_registers] -to [all_registers]");
        }

        arrayDesign.setDesignOutOfContext(true);
        String baseDcpName = "systolic_array_" + nRows + "x" + nCols;
        arrayDesign.writeCheckpoint(baseDcpName + ".dcp");

        if (options.has("route")) {
            ArrayBuilder.unrouteStaticNets(arrayDesign);
            System.out.println("** Running RWRoute partial route + HoldFixer on " + baseDcpName + ".dcp (softPreserve=true)");
            PartialCUFR.routeDesignWithUserDefinedArguments(arrayDesign,
                    new String[]{
                            "--useUTurnNodes",
                            "--nonTimingDriven",
                    },
                    /*pinsToRoute=*/ null,
                    /*softPreserve=*/ false);
//             HoldFixer holdFixer = new HoldFixer(arrayDesign, "clk");
//             holdFixer.fixHoldViolations();

            arrayDesign.writeCheckpoint(baseDcpName + "_routed.dcp");
            System.out.println("** Wrote " + baseDcpName + "_routed.dcp");
        }

        if (options.has("vivado-route")) {
            Path workdir = Paths.get(".").toAbsolutePath().normalize();
            Path inputDcp = workdir.resolve(baseDcpName + ".dcp");
            Path outputDcp = workdir.resolve(baseDcpName + "_vivado_routed.dcp");
            Path tclLog = workdir.resolve(baseDcpName + "_vivado_route.log");
            StringBuilder tcl = new StringBuilder()
                    .append("open_checkpoint {").append(inputDcp).append("}; ")
                    .append("route_design -preserve -no_psir; ")
                    .append("write_checkpoint -force {").append(outputDcp).append("}; ")
                    .append("report_route_status; ");
            if (options.has("report-timing")) {
                // Same Vivado session — no need to re-load the DCP.
                tcl.append(reportTimingTcl());
            }
            System.out.println("** Running Vivado route_design on " + inputDcp
                    + (options.has("report-timing") ? " (with report-timing in same session)" : ""));
            VivadoTools.runTcl(tclLog, tcl.toString(), true);
            System.out.println("** Wrote " + outputDcp);
        }

        // Standalone report-timing pass (skipped if --vivado-route already
        // did it in the same session above).
        if (options.has("report-timing") && !options.has("vivado-route")) {
            runReportTiming(baseDcpName, options);
        }

        t.stop();
        t.printSummary();
    }

    /**
     * Returns the Tcl snippet that runs the timing report. Used both inline
     * inside the --vivado-route session and from a standalone Vivado session.
     */
    private static String reportTimingTcl() {
        return "puts {===== report_timing_summary =====}; "
                + "report_timing_summary; "
                + "puts {===== report_timing -nworst 10 (setup) =====}; "
                + "report_timing -nworst 10 -setup; "
                + "puts {===== report_timing -nworst 10 (hold) =====}; "
                + "report_timing -nworst 10 -hold; ";
    }

    /**
     * Picks the most recently-produced routed DCP (preferring vivado &gt; lsf &gt;
     * local RWRoute &gt; unrouted) and opens it in a Vivado session to run the
     * timing report. Output streams live so timing tables print to the terminal.
     */
    private static void runReportTiming(String baseDcpName, joptsimple.OptionSet options) {
        Path workdir = Paths.get(".").toAbsolutePath().normalize();
        // Pick the routed DCP that exists, in order of preference.
        Path[] candidates = new Path[]{
                workdir.resolve(baseDcpName + "_vivado_routed.dcp"),
                workdir.resolve(baseDcpName + "_lsf_routed.dcp"),
                workdir.resolve(baseDcpName + "_routed.dcp"),
                workdir.resolve(baseDcpName + ".dcp"),
        };
        Path dcp = null;
        for (Path c : candidates) {
            if (java.nio.file.Files.exists(c)) {
                dcp = c;
                break;
            }
        }
        if (dcp == null) {
            throw new RuntimeException("No DCP found to report timing on (looked for "
                    + java.util.Arrays.toString(candidates) + ")");
        }

        Path tclLog = workdir.resolve(baseDcpName + "_report_timing.log");
        String tcl = "open_checkpoint {" + dcp + "}; " + reportTimingTcl();
        System.out.println("** Running Vivado report_timing on " + dcp);
        VivadoTools.runTcl(tclLog, tcl, true);
        System.out.println("** Timing report log: " + tclLog);
    }

    /**
     * Surgically replaces the top-level {@code clk} input port (which currently
     * drives the {@code clk} fanout net) with a BUFGCE: a new {@code clk_in}
     * top-level input drives the BUFG.I, the BUFG.O drives the existing
     * {@code clk} net (preserving all sub-module clk fan-out), and CE is tied
     * to VCC. The BUFG is physically placed at {@code BUFGCE_X2Y0}.
     */
    private static void insertTopLevelBUFGCE(Design design) {
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell topCell = netlist.getTopCell();

        EDIFNet clkNet = topCell.getNet("clk");
        if (clkNet == null) {
            throw new RuntimeException("Expected a top-level 'clk' net to replace with BUFGCE output");
        }

        // Detach the old top-level 'clk' port from the clk fanout net and
        // remove it; the buffer's output will drive that net instead. The
        // existing per-instance clk port-insts on the net are preserved.
        EDIFPort oldClkPort = topCell.getPort("clk");
        if (oldClkPort != null) {
            EDIFPortInst oldPortInst = clkNet.getPortInst(null, "clk");
            if (oldPortInst != null) {
                clkNet.removePortInst(oldPortInst);
            }
            topCell.removePort(oldClkPort);
        }

        // Create the physical BUFGCE (handles intra-site CEINV/IINV SitePIPs,
        // intra-site VCC route to CE, the CE SitePinInst on VCC, and the macro
        // pin mapping fix-ups in one call).
        Site bufgSite = design.getDevice().getSite("BUFGCE_X2Y0");
        if (bufgSite == null) {
            throw new RuntimeException("Site BUFGCE_X2Y0 not found on device " + design.getPartName());
        }
        Cell bufg = ArrayBuilder.createBUFGCE(design, topCell, "bufg", bufgSite);
        EDIFCellInst bufgInst = bufg.getEDIFCellInst();

        // BUFG.O drives the existing clk net (which already has all the fanout).
        // Use the physical Net.connect so the source SitePinInst is created on
        // the physical net (otherwise RWRoute will not see the clock as having
        // a routable source and will skip clock routing entirely).
        Net physicalClk = design.getNet("clk");
        if (physicalClk == null) {
            physicalClk = design.createNet("clk");
        }
        physicalClk.connect(bufg, "O");

        // New top-level clk_in input drives BUFG.I. Same story for the input
        // side — use Net.connect so a SitePinInst is registered on BUFG.I.
        EDIFPort clkInPort = topCell.createPort("clk_in", EDIFDirection.INPUT, 1);
        Net physicalClkIn = design.createNet("clk_in");
        physicalClkIn.getLogicalNet().createPortInst(clkInPort);
        physicalClkIn.connect(bufg, "I");

        System.out.println("** Inserted top-level BUFGCE 'bufg' at BUFGCE_X2Y0 (clk_in -> BUFG.I, BUFG.O -> clk fanout, CE -> VCC)");
    }

    private static String[] createIndexedInstanceNames(String prefix, int count) {
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = prefix + i;
        }
        return names;
    }

    /**
     * For instances named {@code prefix0..prefix(count-1)} placed in the design,
     * returns the name of the lowest-indexed instance that landed in each SLR.
     * The result is sorted by EB index ascending, so {@code [0]} is the
     * topmost EB (lowest logical index) and matches where the primary
     * peripheral channel would be placed at the top of the array.
     */
    private static String[] computeFirstEbPerSLR(Design design, String prefix, int count) {
        Map<String, ModuleInst> miByName = new HashMap<>();
        for (ModuleInst mi : design.getModuleInsts()) {
            miByName.put(mi.getName(), mi);
        }
        TreeMap<Integer, Integer> firstIndexPerSLR = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            ModuleInst mi = miByName.get(prefix + i);
            if (mi == null || !mi.isPlaced()) continue;
            SLR slr = mi.getPlacement().getTile().getSLR();
            firstIndexPerSLR.putIfAbsent(slr.getId(), i);
        }
        return firstIndexPerSLR.values().stream()
                .sorted()
                .map(i -> prefix + i)
                .toArray(String[]::new);
    }

    private static String[][] createTileInstanceNames(int nRows, int nCols) {
        String[][] names = new String[nRows][nCols];
        for (int row = 0; row < nRows; row++) {
            for (int col = 0; col < nCols; col++) {
                names[row][col] = "tile_x" + col + "y" + row;
            }
        }
        return names;
    }

    /**
     * Loads a component's pnr.dcp and creates a relocatable Module, removing
     * clock routing, BUFGs, and orphaned static tie-off cells that inflate
     * the bounding box.
     */
    private static Module loadRelocatableModule(String precompileDir, RapidComponent component,
                                                 Design targetDesign) {
        String dcpPath = precompileDir + File.separator
                + component.getComponentName() + File.separator + "pnr.dcp";
        return loadRelocatableModuleFromDcp(dcpPath, component.getComponentName(), component.getClkName(),
                targetDesign);
    }

    private static Module loadRelocatableModuleFromDcp(String dcpPath, String label, String clkName,
                                                       Design targetDesign) {
        return loadRelocatableModuleFromDcp(dcpPath, label, clkName, targetDesign, true);
    }

    private static Module loadRelocatableModuleFromDcp(String dcpPath, String label, String clkName,
                                                       Design targetDesign, boolean removeLockedSiteInsts) {
        Design pnrDesign = Design.readCheckpoint(dcpPath);
        EDIFTools.removeVivadoBusPreventionAnnotations(pnrDesign.getNetlist());

        ArrayBuilder.removeBUFGs(pnrDesign);
        Net clkNet = pnrDesign.getNet(clkName);
        if (clkNet != null) {
            clkNet.unroute();
        }
        // Remove SiteInsts that only contain <LOCKED> cells or are static sources
        List<SiteInst> toRemove = new ArrayList<>();
        int staticSourceCount = 0;
        int allLockedCount = 0;
        for (SiteInst si : pnrDesign.getSiteInsts()) {
            if (si.getName().startsWith(SiteInst.STATIC_SOURCE)) {
                toRemove.add(si);
                staticSourceCount++;
                continue;
            }
            if (removeLockedSiteInsts) {
                boolean allLocked = !si.getCells().isEmpty();
                for (Cell c : si.getCells()) {
                    if (!c.getName().equals("<LOCKED>")) {
                        allLocked = false;
                        break;
                    }
                }
                if (allLocked) {
                    toRemove.add(si);
                    allLockedCount++;
                }
            }
        }
        for (SiteInst si : toRemove) {
            pnrDesign.removeSiteInst(si);
        }
        System.out.println("** loadRelocatableModule[" + label
                + "]: removed " + staticSourceCount + " STATIC_SOURCE SiteInsts, "
                + allLockedCount + " all-<LOCKED> SiteInsts (total SiteInsts before removal: "
                + (pnrDesign.getSiteInsts().size() + toRemove.size()) + ")");

        Module module = new Module(pnrDesign, false);
        module.calculateAllValidPlacements(targetDesign.getDevice());
        return module;
    }

    /**
     * Places WeightDCU module instances physically above the array,
     * aligned with each column's top-row centroid.
     */
    private static void placeWeightDCUTiles(ArrayBuilder ab, int nCols, Module dcuModule) {
        Design arrayDesign = ab.getArray();
        Map<Pair<Integer, Integer>, Site> centroidMap = ab.getLogicalToCentroidMap();

        RelocatableTileRectangle moduleBoundingBox = dcuModule.getBoundingBox();
        List<RelocatableTileRectangle> placedBoundingBoxes = new ArrayList<>();

        // Compute the array's top row from all placed sites
        int arrayTopRow = Integer.MAX_VALUE;
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            arrayTopRow = Math.min(arrayTopRow, si.getTile().getRow());
        }

        for (int col = 0; col < nCols; col++) {
            Site targetCentroid = centroidMap.get(new Pair<>(col, 0));
            String instName = "weight_eb_x" + col;

            ModuleInst mi = arrayDesign.createModuleInst(instName, dcuModule);

            Site bestAnchor = null;
            int bestDist = Integer.MAX_VALUE;

            for (Site anchor : dcuModule.getAllValidPlacements()) {
                RelocatableTileRectangle candidateBB = moduleBoundingBox
                        .getCorresponding(anchor.getTile(), dcuModule.getAnchor().getTile());

                // Bottom of DCU bounding box must be above top of array
                if (candidateBB.getMaxRow() >= arrayTopRow) {
                    continue;
                }

                // Check no overlap with any placed bounding box
                boolean overlaps = false;
                for (RelocatableTileRectangle existing : placedBoundingBoxes) {
                    if (existing.overlaps(candidateBB)) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) continue;

                int dist = Math.abs(anchor.getTile().getColumn() - targetCentroid.getTile().getColumn())
                         + Math.abs(anchor.getTile().getRow() - targetCentroid.getTile().getRow());
                if (dist < bestDist) {
                    bestDist = dist;
                    bestAnchor = anchor;
                }
            }

            if (bestAnchor == null) {
                throw new RuntimeException("Could not find valid placement for " + instName);
            }

            if (!mi.place(bestAnchor, false, false)) {
                throw new RuntimeException("Failed to place " + instName + " at " + bestAnchor);
            }

            placedBoundingBoxes.add(moduleBoundingBox
                    .getCorresponding(bestAnchor.getTile(), dcuModule.getAnchor().getTile()));

            System.out.println("  ** PLACED WeightEB: " + instName + " at " + bestAnchor);
        }
    }

    /**
     * Places DrainTile module instances physically below the array,
     * aligned with each column's bottom-row centroid.
     */
    private static void placeDrainTiles(ArrayBuilder ab, int nCols, Module drainModule, int accumCount) {
        Design arrayDesign = ab.getArray();
        Map<Pair<Integer, Integer>, Site> centroidMap = ab.getLogicalToCentroidMap();

        RelocatableTileRectangle moduleBoundingBox = drainModule.getBoundingBox();
        List<RelocatableTileRectangle> placedBoundingBoxes = new ArrayList<>();

        // Compute the array's bottom row from all placed sites
        int arrayBottomRow = Integer.MIN_VALUE;
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            arrayBottomRow = Math.max(arrayBottomRow, si.getTile().getRow());
        }

        // Find the number of rows from the centroid map
        int nRows = 0;
        for (Pair<Integer, Integer> key : centroidMap.keySet()) {
            nRows = Math.max(nRows, key.getSecond() + 1);
        }

        for (int col = 0; col < nCols; col++) {
            Site targetCentroid = centroidMap.get(new Pair<>(col, nRows - 1));
            String instName = "drain_x" + col;

            ModuleInst mi = arrayDesign.createModuleInst(instName, drainModule);

            Site bestAnchor = null;
            int bestDist = Integer.MAX_VALUE;

            for (Site anchor : drainModule.getAllValidPlacements()) {
                RelocatableTileRectangle candidateBB = moduleBoundingBox
                        .getCorresponding(anchor.getTile(), drainModule.getAnchor().getTile());

                // Top of drain bounding box must be below bottom of array
                if (candidateBB.getMinRow() <= arrayBottomRow) {
                    continue;
                }

                // Check no overlap with any placed bounding box
                boolean overlaps = false;
                for (RelocatableTileRectangle existing : placedBoundingBoxes) {
                    if (existing.overlaps(candidateBB)) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) continue;

                int dist = Math.abs(anchor.getTile().getColumn() - targetCentroid.getTile().getColumn())
                         + Math.abs(anchor.getTile().getRow() - targetCentroid.getTile().getRow());
                if (dist < bestDist) {
                    bestDist = dist;
                    bestAnchor = anchor;
                }
            }

            if (bestAnchor == null) {
                throw new RuntimeException("Could not find valid placement for " + instName);
            }

            if (!mi.place(bestAnchor, false, false)) {
                throw new RuntimeException("Failed to place " + instName + " at " + bestAnchor);
            }

            placedBoundingBoxes.add(moduleBoundingBox
                    .getCorresponding(bestAnchor.getTile(), drainModule.getAnchor().getTile()));

            // Set column element count (number of accumulators from this column's GEMM tiles)
            DrainTile.setColumnElements(arrayDesign, instName, accumCount, 8);

            // Set external upstream element count (accumulator elements from upstream drain tiles)
            // drain_x0 is the output end, drain_x{nCols-1} has no upstream
            int upstreamElements = (nCols - 1 - col) * accumCount;
            DrainTile.setExternalUpstreamElements(arrayDesign, instName, upstreamElements, 8);

            System.out.println("  ** PLACED DrainTile: " + instName + " at " + bestAnchor
                    + " (col_elem=" + accumCount + ", ext_ups=" + upstreamElements + ")");
        }
    }

    /**
     * Places InputDCU module instances physically right of the array,
     * aligned with each row's last-column centroid.
     */
    private static void placeInputDCUTiles(ArrayBuilder ab, int nRows, Module dcuModule) {
        Design arrayDesign = ab.getArray();
        Map<Pair<Integer, Integer>, Site> centroidMap = ab.getLogicalToCentroidMap();

        RelocatableTileRectangle moduleBoundingBox = dcuModule.getBoundingBox();
        List<RelocatableTileRectangle> placedBoundingBoxes = new ArrayList<>();
        List<String> placedBoundingBoxNames = new ArrayList<>();

        // Seed with the GEMM tile + SLR-crossing bboxes ArrayBuilder retained pre-flatten.
        for (RelocatableTileRectangle bb : ab.getPlacedArrayBoundingBoxes()) {
            placedBoundingBoxes.add(bb);
            placedBoundingBoxNames.add("array_bbox");
        }
        // Then add bboxes for any post-flatten ModuleInsts (weight EBs placed earlier in this run).
        for (ModuleInst existingMi : arrayDesign.getModuleInsts()) {
            if (existingMi.isPlaced()) {
                Module mod = existingMi.getModule();
                placedBoundingBoxes.add(mod.getBoundingBox()
                        .getCorresponding(existingMi.getPlacement().getTile(), mod.getAnchor().getTile()));
                placedBoundingBoxNames.add(existingMi.getName());
            }
        }

        // Find number of columns from centroid map
        int nCols = 0;
        for (Pair<Integer, Integer> key : centroidMap.keySet()) {
            nCols = Math.max(nCols, key.getFirst() + 1);
        }

        // Compute the array's rightmost column from GEMM tile centroids only
        // (not from weight EBs or other modules placed above/below)
        int arrayRightCol = Integer.MIN_VALUE;
        for (Site s : centroidMap.values()) {
            arrayRightCol = Math.max(arrayRightCol, s.getTile().getColumn());
        }

        // Build occupied tiles set for site-level conflict checking
        Set<Tile> occupiedTiles = new HashSet<>();
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            occupiedTiles.add(si.getTile());
        }

        // Compute the row offset from the module anchor to the module's centroid
        int anchorRow = dcuModule.getAnchor().getTile().getRow();
        int bbCenterRow = (moduleBoundingBox.getMinRow() + moduleBoundingBox.getMaxRow()) / 2;
        int anchorToCentroidRowOffset = bbCenterRow - anchorRow;

        for (int row = nRows - 1; row >= 0; row--) {
            Site targetCentroid = centroidMap.get(new Pair<>(nCols - 1, row));
            String instName = "input_eb_y" + row;

            ModuleInst mi = arrayDesign.createModuleInst(instName, dcuModule);

            Site bestAnchor = null;
            int bestDist = Integer.MAX_VALUE;

            for (Site anchor : dcuModule.getAllValidPlacements()) {
                RelocatableTileRectangle candidateBB = moduleBoundingBox
                        .getCorresponding(anchor.getTile(), dcuModule.getAnchor().getTile());

                // Left edge must be right of GEMM array
                if (candidateBB.getMinColumn() <= arrayRightCol) {
                    continue;
                }

                // Check no overlap with any placed bounding box
                boolean overlaps = false;
                for (RelocatableTileRectangle existing : placedBoundingBoxes) {
                    if (existing.overlaps(candidateBB)) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) continue;

                // Check site-level conflicts with occupied tiles
                boolean siteConflict = false;
                for (SiteInst modSi : dcuModule.getSiteInsts()) {
                    Site newSite = dcuModule.getCorrespondingSite(modSi, anchor);
                    if (newSite != null && occupiedTiles.contains(newSite.getTile())) {
                        siteConflict = true;
                        break;
                    }
                }
                if (siteConflict) continue;

                // Use module centroid (not anchor) for distance calculation
                int moduleCentroidRow = anchor.getTile().getRow() + anchorToCentroidRowOffset;
                int dist = Math.abs(anchor.getTile().getColumn() - targetCentroid.getTile().getColumn())
                         + Math.abs(moduleCentroidRow - targetCentroid.getTile().getRow());
                if (dist < bestDist) {
                    bestDist = dist;
                    bestAnchor = anchor;
                }
            }

            if (bestAnchor == null) {
                throw new RuntimeException("Could not find valid placement for " + instName);
            }

            if (!mi.place(bestAnchor, false, false)) {
                throw new RuntimeException("Failed to place " + instName + " at " + bestAnchor);
            }

            RelocatableTileRectangle placedBb = moduleBoundingBox
                    .getCorresponding(bestAnchor.getTile(), dcuModule.getAnchor().getTile());
            placedBoundingBoxes.add(placedBb);
            placedBoundingBoxNames.add(instName);

            // Add newly placed sites to occupied set for subsequent iterations
            for (SiteInst modSi : dcuModule.getSiteInsts()) {
                Site newSite = dcuModule.getCorrespondingSite(modSi, bestAnchor);
                if (newSite != null) {
                    occupiedTiles.add(newSite.getTile());
                }
            }

            System.out.println("  ** PLACED InputEB: " + instName + " at " + bestAnchor);

            if (row == nRows - 1) {
                System.out.println("  >> GEMM bottom-row centroid row: " + targetCentroid.getTile().getRow()
                        + "  |  input_eb_y" + row + " placed row: " + bestAnchor.getTile().getRow());
            }
        }
    }

    /**
     * Places the MM2S NOC channel at the top-right of the array.
     */
    private static void placeMM2SNOCChannel(ArrayBuilder ab, Module mm2sModule) {
        Design arrayDesign = ab.getArray();
        RelocatableTileRectangle moduleBoundingBox = mm2sModule.getBoundingBox();

        // Collect all existing placed bounding boxes
        List<RelocatableTileRectangle> existingBBs = new ArrayList<>();
        for (ModuleInst existingMi : arrayDesign.getModuleInsts()) {
            if (existingMi.isPlaced()) {
                Module mod = existingMi.getModule();
                existingBBs.add(mod.getBoundingBox()
                        .getCorresponding(existingMi.getPlacement().getTile(), mod.getAnchor().getTile()));
            }
        }

        // Build occupied tiles set
        Set<Tile> occupiedTiles = new HashSet<>();
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            occupiedTiles.add(si.getTile());
        }

        // Target: top-right corner of the array
        int targetRow = Integer.MAX_VALUE;
        int targetCol = Integer.MIN_VALUE;
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            targetRow = Math.min(targetRow, si.getTile().getRow());
            targetCol = Math.max(targetCol, si.getTile().getColumn());
        }

        ModuleInst mi = arrayDesign.createModuleInst("mm2s", mm2sModule);
        Site bestAnchor = null;
        int bestDist = Integer.MAX_VALUE;

        for (Site anchor : mm2sModule.getAllValidPlacements()) {
            RelocatableTileRectangle candidateBB = moduleBoundingBox
                    .getCorresponding(anchor.getTile(), mm2sModule.getAnchor().getTile());

            // No overlap with existing bounding boxes
            boolean overlaps = false;
            for (RelocatableTileRectangle existing : existingBBs) {
                if (existing.overlaps(candidateBB)) { overlaps = true; break; }
            }
            if (overlaps) continue;

            // No site-level conflicts
            boolean siteConflict = false;
            for (SiteInst modSi : mm2sModule.getSiteInsts()) {
                Site newSite = mm2sModule.getCorrespondingSite(modSi, anchor);
                if (newSite != null && occupiedTiles.contains(newSite.getTile())) {
                    siteConflict = true; break;
                }
            }
            if (siteConflict) continue;

            int dist = Math.abs(anchor.getTile().getColumn() - targetCol)
                     + Math.abs(anchor.getTile().getRow() - targetRow);
            if (dist < bestDist) {
                bestDist = dist;
                bestAnchor = anchor;
            }
        }

        if (bestAnchor == null) {
            throw new RuntimeException("Could not find valid placement for mm2s");
        }
        if (!mi.place(bestAnchor, false, false)) {
            throw new RuntimeException("Failed to place mm2s at " + bestAnchor);
        }

        System.out.println("  ** PLACED MM2S NOC: mm2s at " + bestAnchor);
    }

    /**
     * Places an additional MM2S NOC channel instance at the top-right of a
     * specific SLR. Uses {@link ArrayBuilder#getPlacedArrayBoundingBoxes()}
     * to avoid the GEMM tile / SLR-crossing footprints that flattenDesign()
     * has already removed from the queryable ModuleInst list.
     */
    private static void placeMM2SNOCChannelInSLR(ArrayBuilder ab, Module mm2sModule,
                                                 String instName, SLR slrFilter) {
        Design arrayDesign = ab.getArray();
        RelocatableTileRectangle moduleBoundingBox = mm2sModule.getBoundingBox();

        List<RelocatableTileRectangle> existingBBs = new ArrayList<>(ab.getPlacedArrayBoundingBoxes());
        for (ModuleInst existingMi : arrayDesign.getModuleInsts()) {
            if (existingMi.isPlaced()) {
                Module mod = existingMi.getModule();
                existingBBs.add(mod.getBoundingBox()
                        .getCorresponding(existingMi.getPlacement().getTile(), mod.getAnchor().getTile()));
            }
        }

        Set<Tile> occupiedTiles = new HashSet<>();
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            occupiedTiles.add(si.getTile());
        }

        // Top-right corner of the array within the requested SLR
        // Compare SLRs by id to avoid object-identity mismatches.
        int slrFilterId = slrFilter.getId();
        int targetRow = Integer.MAX_VALUE;
        int targetCol = Integer.MIN_VALUE;
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            if (si.getTile().getSLR().getId() != slrFilterId) continue;
            targetRow = Math.min(targetRow, si.getTile().getRow());
            targetCol = Math.max(targetCol, si.getTile().getColumn());
        }

        ModuleInst mi = arrayDesign.createModuleInst(instName, mm2sModule);
        Site bestAnchor = null;
        int bestDist = Integer.MAX_VALUE;

        for (Site anchor : mm2sModule.getAllValidPlacements()) {
            if (anchor.getTile().getSLR().getId() != slrFilterId) continue;
            RelocatableTileRectangle candidateBB = moduleBoundingBox
                    .getCorresponding(anchor.getTile(), mm2sModule.getAnchor().getTile());

            boolean overlaps = false;
            for (RelocatableTileRectangle existing : existingBBs) {
                if (existing.overlaps(candidateBB)) { overlaps = true; break; }
            }
            if (overlaps) continue;

            boolean siteConflict = false;
            for (SiteInst modSi : mm2sModule.getSiteInsts()) {
                Site newSite = mm2sModule.getCorrespondingSite(modSi, anchor);
                if (newSite != null && occupiedTiles.contains(newSite.getTile())) {
                    siteConflict = true; break;
                }
            }
            if (siteConflict) continue;

            int dist = Math.abs(anchor.getTile().getColumn() - targetCol)
                     + Math.abs(anchor.getTile().getRow() - targetRow);
            if (dist < bestDist) {
                bestDist = dist;
                bestAnchor = anchor;
            }
        }

        if (bestAnchor == null) {
            throw new RuntimeException("Could not find valid placement for " + instName + " in SLR " + slrFilter.getId());
        }
        if (!mi.place(bestAnchor, false, false)) {
            throw new RuntimeException("Failed to place " + instName + " at " + bestAnchor);
        }

        System.out.println("  ** PLACED MM2S NOC: " + instName + " at " + bestAnchor + " (SLR " + slrFilter.getId() + ")");
    }

    /**
     * Places the S2MM channel at the bottom-right side of the array.
     */
    private static void placeS2MMChannel(ArrayBuilder ab, Module s2mmModule) {
        Design arrayDesign = ab.getArray();
        RelocatableTileRectangle moduleBoundingBox = s2mmModule.getBoundingBox();

        // Collect all existing placed bounding boxes
        List<RelocatableTileRectangle> existingBBs = new ArrayList<>();
        for (ModuleInst existingMi : arrayDesign.getModuleInsts()) {
            if (existingMi.isPlaced()) {
                Module mod = existingMi.getModule();
                existingBBs.add(mod.getBoundingBox()
                        .getCorresponding(existingMi.getPlacement().getTile(), mod.getAnchor().getTile()));
            }
        }

        // Build occupied tiles set
        Set<Tile> occupiedTiles = new HashSet<>();
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            occupiedTiles.add(si.getTile());
        }

        // Target: bottom-right corner of the array
        int targetRow = Integer.MIN_VALUE;
        int targetCol = Integer.MIN_VALUE;
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            targetRow = Math.max(targetRow, si.getTile().getRow());
            targetCol = Math.max(targetCol, si.getTile().getColumn());
        }

        ModuleInst mi = arrayDesign.createModuleInst("s2mm", s2mmModule);
        Site bestAnchor = null;
        int bestDist = Integer.MAX_VALUE;

        for (Site anchor : s2mmModule.getAllValidPlacements()) {
            RelocatableTileRectangle candidateBB = moduleBoundingBox
                    .getCorresponding(anchor.getTile(), s2mmModule.getAnchor().getTile());

            // No overlap with existing bounding boxes
            boolean overlaps = false;
            for (RelocatableTileRectangle existing : existingBBs) {
                if (existing.overlaps(candidateBB)) { overlaps = true; break; }
            }
            if (overlaps) continue;

            // No site-level conflicts
            boolean siteConflict = false;
            for (SiteInst modSi : s2mmModule.getSiteInsts()) {
                Site newSite = s2mmModule.getCorrespondingSite(modSi, anchor);
                if (newSite != null && occupiedTiles.contains(newSite.getTile())) {
                    siteConflict = true; break;
                }
            }
            if (siteConflict) continue;

            int dist = Math.abs(anchor.getTile().getColumn() - targetCol)
                     + Math.abs(anchor.getTile().getRow() - targetRow);
            if (dist < bestDist) {
                bestDist = dist;
                bestAnchor = anchor;
            }
        }

        if (bestAnchor == null) {
            throw new RuntimeException("Could not find valid placement for s2mm");
        }
        if (!mi.place(bestAnchor, false, false)) {
            throw new RuntimeException("Failed to place s2mm at " + bestAnchor);
        }

        System.out.println("  ** PLACED S2MM: s2mm at " + bestAnchor);
    }

    // =========================================================================
    // Multi-SA flow (--accel-json)
    // =========================================================================

    /**
     * Bounds of one SA segment computed from the union of its placed GEMM
     * kernel bounding boxes. Used as a per-SA reference for placing
     * peripherals (WeightEB, InputEB, Drain, MM2S, S2MM, ReluTile) without
     * colliding with sites that belong to a different SA in the same
     * Design.
     *
     * IMPORTANT: deriving these bounds from the GEMM kernel module's actual
     * footprint matters. Using the centroid map alone produces bounds ~half
     * the kernel's height/width too small in each direction (since the
     * centroid sits in the middle of the kernel's extent). Peripheral
     * placements that only check {@code bb.getMinRow() > centroidBottom}
     * end up landing inside the array's actual cell footprint.
     */
    private static class ArrayBounds {
        final int topRow, bottomRow, leftCol, rightCol;
        ArrayBounds(int top, int bot, int left, int right) {
            this.topRow = top; this.bottomRow = bot;
            this.leftCol = left; this.rightCol = right;
        }
        static ArrayBounds from(List<RelocatableTileRectangle> placedBboxes) {
            if (placedBboxes == null || placedBboxes.isEmpty()) {
                throw new IllegalArgumentException(
                        "ArrayBounds.from: empty placed bbox list");
            }
            int top = Integer.MAX_VALUE, bot = Integer.MIN_VALUE;
            int left = Integer.MAX_VALUE, right = Integer.MIN_VALUE;
            for (RelocatableTileRectangle bb : placedBboxes) {
                top = Math.min(top, bb.getMinRow());
                bot = Math.max(bot, bb.getMaxRow());
                left = Math.min(left, bb.getMinColumn());
                right = Math.max(right, bb.getMaxColumn());
            }
            return new ArrayBounds(top, bot, left, right);
        }
    }

    /**
     * Multi-SA orchestration. Reads an accel.json layer chain, builds the
     * combined netlist with SA-prefixed segments and ReluTile bridges, runs
     * a per-SA {@link ArrayBuilder} pass constrained to one SLR, places the
     * peripheral modules per-SA, places ReluTiles between SAs, and writes
     * an unrouted DCP. Routing/flop-tree insertion are deferred to a later
     * iteration.
     */
    private static void runMultiSAFlow(joptsimple.OptionSet options, String partName)
            throws java.io.IOException {
        String jsonPath = (String) options.valueOf("accel-json");
        String outDcp = (String) options.valueOf("out-dcp");
        String precompileDir = "RapidSA";
        boolean singleMM2S = options.has("single-mm2s");
        if (singleMM2S) {
            System.out.println("** --single-mm2s: only SA[0] gets a MM2S; SAs[1..] weight EB roots will dangle.");
        }

        AccelConfig cfg = AccelConfig.parse(Paths.get(jsonPath));
        List<AccelConfig.LinearLayer> linears = cfg.getLinearLayers();
        System.out.println("** Multi-SA flow: " + jsonPath
                + " (" + linears.size() + " linear layers, output -> " + outDcp + ")");
        for (int i = 0; i < linears.size(); i++) {
            AccelConfig.LinearLayer l = linears.get(i);
            int er = RapidSANetlistBuilder.effectiveNRows(l, i);
            int ec = RapidSANetlistBuilder.effectiveNCols(l, i);
            System.out.printf("   SA[%d] %dx%d  paddedBatch=%d paddedTileIn=%d paddedTileOut=%d%n",
                    i, er, ec, l.paddedBatch, l.paddedTileIn, l.paddedTileOut);
        }
        Device device = Device.getDevice(partName);
        int slrCount = device.getSLRs().length;
        if (linears.size() > slrCount) {
            throw new RuntimeException("accel.json has " + linears.size()
                    + " linear layers but device " + partName + " has only "
                    + slrCount + " SLRs (one-SA-per-SLR layout)");
        }

        CodePerfTracker t = new CodePerfTracker(RapidSA.class.getName() + ".multiSA");
        t.start("Build netlist");

        // 1. Combined netlist with sa{i}_ prefixed segments. Only the GEMM
        //    tiles, EB chains, and drain chain instances exist at this stage
        //    — the MM2S, S2MM, and ReluTile black boxes are NOT created
        //    yet. They get attached after the per-SA createArray passes
        //    below, so the encrypted-IP s2mm/mm2s wrappers are only seen by
        //    flattenDesign() once (the final flatten) instead of once per
        //    createArray. With the earlier "attach first" ordering, multiple
        //    flattens over the still-unplaced encrypted-IP black boxes left
        //    thousands of internal IP leaf cells unmaterialized after the
        //    final mi.place().
        Design design = RapidSANetlistBuilder.createMultiSANetlist(
                linears, partName, precompileDir);

        // 2. Load the kernel Design and the peripheral Modules (one each,
        //    shared across all SAs — RapidWright's ModuleImpls requires
        //    pointer-equal netlists across instances).
        GEMMTile gemm44 = new GEMMTile(4, 4);
        EdgeBufferTile weightEbComp = new EdgeBufferTile(4, EdgeBufferTile.Type.WEIGHT);
        EdgeBufferTile inputEbComp = new EdgeBufferTile(4, EdgeBufferTile.Type.INPUT);
        DrainTile drainComp = new DrainTile(4, 8);

        // The kernel Design's routing/SiteInsts get consumed when an
        // ArrayBuilder stamps it. So we re-read a fresh kernel from disk
        // for each SA's pass — see {@link #loadFreshKernelDesign}.
        String kernelDcp = precompileDir + File.separator
                + gemm44.getComponentName() + File.separator + "pnr.dcp";

        // EB/Drain/MM2S/S2MM/ReluTile Modules are loaded JUST BEFORE the
        // first placement that uses each, mirroring direct-flow's load-then-
        // place pattern. Loading them upfront (before createArray) was
        // dropping ~2k ROUTETHRU cells from placed SiteInsts, which is what
        // produced the "X sites failed to restore" XDEF error in Vivado.
        Module[] weightEbModuleHolder = new Module[1];
        Module[] inputEbModuleHolder = new Module[1];
        Module[] drainModuleHolder = new Module[1];

        // 3. Phase 1: per-SA ArrayBuilder.createArray() in order. Each call
        //    flattens the design — doing them all up-front means peripheral
        //    placements (Phase 2 below) happen on a stable design with no
        //    intervening flattens, which is what Module/ModuleInst placement
        //    needs in order to actually materialize SiteInsts.
        List<ArrayBuilder> perSaBuilders = new ArrayList<>();
        List<ArrayBounds> perSaBounds = new ArrayList<>();
        List<RelocatableTileRectangle> globalNoGo = new ArrayList<>();

        int last = linears.size() - 1;
        for (int i = 0; i < linears.size(); i++) {
            AccelConfig.LinearLayer layer = linears.get(i);
            String prefix = "sa" + i + "_";
            // SLR mapping: data flows top-to-bottom (SA[0] -> SA[1] -> ... -> SA[last]).
            // SLR 0 sits at the BOTTOM of the device on Versal, so SA[0] goes in
            // the TOPMOST SLR (highest id) and SA[last] in SLR 0.
            int targetSlr = slrCount - 1 - i;
            t.stop().start("SA[" + i + "] createArray (SLR " + targetSlr + ")");
            // Fresh kernel per SA: ArrayBuilder consumes the kernel Design's
            // routing/SiteInsts when it stamps tiles, so reusing the same
            // kernel across SAs leaves later SAs' GEMM tiles unrouted.
            Design kernel = Design.readCheckpoint(kernelDcp);
            EDIFTools.removeVivadoBusPreventionAnnotations(kernel.getNetlist());
            ArrayBuilderConfig saConfig = new ArrayBuilderConfig(kernel, design);
            saConfig.setTopClockName("clk");
            saConfig.setKernelClockName("clk");
            saConfig.setOutOfContext(false);
            saConfig.setRouteClock(false);
            saConfig.setFlipPlacementHorizontally(true);
            saConfig.setRowOffset(i == 0 ? MULTI_SA_BASE_ROW_OFFSET : MULTI_SA_DOWNSTREAM_ROW_OFFSET);
            saConfig.setColumnOffset(1);
            saConfig.setTargetSLR(targetSlr);
            saConfig.setInstanceNamePrefix(prefix);

            ArrayBuilder ab = new ArrayBuilder(saConfig);
            ab.initializeArrayBuilder();
            ab.createArray();
            perSaBuilders.add(ab);
            ArrayBounds bounds = ArrayBounds.from(ab.getPlacedArrayBoundingBoxes());
            perSaBounds.add(bounds);
            globalNoGo.addAll(ab.getPlacedArrayBoundingBoxes());
            System.out.println("  ** SA[" + i + "] placed in SLR " + targetSlr
                    + ": rows " + bounds.topRow + ".." + bounds.bottomRow
                    + ", cols " + bounds.leftCol + ".." + bounds.rightCol);
        }

        // 4. Phase 2a: place EBs and Drains FIRST, while their netlist black
        //    boxes are still untouched by any attach* call. This mirrors the
        //    direct single-SA flow's order (place EB/Drain -> attach MM2S
        //    -> place MM2S). Placing a Module whose corresponding black box
        //    already has port-insts wired to nets (from a prior attach*)
        //    leaves the resulting SiteInsts in a state that fails
        //    XDEF-restore in Vivado later.
        t.stop().start("Place EBs and Drains");
        for (int i = 0; i < linears.size(); i++) {
            AccelConfig.LinearLayer layer = linears.get(i);
            int nRows = RapidSANetlistBuilder.effectiveNRows(layer, i);
            int nCols = RapidSANetlistBuilder.effectiveNCols(layer, i);
            String prefix = "sa" + i + "_";
            boolean isRotated = (i != 0);
            boolean hasInputEB = !isRotated;
            boolean hasDrain = (i == last);
            ArrayBuilder ab = perSaBuilders.get(i);
            ArrayBounds bounds = perSaBounds.get(i);

            if (!isRotated) {
                if (weightEbModuleHolder[0] == null)
                    weightEbModuleHolder[0] = loadRelocatableModule(precompileDir, weightEbComp, design);
                placeWeightEBsAboveForSA(ab, prefix, nCols, weightEbModuleHolder[0],
                        bounds, globalNoGo);
            } else {
                // Rotated SAs: netlist uses the InputEdgeBuffer precompile
                // (see buildArraySegment). Use the InputEB Module for
                // placement so the relocation matches the cell type.
                if (inputEbModuleHolder[0] == null)
                    inputEbModuleHolder[0] = loadRelocatableModule(precompileDir, inputEbComp, design);
                placeWeightEBsRightForSA(ab, prefix, nRows, inputEbModuleHolder[0],
                        bounds, globalNoGo);
            }
            if (hasInputEB) {
                if (inputEbModuleHolder[0] == null)
                    inputEbModuleHolder[0] = loadRelocatableModule(precompileDir, inputEbComp, design);
                placeInputEBsRightForSA(ab, prefix, nRows, inputEbModuleHolder[0],
                        bounds, globalNoGo);
            }
            if (hasDrain) {
                if (drainModuleHolder[0] == null)
                    drainModuleHolder[0] = loadRelocatableModule(precompileDir, drainComp, design);
                placeDrainsBelowForSA(ab, prefix, nCols, drainModuleHolder[0],
                        bounds, globalNoGo);
            }
        }

        // 5. Phase 2b: now that EB/Drain ModuleInsts are placed, attach the
        //    MM2S/S2MM/ReluTile black boxes and the netlist wiring. The
        //    claimPortInst calls inside attach* now operate on materialized
        //    SiteInst-backed cells (matching direct-flow behavior).
        t.stop().start("Attach MM2S/S2MM/ReluTile netlist");
        attachAllMM2SAndReluAndS2MM(design, precompileDir, linears, singleMM2S);

        // Load MM2S/S2MM/ReluTile Modules ONLY AFTER attach* has migrated
        // their synth cell types into the netlist. This ordering matches
        // direct-flow exactly. Loading these Modules before attach means
        // the pnr cell type gets registered first, then attach migrates
        // the synth cell type, and the resulting cell-type state confuses
        // Vivado's XDEF restore.
        Module mm2sModule = loadRelocatableModule(precompileDir,
                new com.xilinx.rapidwright.rapidsa.components.MM2SNOCChannel(), design);
        Module s2mmModule = loadRelocatableModule(precompileDir,
                new com.xilinx.rapidwright.rapidsa.components.S2MMNOCChannel(), design);
        Module reluModule = loadRelocatableModule(precompileDir,
                new com.xilinx.rapidwright.rapidsa.components.ReluTile(4, 8), design);
        BufferTile bufferTile = new BufferTile(4, 8);
        String bufferSlrCrossingDcp = precompileDir + File.separator
                + bufferTile.getComponentName() + File.separator
                + RapidSAPrecompile.SLR_CROSSING_RUN_DIR + File.separator
                + RapidSAPrecompile.SLR_CROSSING_DCP_NAME;
        Module bufferSlrCrossingModule = loadRelocatableModuleFromDcp(bufferSlrCrossingDcp,
                bufferTile.getComponentName() + "." + RapidSAPrecompile.SLR_CROSSING_RUN_DIR,
                bufferTile.getClkName(), design, false);

        // 6. Phase 2c: place the now-attached MM2S/ReluTile/S2MM ModuleInsts.
        t.stop().start("Place MM2S/S2MM/ReluTile");
        for (int i = 0; i < linears.size(); i++) {
            String prefix = "sa" + i + "_";
            ArrayBuilder ab = perSaBuilders.get(i);
            ArrayBounds bounds = perSaBounds.get(i);
            // Skip MM2S placement when --single-mm2s and this isn't SA[0].
            if (!singleMM2S || i == 0) {
                placeMM2SForSA(ab, prefix, mm2sModule, bounds, globalNoGo);
            }
        }
        for (int i = 0; i + 1 < linears.size(); i++) {
            AccelConfig.LinearLayer prev = linears.get(i);
            int prevNCols = RapidSANetlistBuilder.effectiveNCols(prev, i);
            placeReluTilesBetweenSAs(perSaBuilders.get(i), "sa" + i,
                    "sa" + (i + 1), prevNCols, reluModule,
                    perSaBounds.get(i), globalNoGo);
            placeBufferSLRCrossingsBetweenSAs(perSaBuilders.get(i), "sa" + i,
                    "sa" + (i + 1), prevNCols, bufferSlrCrossingModule,
                    perSaBounds.get(i + 1), globalNoGo);
        }
        placeS2MMForSA(perSaBuilders.get(last), "s2mm", s2mmModule,
                perSaBounds.get(last), globalNoGo);

        t.stop().start("Finalize");

        // 7. Flatten & finalize.
        design.flattenDesign();
        EDIFTools.uniqueifyNetlist(design);
        // Do not run DesignTools.createMissingSitePinInsts(design) globally
        // here. The NOC channel modules contain encrypted IP: their physical
        // cells are visible, but their logical leaf netlists are intentionally
        // opaque. A global missing-pin inference pass can fabricate boundary
        // pins on encrypted internal nets and Vivado then reports unplaced
        // cells/pins under s2mm when restoring the XDEF. The direct single-SA
        // flow also avoids the global pass; FlopTreeTools creates and routes
        // the pins it needs for inserted control flops below.

        // 8. Insert flop trees on each SA's per-SA scoped control nets, then
        //    routeSite() on the touched SiteInsts. This is the same pattern
        //    direct-flow uses; without it, intra-site IMR ROUTETHRU cells
        //    aren't materialized and Vivado's XDEF restore fails on hundreds
        //    of array sites.
        int totalSinks = 0;
        for (AccelConfig.LinearLayer l : linears) {
            int er = RapidSANetlistBuilder.effectiveNRows(l, 0); // for a per-SA estimate
            int ec = RapidSANetlistBuilder.effectiveNCols(l, 0);
            totalSinks = Math.max(totalSinks, er * ec);
        }
        int defaultMaxDepthPerSLR = Math.max(2,
                (int) Math.ceil(Math.log(Math.max(1, totalSinks)) / Math.log(4)));
        int chainSlack = 3;
        int defaultFlopTreeDepth = defaultMaxDepthPerSLR + 2 + chainSlack;
        for (int i = 0; i < linears.size(); i++) {
            String prefix = "sa" + i + "_";
            for (String netName : new String[]{prefix + "sa_accum_shift", prefix + "output_wr_en"}) {
                if (design.getNetlist().getTopCell().getNet(netName) == null) continue;
                if (design.getNet(netName) == null) {
                    // Wrap the existing EDIF net with a physical Net so
                    // FlopTreeTools can operate on it. Direct-flow's nets
                    // get a physical Net created elsewhere; multi-flow's
                    // prefixed nets don't, hence this explicit step.
                    design.createNet(netName);
                }
                System.out.println("[multi-finalize] FlopTree on " + netName);
                FlopTreeTools.insertFlopTreeForNet(design, netName, "clk",
                        defaultFlopTreeDepth, defaultMaxDepthPerSLR, globalNoGo);
            }
        }

        insertTopLevelBUFGCE(design);
        design.addXDCConstraint("create_clock -period 2.0 -name clk [get_ports clk_in]");
        design.setDesignOutOfContext(true);

        design.writeCheckpoint(outDcp);
        System.out.println("** Wrote " + outDcp);

        t.stop();
        t.printSummary();
    }

    /** Wires per-SA MM2S, ReluTile bridges, and the single S2MM into the netlist. */
    private static void attachAllMM2SAndReluAndS2MM(Design design, String precompileDir,
                                                    List<AccelConfig.LinearLayer> linears,
                                                    boolean singleMM2S) {
        int last = linears.size() - 1;
        for (int i = 0; i < linears.size(); i++) {
            // Test mode: only attach SA[0]'s MM2S.
            if (singleMM2S && i != 0) continue;
            AccelConfig.LinearLayer l = linears.get(i);
            String prefix = "sa" + i + "_";
            boolean hasInputEB = (i == 0);
            boolean hasDrain = (i == last);
            int nRows = RapidSANetlistBuilder.effectiveNRows(l, i);
            int nCols = RapidSANetlistBuilder.effectiveNCols(l, i);
            RapidSANetlistBuilder.attachMM2SForSA(design, precompileDir,
                    prefix + "mm2s", prefix, nRows, nCols, hasInputEB, hasDrain);
        }
        for (int i = 0; i + 1 < linears.size(); i++) {
            AccelConfig.LinearLayer prev = linears.get(i);
            AccelConfig.LinearLayer next = linears.get(i + 1);
            int prevR = RapidSANetlistBuilder.effectiveNRows(prev, i);
            int prevC = RapidSANetlistBuilder.effectiveNCols(prev, i);
            int nextR = RapidSANetlistBuilder.effectiveNRows(next, i + 1);
            int nextC = RapidSANetlistBuilder.effectiveNCols(next, i + 1);
            RapidSANetlistBuilder.attachReluBetweenSAsWithBufferCrossings(design, precompileDir,
                    "sa" + i + "_", prevR, prevC,
                    "sa" + (i + 1) + "_", nextR, nextC);
        }
        RapidSANetlistBuilder.attachS2MMNOCChannel(design, precompileDir,
                "s2mm", "sa" + last + "_drain_x0");
        // Handshake needs sa{last}_mm2s, which only exists in non-singleMM2S mode.
        if (!singleMM2S || last == 0) {
            RapidSANetlistBuilder.connectIngressEgressChannelHandshake(design,
                    "sa" + last + "_mm2s", "s2mm");
        } else {
            // s2mm.s2mm_start would otherwise dangle.
            RapidSANetlistBuilder.tieOffS2MMHandshake(design, "s2mm");
        }
        // Tie off every SA's MM2S-driven inputs that we skipped attaching.
        if (singleMM2S) {
            for (int i = 0; i < linears.size(); i++) {
                if (i == 0) continue;
                AccelConfig.LinearLayer l = linears.get(i);
                int nRows = RapidSANetlistBuilder.effectiveNRows(l, i);
                int nCols = RapidSANetlistBuilder.effectiveNCols(l, i);
                String prefix = "sa" + i + "_";
                boolean hasInputEB = (i == 0); // false here since i>0
                boolean hasDrain = (i == last);
                RapidSANetlistBuilder.tieOffSAControlPins(design, prefix,
                        nRows, nCols, hasInputEB, hasDrain);
            }
        }
    }

    // ----- Per-SA placement helpers (multi-SA flow only) -----

    /**
     * Picks the closest valid anchor for {@code module} that satisfies
     * (a) module's full bbox stays within the target SLR
     * (b) bbox does not overlap any existing no-go bbox
     * (c) per-site no overlap with already-occupied tiles
     * (d) {@code edgeCheck} returns true (geometric edge constraint, e.g.
     *     "must be above arrayTopRow")
     * Distance is Manhattan to {@code targetTile}.
     */
    private static Site findBestAnchor(Module module, Site targetSite, int targetSlrId,
                                       List<RelocatableTileRectangle> noGoBboxes,
                                       Set<Tile> occupiedTiles,
                                       java.util.function.Predicate<RelocatableTileRectangle> edgeCheck) {
        RelocatableTileRectangle moduleBox = module.getBoundingBox();
        Tile targetTile = targetSite.getTile();
        Site best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Site anchor : module.getAllValidPlacements()) {
            if (targetSlrId >= 0 && anchor.getTile().getSLR().getId() != targetSlrId) continue;
            RelocatableTileRectangle candBb = moduleBox.getCorresponding(
                    anchor.getTile(), module.getAnchor().getTile());
            if (!edgeCheck.test(candBb)) continue;
            boolean overlaps = false;
            for (RelocatableTileRectangle existing : noGoBboxes) {
                if (existing.overlaps(candBb)) { overlaps = true; break; }
            }
            if (overlaps) continue;
            // Confirm bbox lies entirely within target SLR (relevant when bbox
            // straddles an SLR boundary even though the anchor is inside).
            if (targetSlrId >= 0) {
                Tile minTile = anchor.getTile().getDevice().getTile(candBb.getMinRow(), candBb.getMinColumn());
                Tile maxTile = anchor.getTile().getDevice().getTile(candBb.getMaxRow(), candBb.getMaxColumn());
                if (minTile != null && minTile.getSLR().getId() != targetSlrId) continue;
                if (maxTile != null && maxTile.getSLR().getId() != targetSlrId) continue;
            }
            int dist = Math.abs(anchor.getTile().getColumn() - targetTile.getColumn())
                     + Math.abs(anchor.getTile().getRow() - targetTile.getRow());
            if (dist < bestDist) { bestDist = dist; best = anchor; }
        }
        return best;
    }

    private static void recordPlacementBbox(Site placedAnchor, Module module,
                                            List<RelocatableTileRectangle> noGoBboxes) {
        RelocatableTileRectangle placedBb = module.getBoundingBox()
                .getCorresponding(placedAnchor.getTile(), module.getAnchor().getTile());
        noGoBboxes.add(placedBb);
    }

    /** Places nCols WeightEBs ABOVE this SA (standard orientation). */
    private static void placeWeightEBsAboveForSA(ArrayBuilder ab, String prefix, int nCols,
                                                  Module weightEbModule, ArrayBounds bounds,
                                                  List<RelocatableTileRectangle> globalNoGo) {
        Design arrayDesign = ab.getArray();
        Map<Pair<Integer, Integer>, Site> centroids = ab.getLogicalToCentroidMap();
        int slrId = centroids.values().iterator().next().getTile().getSLR().getId();
        for (int col = 0; col < nCols; col++) {
            Site target = centroids.get(new Pair<>(col, 0));
            String instName = prefix + "weight_eb_x" + col;
            ModuleInst mi = arrayDesign.createModuleInst(instName, weightEbModule);
            final int arrayTopRow = bounds.topRow;
            Site anchor = findBestAnchor(weightEbModule, target, slrId, globalNoGo,
                    /*occupiedTiles=*/ java.util.Collections.emptySet(),
                    bb -> bb.getMaxRow() < arrayTopRow);
            if (anchor == null) {
                throw new RuntimeException("No placement for " + instName + " (SLR " + slrId + ")");
            }
            if (!mi.place(anchor, false, false)) {
                throw new RuntimeException("Failed to place " + instName + " at " + anchor);
            }
            recordPlacementBbox(anchor, weightEbModule, globalNoGo);
            System.out.println("  ** PLACED WeightEB(above): " + instName + " at " + anchor);
        }
    }

    /**
     * Places nRows WeightEBs to the RIGHT of this SA (rotated orientation).
     * Note on naming: even though the rotated SA's WeightEB drives ports
     * named {@code west_inputs}, the array uses
     * {@code setFlipPlacementHorizontally(true)} so the physical "west" pins
     * land on the RIGHT side of the array. Place the EB chain accordingly.
     */
    private static void placeWeightEBsRightForSA(ArrayBuilder ab, String prefix, int nRows,
                                                  Module weightEbModule, ArrayBounds bounds,
                                                  List<RelocatableTileRectangle> globalNoGo) {
        Design arrayDesign = ab.getArray();
        Map<Pair<Integer, Integer>, Site> centroids = ab.getLogicalToCentroidMap();
        int slrId = centroids.values().iterator().next().getTile().getSLR().getId();
        // Find rightmost-column index from centroids to use as the per-row target.
        int nCols = 0;
        for (Pair<Integer, Integer> k : centroids.keySet())
            nCols = Math.max(nCols, k.getFirst() + 1);
        for (int row = 0; row < nRows; row++) {
            Site target = centroids.get(new Pair<>(nCols - 1, row));
            String instName = prefix + "weight_eb_y" + row;
            ModuleInst mi = arrayDesign.createModuleInst(instName, weightEbModule);
            final int arrayRightCol = bounds.rightCol;
            Site anchor = findBestAnchor(weightEbModule, target, slrId, globalNoGo,
                    java.util.Collections.emptySet(),
                    bb -> bb.getMinColumn() > arrayRightCol);
            if (anchor == null) {
                throw new RuntimeException("No placement for " + instName + " (SLR " + slrId + ")");
            }
            if (!mi.place(anchor, false, false)) {
                throw new RuntimeException("Failed to place " + instName + " at " + anchor);
            }
            recordPlacementBbox(anchor, weightEbModule, globalNoGo);
            System.out.println("  ** PLACED WeightEB(right): " + instName + " at " + anchor);
        }
    }

    /**
     * Places nRows InputEBs to the RIGHT of this SA (standard SA[0] only).
     * Uses the MODULE CENTROID for Manhattan-distance scoring (anchor +
     * (bbCenter - anchor) row offset), matching direct-flow's
     * placeInputDCUTiles. The InputEB module's anchor sits at one edge of
     * its bbox, so anchor-based distance picks an anchor whose centroid is
     * shifted ~half-bbox-height from the target — leading to placements
     * different from direct-flow's. The centroid-based distance fixes that.
     */
    private static void placeInputEBsRightForSA(ArrayBuilder ab, String prefix, int nRows,
                                                 Module inputEbModule, ArrayBounds bounds,
                                                 List<RelocatableTileRectangle> globalNoGo) {
        Design arrayDesign = ab.getArray();
        Map<Pair<Integer, Integer>, Site> centroids = ab.getLogicalToCentroidMap();
        int slrId = centroids.values().iterator().next().getTile().getSLR().getId();
        int nCols = 0;
        for (Pair<Integer, Integer> k : centroids.keySet())
            nCols = Math.max(nCols, k.getFirst() + 1);

        RelocatableTileRectangle moduleBb = inputEbModule.getBoundingBox();
        int anchorRow = inputEbModule.getAnchor().getTile().getRow();
        int bbCenterRow = (moduleBb.getMinRow() + moduleBb.getMaxRow()) / 2;
        int anchorToCentroidRowOffset = bbCenterRow - anchorRow;
        final int arrayRightCol = bounds.rightCol;

        for (int row = 0; row < nRows; row++) {
            Site target = centroids.get(new Pair<>(nCols - 1, row));
            Tile targetTile = target.getTile();
            String instName = prefix + "input_eb_y" + row;
            ModuleInst mi = arrayDesign.createModuleInst(instName, inputEbModule);

            Site bestAnchor = null;
            int bestDist = Integer.MAX_VALUE;
            for (Site anchor : inputEbModule.getAllValidPlacements()) {
                if (anchor.getTile().getSLR().getId() != slrId) continue;
                RelocatableTileRectangle candBb = moduleBb
                        .getCorresponding(anchor.getTile(), inputEbModule.getAnchor().getTile());
                if (candBb.getMinColumn() <= arrayRightCol) continue;
                boolean overlaps = false;
                for (RelocatableTileRectangle existing : globalNoGo) {
                    if (existing.overlaps(candBb)) { overlaps = true; break; }
                }
                if (overlaps) continue;
                int moduleCentroidRow = anchor.getTile().getRow() + anchorToCentroidRowOffset;
                int dist = Math.abs(anchor.getTile().getColumn() - targetTile.getColumn())
                         + Math.abs(moduleCentroidRow - targetTile.getRow());
                if (dist < bestDist) { bestDist = dist; bestAnchor = anchor; }
            }
            if (bestAnchor == null) {
                throw new RuntimeException("No placement for " + instName + " (SLR " + slrId + ")");
            }
            if (!mi.place(bestAnchor, false, false)) {
                throw new RuntimeException("Failed to place " + instName + " at " + bestAnchor);
            }
            recordPlacementBbox(bestAnchor, inputEbModule, globalNoGo);
            System.out.println("  ** PLACED InputEB: " + instName + " at " + bestAnchor);
        }
    }

    /** Places nCols Drains BELOW this SA (last SA only). */
    private static void placeDrainsBelowForSA(ArrayBuilder ab, String prefix, int nCols,
                                               Module drainModule, ArrayBounds bounds,
                                               List<RelocatableTileRectangle> globalNoGo) {
        Design arrayDesign = ab.getArray();
        Map<Pair<Integer, Integer>, Site> centroids = ab.getLogicalToCentroidMap();
        int slrId = centroids.values().iterator().next().getTile().getSLR().getId();
        int nRows = 0;
        for (Pair<Integer, Integer> k : centroids.keySet())
            nRows = Math.max(nRows, k.getSecond() + 1);
        int accumCount = 4;
        for (int col = 0; col < nCols; col++) {
            Site target = centroids.get(new Pair<>(col, nRows - 1));
            String instName = prefix + "drain_x" + col;
            ModuleInst mi = arrayDesign.createModuleInst(instName, drainModule);
            final int arrayBotRow = bounds.bottomRow;
            Site anchor = findBestAnchor(drainModule, target, slrId, globalNoGo,
                    java.util.Collections.emptySet(),
                    bb -> bb.getMinRow() > arrayBotRow);
            if (anchor == null) {
                throw new RuntimeException("No placement for " + instName + " (SLR " + slrId + ")");
            }
            if (!mi.place(anchor, false, false)) {
                throw new RuntimeException("Failed to place " + instName + " at " + anchor);
            }
            recordPlacementBbox(anchor, drainModule, globalNoGo);
            DrainTile.setColumnElements(arrayDesign, instName, accumCount, 8);
            int upstreamElements = (nCols - 1 - col) * accumCount;
            DrainTile.setExternalUpstreamElements(arrayDesign, instName, upstreamElements, 8);
            System.out.println("  ** PLACED Drain: " + instName + " at " + anchor);
        }
    }

    /** Places this SA's MM2S at the top-right corner. */
    private static void placeMM2SForSA(ArrayBuilder ab, String prefix, Module mm2sModule,
                                        ArrayBounds bounds,
                                        List<RelocatableTileRectangle> globalNoGo) {
        Design arrayDesign = ab.getArray();
        int slrId = ab.getLogicalToCentroidMap().values().iterator().next().getTile().getSLR().getId();
        // Use the top-right tile as the placement target.
        Site targetSite = null;
        int bestKey = Integer.MAX_VALUE;
        for (Map.Entry<Pair<Integer, Integer>, Site> e : ab.getLogicalToCentroidMap().entrySet()) {
            int rank = e.getKey().getSecond() * 1000 - e.getKey().getFirst();
            if (rank < bestKey) { bestKey = rank; targetSite = e.getValue(); }
        }
        String instName = prefix + "mm2s";
        ModuleInst mi = arrayDesign.createModuleInst(instName, mm2sModule);
        Site anchor = findBestAnchor(mm2sModule, targetSite, slrId, globalNoGo,
                java.util.Collections.emptySet(), bb -> true);
        if (anchor == null) {
            throw new RuntimeException("No placement for " + instName + " (SLR " + slrId + ")");
        }
        if (!mi.place(anchor, false, false)) {
            throw new RuntimeException("Failed to place " + instName + " at " + anchor);
        }
        recordPlacementBbox(anchor, mm2sModule, globalNoGo);
        System.out.println("  ** PLACED MM2S: " + instName + " at " + anchor);
    }

    /** Places the single S2MM at bottom-right of the last SA. */
    private static void placeS2MMForSA(ArrayBuilder ab, String instName, Module s2mmModule,
                                        ArrayBounds bounds,
                                        List<RelocatableTileRectangle> globalNoGo) {
        Design arrayDesign = ab.getArray();
        int slrId = ab.getLogicalToCentroidMap().values().iterator().next().getTile().getSLR().getId();
        // Bottom-right tile target.
        Site targetSite = null;
        int bestKey = Integer.MIN_VALUE;
        for (Map.Entry<Pair<Integer, Integer>, Site> e : ab.getLogicalToCentroidMap().entrySet()) {
            int rank = e.getKey().getSecond() * 1000 + e.getKey().getFirst();
            if (rank > bestKey) { bestKey = rank; targetSite = e.getValue(); }
        }
        ModuleInst mi = arrayDesign.createModuleInst(instName, s2mmModule);
        Site anchor = findBestAnchor(s2mmModule, targetSite, slrId, globalNoGo,
                java.util.Collections.emptySet(), bb -> true);
        if (anchor == null) {
            throw new RuntimeException("No placement for " + instName + " (SLR " + slrId + ")");
        }
        if (!mi.place(anchor, false, false)) {
            throw new RuntimeException("Failed to place " + instName + " at " + anchor);
        }
        recordPlacementBbox(anchor, s2mmModule, globalNoGo);
        System.out.println("  ** PLACED S2MM: " + instName + " at " + anchor);
    }

    /**
     * Places one BufferTile SLR-crossing wrapper per ReluTile between two
     * adjacent SAs. The wrapper's top half lives in {@code prevAb}'s SLR and
     * its bottom half lives in the next SLR, matching the ReluTile ->
     * BufferTile -> next-SA netlist wiring.
     */
    private static void placeBufferSLRCrossingsBetweenSAs(ArrayBuilder prevAb, String prevName,
                                                           String nextName, int count,
                                                           Module bufferSlrCrossingModule,
                                                           ArrayBounds nextBounds,
                                                           List<RelocatableTileRectangle> globalNoGo) {
        Design arrayDesign = prevAb.getArray();
        int slrId = prevAb.getLogicalToCentroidMap().values().iterator().next()
                .getTile().getSLR().getId();
        Map<SLR, List<Site>> validCrossings = ArrayBuilder.getValidSLRCrossings(bufferSlrCrossingModule);
        List<Site> candidateAnchors = null;
        for (Map.Entry<SLR, List<Site>> e : validCrossings.entrySet()) {
            if (e.getKey().getId() == slrId) {
                candidateAnchors = e.getValue();
                break;
            }
        }
        if (candidateAnchors == null || candidateAnchors.isEmpty()) {
            throw new RuntimeException("No BufferTile SLR-crossing placements for SLR " + slrId);
        }

        Map<Pair<Integer, Integer>, Site> centroids = prevAb.getLogicalToCentroidMap();
        int prevNRows = 0;
        for (Pair<Integer, Integer> k : centroids.keySet()) {
            prevNRows = Math.max(prevNRows, k.getSecond() + 1);
        }

        RelocatableTileRectangle moduleBb = bufferSlrCrossingModule.getBoundingBox();
        for (int idx = 0; idx < count; idx++) {
            Site target = centroids.get(new Pair<>(idx, prevNRows - 1));
            Tile targetTile = target.getTile();
            String instName = prevName + "_to_" + nextName + "_buf_x" + idx;
            ModuleInst mi = arrayDesign.createModuleInst(instName, bufferSlrCrossingModule);

            List<Pair<Integer, Site>> viableAnchors = new ArrayList<>();
            for (Site anchor : candidateAnchors) {
                RelocatableTileRectangle candBb = moduleBb
                        .getCorresponding(anchor.getTile(), bufferSlrCrossingModule.getAnchor().getTile());
                boolean overlaps = false;
                for (RelocatableTileRectangle existing : globalNoGo) {
                    if (existing.overlaps(candBb)) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) continue;
                if (candBb.getMaxRow() >= nextBounds.topRow) {
                    continue;
                }

                int dist = Math.abs(anchor.getTile().getColumn() - targetTile.getColumn())
                         + Math.abs(anchor.getTile().getRow() - targetTile.getRow());
                viableAnchors.add(new Pair<>(dist, anchor));
            }
            viableAnchors.sort(java.util.Comparator.comparingInt(Pair::getFirst));

            Site bestAnchor = null;
            for (Pair<Integer, Site> candidate : viableAnchors) {
                Site anchor = candidate.getSecond();
                if (mi.place(anchor, false, false)) {
                    bestAnchor = anchor;
                    break;
                }
                mi.unplace();
            }
            if (bestAnchor == null) {
                throw new RuntimeException("No placement for " + instName + " (SLR " + slrId + ")");
            }
            recordPlacementBbox(bestAnchor, bufferSlrCrossingModule, globalNoGo);
            RelocatableTileRectangle placedBb = moduleBb
                    .getCorresponding(bestAnchor.getTile(), bufferSlrCrossingModule.getAnchor().getTile());
            System.out.println("  ** PLACED BufferTile SLR Crossing: " + instName
                    + " at " + bestAnchor
                    + " bbox rows " + placedBb.getMinRow() + ".." + placedBb.getMaxRow()
                    + " nextArrayTopRow=" + nextBounds.topRow);
        }
    }

    /**
     * Stamps {@code prevNCols} ReluTile_4 instances inside SA[i]'s SLR, just
     * below SA[i]'s array. Names follow the netlist-builder convention
     * ({@code sa{i}_to_sa{i+1}_relu_x{n}}). The relu bbox is required to be
     * STRICTLY BELOW the prev SA (every row of the relu bbox > the array's
     * bottom row), and to remain in the same SLR.
     */
    private static void placeReluTilesBetweenSAs(ArrayBuilder prevAb, String prevName,
                                                  String nextName, int prevNCols,
                                                  Module reluModule, ArrayBounds prevBounds,
                                                  List<RelocatableTileRectangle> globalNoGo) {
        Design arrayDesign = prevAb.getArray();
        int slrId = prevAb.getLogicalToCentroidMap().values().iterator().next()
                .getTile().getSLR().getId();
        Map<Pair<Integer, Integer>, Site> centroids = prevAb.getLogicalToCentroidMap();
        int prevNRows = 0;
        for (Pair<Integer, Integer> k : centroids.keySet())
            prevNRows = Math.max(prevNRows, k.getSecond() + 1);
        // Diagnostic: log the relu module bbox dims so we can verify the
        // "strictly below" check is operating on the geometry we expect.
        RelocatableTileRectangle reluBox = reluModule.getBoundingBox();
        System.out.println("  ** ReluTile bbox: rows " + reluBox.getMinRow() + ".."
                + reluBox.getMaxRow() + ", cols " + reluBox.getMinColumn() + ".."
                + reluBox.getMaxColumn() + "  (SA bottomRow=" + prevBounds.bottomRow + ")");
        for (int idx = 0; idx < prevNCols; idx++) {
            Site target = centroids.get(new Pair<>(idx, prevNRows - 1));
            String instName = prevName + "_to_" + nextName + "_relu_x" + idx;
            ModuleInst mi = arrayDesign.createModuleInst(instName, reluModule);
            final int arrayBotRow = prevBounds.bottomRow;
            // Strict vertical separation: the relu bbox must lie entirely
            // below the prev SA's bottom row (both top and bottom edges of
            // the relu bbox > arrayBotRow). minRow > arrayBotRow implies
            // maxRow > arrayBotRow too (bbox invariant), so one check covers
            // both. Adding a redundant maxRow check anyway as belt-and-braces
            // in case any future bbox normalization makes the implication false.
            Site anchor = findBestAnchor(reluModule, target, slrId, globalNoGo,
                    java.util.Collections.emptySet(),
                    bb -> bb.getMinRow() > arrayBotRow && bb.getMaxRow() > arrayBotRow);
            if (anchor == null) {
                throw new RuntimeException("No placement for " + instName + " (SLR " + slrId
                        + ", strictly below row " + arrayBotRow + ")");
            }
            if (!mi.place(anchor, false, false)) {
                throw new RuntimeException("Failed to place " + instName + " at " + anchor);
            }
            recordPlacementBbox(anchor, reluModule, globalNoGo);
            // Verify the placed bbox is actually strictly below.
            RelocatableTileRectangle placedBb = reluModule.getBoundingBox()
                    .getCorresponding(anchor.getTile(), reluModule.getAnchor().getTile());
            System.out.println("  ** PLACED ReluTile: " + instName + " at " + anchor
                    + "  bbox rows " + placedBb.getMinRow() + ".." + placedBb.getMaxRow());
        }
    }
}
