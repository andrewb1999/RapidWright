/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Advanced Research and Development.
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
import com.xilinx.rapidwright.design.ClockTools;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetTools;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.RelocatableTileRectangle;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockGenerator;
import com.xilinx.rapidwright.design.blocks.PBlockRange;
import com.xilinx.rapidwright.design.blocks.PBlockSide;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.eco.ECOPlacementHelper;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.edif.EDIFValueType;
import com.xilinx.rapidwright.rwroute.PartialCUFR;
import com.xilinx.rapidwright.rwroute.PartialRouter;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.PerformanceExplorer;
import com.xilinx.rapidwright.util.VivadoTools;
import joptsimple.OptionParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.xilinx.rapidwright.util.Utils.isBRAM;
import static com.xilinx.rapidwright.util.Utils.isDSP;
import static com.xilinx.rapidwright.util.Utils.isSLICE;

/**
 * A Tool to optimize, place and route a kernel and then replicate its
 * implementation in an array across the fabric.
 */
public class ArrayBuilder {

    private static final Set<SiteTypeEnum> VALID_CENTROID_SITE_TYPES =
            new HashSet<>(Arrays.asList(SiteTypeEnum.SLICEL, SiteTypeEnum.SLICEM));

    private Design kernelDesign;

    private Design topDesign;

    private Design array;

    private String kernelClockName;

    private String topClockName;

    private List<PBlock> pblocks;

    private CodePerfTracker t;

    private ArrayNetlistGraph condensedGraph;

    private Map<ModuleInst, Site> newPlacementMap;

    private Map<Pair<Integer, Integer>, Site> logicalToCentroidMap;

    /**
     * Bounding boxes of all GEMM tile and SLR-crossing module instances placed by
     * {@link #placeModuleInstancesAutomatically}. Retained here because
     * {@link #createArray()} calls {@code array.flattenDesign()} after placement,
     * which collapses the corresponding {@code ModuleInst}s and makes
     * {@code array.getModuleInsts()} no longer expose them.
     */
    private List<RelocatableTileRectangle> placedArrayBoundingBoxes;

    private List<Module> modules;

    private List<String> modInstNames;

    private final ArrayBuilderConfig config;

    public ArrayBuilder(ArrayBuilderConfig config) {
        newPlacementMap = new HashMap<>();
        logicalToCentroidMap = new HashMap<>();
        placedArrayBoundingBoxes = new ArrayList<>();
        modInstNames = new ArrayList<>();
        modules = new ArrayList<>();
        this.config = config;
        this.t = new CodePerfTracker(ArrayBuilder.class.getName());
        this.t.start("Init");
    }

    public ArrayBuilder(ArrayBuilderConfig config, CodePerfTracker t) {
        this(config);
        this.t = t;
    }

    private static void printHelp(OptionParser p) {
        MessageGenerator.printHeader("ArrayBuilder");
        System.out.println("Generates an optimized, implemented array of the provided kernel.");
        try {
            p.printHelpOn(System.out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Device getDevice() {
        return getKernelDesign().getDevice();
    }

    public Design getKernelDesign() {
        return kernelDesign;
    }

    public Design getTopDesign() {
        return topDesign;
    }

    public Design getArray() {
        return array;
    }

    public String getKernelClockName() {
        return kernelClockName;
    }

    public String getTopClockName() {
        return topClockName;
    }

    public void setPBlocks(List<PBlock> pblocks) {
        this.pblocks = pblocks;
    }

    public List<PBlock> getPBlocks() {
        return pblocks == null ? Collections.emptyList() : pblocks;
    }

    public ArrayNetlistGraph getCondensedGraph() {
        return condensedGraph;
    }

    public void setCondensedGraph(ArrayNetlistGraph condensedGraph) {
        this.condensedGraph = condensedGraph;
    }

    private Map<ModuleInst, Site> getNewPlacementMap() {
        return newPlacementMap;
    }

    public Map<Pair<Integer, Integer>, Site> getLogicalToCentroidMap() {
        return logicalToCentroidMap;
    }

    /**
     * Bounding boxes of all GEMM tile and SLR-crossing module instances that were
     * placed during {@link #createArray()}. Use this from downstream placement
     * passes (e.g. peripheral edge buffers) instead of {@code Design.getModuleInsts()},
     * which loses these entries after the post-placement {@code flattenDesign()}.
     */
    public List<RelocatableTileRectangle> getPlacedArrayBoundingBoxes() {
        return placedArrayBoundingBoxes;
    }

    private Site getModuleInstCentroid(ModuleInst mi) {
        List<Point> points = new ArrayList<>();
        for (SiteInst si : mi.getSiteInsts()) {
            Tile t = si.getTile();
            points.add(new Point(t.getColumn(), t.getRow()));
        }
        return ECOPlacementHelper.getCentroidOfPoints(
                array.getDevice(), points, VALID_CENTROID_SITE_TYPES);
    }

    public void initializeArrayBuilder() {
        assert (config.getKernelDesign() != null);

        kernelDesign = config.getKernelDesign();
        topDesign = config.getTopDesign();

        if (config.getPBlockStrings() != null) {
            List<PBlock> pblocks = new ArrayList<PBlock>();
            for (String str : config.getPBlockStrings()) {
                pblocks.add(new PBlock(getDevice(), str));
            }
            setPBlocks(pblocks);
        } else if (!config.isSkipImpl()) {
            PBlockGenerator pb = new PBlockGenerator();
            Path utilReport;
            Path shapesReport;
            if (config.getUtilReport() != null && config.getShapesReport() != null) {
                utilReport = Paths.get(config.getUtilReport());
                shapesReport = Paths.get(config.getShapesReport());
            } else {
                utilReport = Paths.get("utilization.report");
                shapesReport = Paths.get("shapes.report");
                Path kernelDesign = Paths.get("kernelDesign.dcp");
                config.getKernelDesign().writeCheckpoint(kernelDesign);
                VivadoTools.getUtilizationAndShapesReport(kernelDesign, utilReport, shapesReport);
            }

            List<String> pblocks = pb.generatePBlockFromReport(utilReport.toString(), shapesReport.toString());
            List<PBlock> pblockObjects = new ArrayList<PBlock>();
            for (String s : pblocks) {
                PBlock pblock = new PBlock();
                for (String range : s.split(" ")) {
                    pblock.add(new PBlockRange(getDevice(), range));
                }
                pblockObjects.add(pblock);
            }
            setPBlocks(pblockObjects);
        }

        for (int i = 0; i < getPBlocks().size(); i++) {
            System.out.println("[INFO] PBlocks Set [" + i + "]: " + getPBlocks().get(i));
        }

        if (config.getTopDesign() != null) {
            EDIFTools.removeVivadoBusPreventionAnnotations(config.getTopDesign().getNetlist());
        }

        if (config.getKernelClockName() != null) {
            kernelClockName = config.getKernelClockName();
        } else {
            kernelClockName = ClockTools.getClockFromDesign(config.getKernelDesign()).toString();
        }

        if (config.getTopClockName() != null) {
            topClockName = config.getTopClockName();
        } else if (config.getTopDesign() != null) {
            topClockName = ClockTools.getClockFromDesign(config.getTopDesign()).toString();
        }
    }

    public static void removeBUFGs(Design design) {
        // Find BUFGs in the design and remove them
        List<Cell> bufgs = new ArrayList<>();
        for (Cell c : design.getCells()) {
            if (c.getType().equals("BUFG") || c.getType().equals("BUFGCE")) {
                bufgs.add(c);
            }
        }

        for (Cell bufg : bufgs) {
            SiteInst si = bufg.getSiteInst();
            String inputSiteWire = bufg.getSiteWireNameFromLogicalPin("I");
            Net input = si.getNetFromSiteWire(inputSiteWire);
            String outputSiteWire = bufg.getSiteWireNameFromLogicalPin("O");
            Net output = si.getNetFromSiteWire(outputSiteWire);

            // Remove BUFG
            design.removeCell(bufg);

            design.removeSiteInst(bufg.getSiteInst());
            EDIFCellInst bufgInst = design.getTopEDIFCell().removeCellInst(bufg.getName());
            for (EDIFPortInst portInst : bufgInst.getPortInsts()) {
                portInst.getNet().removePortInst(portInst);
            }
            EDIFNet clkin = design.getTopEDIFCell().getNet(input.getName());
            EDIFNet clk = design.getTopEDIFCell().getNet(output.getName());
            for (EDIFPortInst portInst : clkin.getPortInsts()) {
                clk.addPortInst(portInst);
            }
            design.getTopEDIFCell().removeNet(clkin);
        }
    }

    public static List<String> getMatchingModuleInstanceNames(Module m, Design array) {
        List<String> instNames = new ArrayList<>();
        EDIFCell modCellType = m.getNetlist().getTopCell();
        EDIFHierCellInst top = array.getNetlist().getTopHierCellInst();
        Queue<EDIFHierCellInst> q = new LinkedList<>();
        q.add(top);
        while (!q.isEmpty()) {
            EDIFHierCellInst curr = q.poll();
            if (curr.getCellType().matchesInterface(modCellType)) {
                instNames.add(curr.getFullHierarchicalInstName());
            } else {
                for (EDIFCellInst child : curr.getCellType().getCellInsts()) {
                    q.add(curr.getChild(child));
                }
            }
        }
        return instNames;
    }

    public static void writePlacementToFile(Map<ModuleInst, Site> placementMap, String fileName) {
        List<String> lines = new ArrayList<>();
        Comparator<Site> comparator = new Comparator<Site>() {
            @Override
            public int compare(Site o1, Site o2) {
                if (o1.getInstanceY() > o2.getInstanceY()) {
                    return -1;
                }

                if (o1.getInstanceY() < o2.getInstanceY()) {
                    return 1;
                }

                return Integer.compare(o1.getInstanceX(), o2.getInstanceX());
            }
        };
        Map<ModuleInst, Site> sortedMap = placementMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(comparator))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));
        for (Map.Entry<ModuleInst, Site> entry : sortedMap.entrySet()) {
            lines.add(entry.getKey() + " " + entry.getValue());
        }
        FileTools.writeLinesToTextFile(lines, fileName);
    }

    public static Map<String, String> readPlacementFromFile(String fileName) {
        if (fileName == null) {
            throw new RuntimeException("Trying to read placement file without providing a file path");
        }
        Map<String, String> placementMap = new HashMap<>();
        List<String> lines = FileTools.getLinesFromTextFile(fileName);

        for (String line : lines) {
            String[] splitLine = line.split("\\s+");
            placementMap.put(splitLine[0], splitLine[1]);
        }

        return placementMap;
    }

    public static void writePlacementLocsToFile(List<Module> modules, String fileName) {
        List<String> lines = new ArrayList<>();
        Comparator<Site> comparator = (o1, o2) -> {
            if (o1.getInstanceY() > o2.getInstanceY()) {
                return -1;
            }

            if (o1.getInstanceY() < o2.getInstanceY()) {
                return 1;
            }

            return Integer.compare(o1.getInstanceX(), o2.getInstanceX());
        };
        for (Module module : modules) {
            lines.add(module.getName() + ":");
            List<Site> validPlacements = module.getAllValidPlacements().stream().sorted(comparator)
                    .collect(Collectors.toList());
            for (Site anchor : validPlacements) {
                lines.add(anchor.getName());
            }
        }
        FileTools.writeLinesToTextFile(lines, fileName);
    }

    public static List<List<Site>> getValidPlacementGrid(Module module) {
        List<List<Site>> placementGrid = new ArrayList<>();
        // Sort by descending Y coordinate, then ascending X coordinate
        List<Site> sortedValidPlacements = module.getAllValidPlacements().stream().sorted((s1, s2) -> {
            if (s1.getInstanceY() == s2.getInstanceY()) {
                return s1.getInstanceX() - s2.getInstanceX();
            }
            return s2.getInstanceY() - s1.getInstanceY();
        }).collect(Collectors.toList());
        int currentYCoordinate = sortedValidPlacements.get(0).getInstanceY();
        int i = 0;
        placementGrid.add(new ArrayList<>());
        for (Site anchor : sortedValidPlacements) {
            if (anchor.getInstanceY() < currentYCoordinate) {
                i++;
                placementGrid.add(new ArrayList<>());
            }
            placementGrid.get(i).add(anchor);
            currentYCoordinate = anchor.getInstanceY();
        }
        return placementGrid;
    }

    private List<Module> implementKernel(Path workDir) {
        t.stop().start("Implement Kernel");
        FileTools.makeDirs(workDir.toString());
        System.out.println("[INFO] Created work directory: " + workDir.toString());

        // Initialize PerformanceExplorer
        PerformanceExplorer pe = new PerformanceExplorer(getKernelDesign(), workDir.toString(),
                kernelClockName, config.getClockPeriod());

        // Set PBlocks
        Map<PBlock, String> pblocks = new HashMap<>();
        for (PBlock pb : getPBlocks()) {
            pblocks.put(pb, null);
        }
        pe.setPBlocks(pblocks);
        pe.setAddEDIFAndMetadata(true);
        pe.setReusePreviousResults(config.isReuseResults());
        pe.explorePerformance();

        List<Module> modules = new ArrayList<>();
        List<Pair<Path, Float>> results = pe.getBestResultsPerPBlock();
        for (int i = 0; i < results.size(); i++) {
            Pair<Path, Float> result = results.get(i);
            Path dcpPath = result.getFirst().resolve("routed.dcp");
            if (Files.exists(dcpPath)) {
                System.out.println("Reading... " + dcpPath);
                Design d = Design.readCheckpoint(dcpPath);
                d.setName(d.getName() + "_" + i);
                Module m = new Module(d, config.shouldUnrouteStaticNets());
                modules.add(m);
                m.setPBlock(pe.getPBlock(i));
                m.calculateAllValidPlacements(d.getDevice());
            } else {
                System.err.println("Missing DCP Result: " + dcpPath);
            }
            System.out.println(result.getFirst() + " " + result.getSecond());
        }
        return modules;
    }

    private ArrayNetlistGraph.IdealArrayPlacement calculateIdealArrayPlacement() {
        t.stop().start("Calculate ideal array placement");
        // Find instances in existing design
        modInstNames = getMatchingModuleInstanceNames(modules.get(0), array);
        // Multi-SA: restrict to instances belonging to this segment.
        String prefix = config.getInstanceNamePrefix();
        if (prefix != null) {
            int before = modInstNames.size();
            modInstNames.removeIf(n -> !n.startsWith(prefix));
            System.out.println("[ArrayBuilder] Filtered modInstNames to prefix '" + prefix
                    + "': " + before + " -> " + modInstNames.size());
        }
        if (modInstNames.isEmpty()) {
            throw new RuntimeException("Failed to find module instances in top design that match kernel interface"
                    + (prefix != null ? " (prefix='" + prefix + "')" : ""));
        }
        config.setInstCountLimit(modInstNames.size());
        Map<EDIFPort, PBlockSide> sideMap = null;
        if (config.getSideMapFile() != null) {
            sideMap = InlineFlopTools.parseSideMap(getKernelDesign().getNetlist(), config.getSideMapFile());
        }
        setCondensedGraph(new ArrayNetlistGraph(array, modInstNames, sideMap));
        ArrayNetlistGraph.IdealArrayPlacement idealPlacement =
                getCondensedGraph().getGreedyPlacementGrid();
        return idealPlacement;
    }

    private ArrayNetlistGraph.IdealArrayPlacement prepareArrayForPlacement() {
        Path workDir = Paths.get(config.getWorkDir());
        if (!config.isSkipImpl()) {
            modules = implementKernel(workDir);
        } else /* skipImpl==true */ {
            // Just use the design we loaded and replicate it
            t.stop().start("Calculate Valid Placements");
            removeBUFGs(config.getKernelDesign());
            if (config.shouldUnrouteStaticNets()) {
                Net gndNet = config.getKernelDesign().getNet(Net.GND_NET);
                if (gndNet != null) {
                    gndNet.unroute();
                    List<SitePinInst> staticSourcePins = new ArrayList<>();
                    Set<SiteInst> staticSourceSites = new HashSet<>();
                    for (SitePinInst pin : gndNet.getPins()) {
                        if (pin.isOutPin() && pin.getSiteInst().getName().startsWith(SiteInst.STATIC_SOURCE)) {
                            staticSourcePins.add(pin);
                            staticSourceSites.add(pin.getSiteInst());
                        }
                    }
                    for (SitePinInst pin : staticSourcePins) {
                        gndNet.removePin(pin);
                        pin.getSiteInst().removePin(pin);
                    }
                    for (SiteInst siteInst : staticSourceSites) {
                        siteInst.setDesign(null);
                        siteInst.unPlace();
                    }
                }
                Net vccNet = getKernelDesign().getNet(Net.VCC_NET);
                if (vccNet != null) {
                    vccNet.unroute();
                }
            }
            Module m = new Module(getKernelDesign(), config.shouldUnrouteStaticNets());
            m.getNet(getKernelClockName()).unroute();

            if (config.getInputPlacementFileName() == null) {
                m.calculateAllValidPlacements(getDevice());
                filterValidPlacementsToTargetSLR(m, "kernel");
            }
            if (!getPBlocks().isEmpty()) {
                m.setPBlock(getPBlocks().get(0));
            }
            modules.add(m);
        }

        ArrayNetlistGraph.IdealArrayPlacement idealPlacement = null;
        if (getTopDesign() == null) {
            array = new Design("array", getKernelDesign().getPartName());
        } else {
            array = getTopDesign();
            idealPlacement = calculateIdealArrayPlacement();
        }

        return idealPlacement;
    }

    /**
     * If {@link ArrayBuilderConfig#getTargetSLR()} is set (>= 0), removes
     * any anchor in {@code module.getAllValidPlacements()} that resolves to
     * a site outside that SLR. This restricts {@link #createArray()} to a
     * single SLR for use in multi-SA flows. No-op when no constraint is set.
     */
    private void filterValidPlacementsToTargetSLR(Module module, String label) {
        int targetId = config.getTargetSLR();
        if (targetId < 0) return;
        List<Site> placements = module.getAllValidPlacements();
        int before = placements.size();
        try {
            placements.removeIf(s -> s.getTile().getSLR().getId() != targetId);
        } catch (UnsupportedOperationException e) {
            throw new RuntimeException(
                    "ArrayBuilderConfig.targetSLR is set but Module.getAllValidPlacements() "
                            + "returned an immutable list; cannot filter. "
                            + "Module: " + label, e);
        }
        int after = placements.size();
        System.out.println("[ArrayBuilder] Filtered " + label + " valid placements to SLR "
                + targetId + ": " + before + " -> " + after);
        if (after == 0) {
            throw new RuntimeException("No valid placements remain in SLR " + targetId
                    + " for module '" + label + "' after SLR filtering");
        }
    }

    private void placeInstancesWithManualPlacementFile() {
        Map<String, String> placementMap = readPlacementFromFile(config.getInputPlacementFileName());
        System.out.println("Placing from specified file");
        int placed = 0;
        for (Map.Entry<String, String> entry : placementMap.entrySet()) {
            String instName = entry.getKey();
            String anchorName = entry.getValue();
            EDIFHierCellInst hierInst = array.getNetlist().getHierCellInstFromName(instName);
            if (hierInst == null) {
                throw new RuntimeException("Instance name " + instName + " is invalid");
            }
            if (modules.size() > 1) {
                throw new RuntimeException("Manual placement does not work with automated implementation");
            }
            Module module = modules.get(0);
            ModuleInst curr = array.createModuleInst(instName, module);

            Site anchor = array.getDevice().getSite(anchorName);

            boolean wasPlaced = curr.place(anchor, true, false);
            if (!wasPlaced) {
                throw new RuntimeException("Unable to place cell " + instName + " at site " + anchor);
            }

            if (straddlesClockRegion(curr)) {
                curr.unplace();
                throw new RuntimeException("Chosen site anchor " + anchor + " straddles multiple clock regions");
            }

            newPlacementMap.put(curr, anchor);
            placed++;
            System.out.println("  ** PLACED: " + placed + " " + anchor + " " + curr.getName());
        }
    }

    private void placeModuleInstancesAutomatically(ArrayNetlistGraph.IdealArrayPlacement idealPlacement) {
        // TODO: Figure out how to handle placement for multiple modules
        Module module = modules.get(0);
        RelocatableTileRectangle boundingBox = module.getBoundingBox();
        List<RelocatableTileRectangle> boundingBoxes = new ArrayList<>();
        List<List<Site>> validPlacementGrid = getValidPlacementGrid(module);
        Set<Pair<Integer, Integer>> alreadyPlaced = new HashSet<>();
        Map<Pair<Integer, Integer>, Pair<Integer, Integer>> idealToPhysicalPlacementMap = new HashMap<>();
        int topLeftPhysicalPlacementX = config.getColumnOffset();
        int topLeftPhysicalPlacementY = config.getRowOffset();
        int topRightPhysicalPlacementX = validPlacementGrid.get(topLeftPhysicalPlacementY).size() - 1 - config.getColumnOffset();
        int numPlaced = 0;
        for (int x = 0; x < idealPlacement.getArrayWidth(); x++) {
            for (int y = 0; y < idealPlacement.getArrayHeight(); y++) {
                boolean searchHorizontally = (y == 0);
                if (alreadyPlaced.contains(new Pair<>(x, y))) {
                    // Already placed as part of an SLR crossing module
                    continue;
                }
                String instToPlace = idealPlacement.getInstanceAtLocation(x, y);
                int physX;
                int physY;
                if (x == 0 && y == 0) {
                    // First element to place
                    if (config.isFlipPlacementHorizontally()) {
                        physX = topRightPhysicalPlacementX;
                    } else {
                        physX = topLeftPhysicalPlacementX;
                    }
                    physY = topLeftPhysicalPlacementY;
                } else {
                    // Start from previous placement
                    if (searchHorizontally) {
                        Pair<Integer, Integer> westPrevLoc = idealToPhysicalPlacementMap.get(new Pair<>(x - 1, y));
                        physX = westPrevLoc.getFirst();
                        physY = westPrevLoc.getSecond();
                    } else {
                        Pair<Integer, Integer> northPrevLoc = idealToPhysicalPlacementMap.get(new Pair<>(x, y - 1));
                        physX = northPrevLoc.getFirst();
                        physY = northPrevLoc.getSecond();
                    }
                }

                boolean placed = false;
                while (!placed) {
                    Site anchor = validPlacementGrid.get(physY).get(physX);
                    RelocatableTileRectangle newBoundingBox =
                            boundingBox.getCorresponding(anchor.getTile(), module.getAnchor().getTile());
                    boolean noOverlap = boundingBoxes.stream().noneMatch((b) -> b.overlaps(newBoundingBox));
                    if (noOverlap && !boundingBoxStraddlesClockRegion(newBoundingBox)) {
                        // Choose proposed anchor to place instance
                        ModuleInst curr = array.createModuleInst(instToPlace, module);
                        if (curr.place(anchor, false, false)) {
                            placed = true;
                            boundingBoxes.add(newBoundingBox);
                            newPlacementMap.put(curr, anchor);
                            logicalToCentroidMap.put(new Pair<>(x, y), getModuleInstCentroid(curr));
                            idealToPhysicalPlacementMap.put(new Pair<>(x, y), new Pair<>(physX, physY));
                            System.out.println("  ** PLACED: " + numPlaced + " " + anchor + " " + curr.getName()
                                    + " " + curr.getAnchor().getTile().getSLR());
                            numPlaced++;
                        } else {
                            throw new RuntimeException("Failed to place module at site that should be valid anchor");
                        }
                    }
                    if (!placed) {
                        // Could not place at proposed anchor, get new proposal
                        if (searchHorizontally) {
                            // Search horizontally from physX for valid placement
                            if (config.isFlipPlacementHorizontally()) {
                                // Search to the left
                                physX--;
                                if (physX < 0) {
                                    throw new RuntimeException("Optimal placement is too wide for device");
                                }
                            } else {
                                // Search to the right
                                physX++;
                                if (physX >= validPlacementGrid.get(physY).size()) {
                                    throw new RuntimeException("Optimal placement is too wide for device");
                                }
                            }
                        } else {
                            // Search down from physY for valid placement
                            physY++;
                            if (physY >= validPlacementGrid.size()) {
                                throw new RuntimeException("Optimal placement is too tall for device");
                            }
                        }
                    }
                }
            }
        }

        // Retain the placement-time bounding boxes so downstream placement passes
        // can still see GEMM tile and SLR-crossing footprints after createArray()'s
        // flattenDesign() collapses the corresponding ModuleInsts.
        placedArrayBoundingBoxes.addAll(boundingBoxes);
    }

    private void placeArray() {
        ArrayNetlistGraph.IdealArrayPlacement idealPlacement = prepareArrayForPlacement();

        t.stop().start("Place Instances");
        if (config.getOutputPlacementLocsFileName() != null) {
            writePlacementLocsToFile(modules, config.getOutputPlacementLocsFileName());
        }

        // Add encrypted cells from modules to array
        for (Module module : modules) {
            // Merge encrypted cells
            List<String> encryptedCells = module.getNetlist().getEncryptedCells();
            if (!encryptedCells.isEmpty()) {
                System.out.println("Encrypted cells merged");
                array.getNetlist().addEncryptedCells(encryptedCells);
            }
        }

        if (config.getInputPlacementFileName() != null) {
            placeInstancesWithManualPlacementFile();
        } else {
            placeModuleInstancesAutomatically(idealPlacement);
        }

        if (config.getOutputPlacementFileName() != null) {
            writePlacementToFile(getNewPlacementMap(), config.getOutputPlacementFileName());
        }
    }

    private void finalizeStandaloneArray() {
        if (config.getTopDesign() != null) {
            throw new RuntimeException("Cannot call finalizeStandaloneArray on an array with a top level design");
        }
        EDIFCell top = array.getTopEDIFCell();
        EDIFHierNet clkNet = array.getNetlist().getHierNetFromName(getKernelClockName());
        if (clkNet == null) {
            // Create BUFG and clock net, then connect to all instances
            Cell bufg = createBUFGCE(array, top, "bufg", array.getDevice().getSite("BUFGCE_X2Y0"));
            Net clk = array.createNet(getKernelClockName());
            clk.connect(bufg, "O");
            Net clkIn = array.createNet(getKernelClockName() + "_in");
            clkIn.connect(bufg, "I");
            EDIFPort clkInPort = top.createPort(getKernelClockName(), EDIFDirection.INPUT, 1);
            clkIn.getLogicalNet().createPortInst(clkInPort);
            EDIFNet logClkNet = clk.getLogicalNet();
            for (EDIFCellInst inst : top.getCellInsts()) {
                EDIFPort port = inst.getPort(getKernelClockName());
                if (port != null) {
                    logClkNet.createPortInst(port, inst);
                }
            }
        }

        // Port up unconnected inputs
        for (EDIFPort topPort : modules.get(0).getNetlist().getTopCell().getPorts()) {
            if (topPort.isInput()) {
                if (top.getPort(topPort.getName()) == null) {
                    EDIFPort port = top.createPort(topPort);
                    if (port.isBus()) {
                        for (int j = 0; j < port.getWidth(); j++) {
                            EDIFNet net = top.createNet(port.getPortInstNameFromPort(j));
                            net.createPortInst(port, j);
                            for (ModuleInst mi : array.getModuleInsts()) {
                                net.createPortInst(port, j, mi.getCellInst());
                            }
                        }
                    } else {
                        EDIFNet net = top.createNet(port.getName());
                        net.createPortInst(port);
                        for (ModuleInst mi : array.getModuleInsts()) {
                            net.createPortInst(port, mi.getCellInst());
                        }
                    }
                }
            }
        }

        PerformanceExplorer.updateClockPeriodConstraint(array, getKernelClockName(), config.getClockPeriod());
        array.setDesignOutOfContext(true);
        array.setAutoIOBuffers(false);
    }

    private static void unrouteStaticNets(Design design) {
        Net gndNet = design.getNet(Net.GND_NET);
        if (gndNet != null) {
            gndNet.unroute();
        }
        Net vccNet = design.getNet(Net.VCC_NET);
        if (vccNet != null) {
            vccNet.unroute();
        }
    }

    private void createFlopHarnessForArray() {
        t.stop().start("Create flop harness");
        // Automatically find bounding PBlock based on used Slices, DSPs, and BRAMs
        Set<Site> usedSites = new HashSet<>();
        for (SiteInst siteInst : array.getSiteInsts()) {
            if (siteInst.getName().contains(SiteInst.STATIC_SOURCE)) {
                continue;
            }
            if (isSLICE(siteInst) || isBRAM(siteInst) || isDSP(siteInst)) {
                usedSites.add(siteInst.getSite());
            }
        }
        PBlock pBlock = new PBlock(array.getDevice(), usedSites);
        InlineFlopTools.createAndPlaceFlopsInlineOnTopPortsNearPins(array, getTopClockName(), pBlock);
    }

    private void routeArray() {
        if (config.isRouteClock() && !config.isRouteDesign()) {
            t.stop().start("Route clock");
            Net clockNet = array.getNet(getTopClockName());
            DesignTools.makePhysNetNameConsistent(array, clockNet);
            DesignTools.createPossiblePinsToStaticNets(array);
            DesignTools.createMissingSitePinInsts(array, clockNet);
            List<SitePinInst> pinsToRoute = clockNet.getPins();

            PartialRouter.routeDesignPartialNonTimingDriven(array, pinsToRoute);
        } else if (config.isRouteDesign()) {
            t.stop().start("Route design");
            PartialCUFR.routeDesignWithUserDefinedArguments(array, new String[]{
                    "--fixBoundingBox",
                    "--useUTurnNodes",
                    "--nonTimingDriven",
            });
        }
    }

    public void createArray() {
        placeArray();

        // Unroute conflicting nets.
        List<Net> overlapping = NetTools.getNetsWithOverlappingNodes(getArray());
        for (Net net : overlapping) {
            System.out.println("[overlap-unroute] " + net.getName());
            net.unroute();
        }
        if (!overlapping.isEmpty()) {
            System.out.println("Found " + overlapping.size() + " overlapping nets, that were unrouted.");
        }

        if (config.isSkipImpl() && config.getTopDesign() == null) {
            finalizeStandaloneArray();
        }

        if (config.shouldUnrouteStaticNets()) {
            unrouteStaticNets(array);
        }

        array.getNetlist().consolidateAllToWorkLibrary(false, true);
        array.flattenDesign();

        if (config.isOutOfContext()) {
            createFlopHarnessForArray();
        }

        routeArray();
    }

    private static Map<Pair<Integer, Integer>, String> foldIdealPlacement(Map<Pair<Integer, Integer>, String> placement,
                                                                          Map<Integer, Integer> newRowMap) {
        if (newRowMap.isEmpty()) {
            return placement;
        }
        // Map from (x,y) coordinate to the moduleInst name placed at that ideal (x,y) coordinate
        Map<Pair<Integer, Integer>, String> newPlacement = new HashMap<>(placement);

        // Check if row updates are unique
        Set<Integer> fromSet = new HashSet<>();
        Set<Integer> toSet = new HashSet<>();
        for (Map.Entry<Integer, Integer> rowUpdate : newRowMap.entrySet()) {
            if (fromSet.contains(rowUpdate.getKey())) {
                throw new RuntimeException("Non-unique source row when folding placement");
            }
            if (toSet.contains(rowUpdate.getValue())) {
                throw new RuntimeException("Non-unique destination row when folding placement");
            }
            fromSet.add(rowUpdate.getKey());
            toSet.add(rowUpdate.getValue());
        }
        if (!fromSet.containsAll(toSet)) {
            throw new RuntimeException("Ideal placement folding provided with a non one-to-one mapping");
        }

        for (Map.Entry<Integer, Integer> rowUpdate : newRowMap.entrySet()) {
            int fromRow = rowUpdate.getKey();
            int toRow = rowUpdate.getValue();
            int currColumn = 0;
            while (placement.containsKey(new Pair<>(currColumn, fromRow))) {
                String cell = placement.get(new Pair<>(currColumn, fromRow));
                newPlacement.put(new Pair<>(currColumn, toRow), cell);
                currColumn++;
            }
            // Remove rest of destination row if rows are not equal length
            while (placement.containsKey(new Pair<>(currColumn, toRow))) {
                newPlacement.remove(new Pair<>(currColumn, toRow));
                currColumn++;
            }
        }
        return newPlacement;
    }

    public static Cell createBUFGCE(Design design, EDIFCell parent, String name, Site location) {
        Cell bufgce = design.createAndPlaceCell(parent, name, Unisim.BUFGCE, location, location.getBEL("BUFCE"));

        bufgce.addProperty("CE_TYPE", "ASYNC", EDIFValueType.STRING);

        // Ensure a VCC cell source in the current cell
        EDIFNet vcc = EDIFTools.getStaticNet(NetType.VCC, parent, design.getNetlist());

        bufgce.getSiteInst().addSitePIP("CEINV", "CE_PREINV");
        bufgce.getSiteInst().addSitePIP("IINV", "I_PREINV");

        if (design.getSeries() == Series.Versal) {
            BEL ceinv = bufgce.getSite().getBEL("CEINV");
            bufgce.getSiteInst().routeIntraSiteNet(design.getVccNet(), ceinv.getPin("CE"), ceinv.getPin("CE_PREINV"));
            design.getVccNet().addPin(new SitePinInst(false, "CE", bufgce.getSiteInst()));
            vcc.createPortInst("CE", bufgce);
        } else if (design.getSeries() == Series.UltraScalePlus) {
            // TODO
        }
        // Remove CE:VCC entry for CE:CE
        bufgce.removePinMapping("CE");
        bufgce.addPinMapping("CE", "CE");

        return bufgce;
    }

    private static boolean boundingBoxStraddlesClockRegion(RelocatableTileRectangle boundingBox) {
        ClockRegion cr0 = boundingBox.getMaxColumnTile().getClockRegion();
        ClockRegion cr1 = boundingBox.getMinColumnTile().getClockRegion();
        ClockRegion cr2 = boundingBox.getMaxRowTile().getClockRegion();
        ClockRegion cr3 = boundingBox.getMinRowTile().getClockRegion();
        return !Stream.of(cr0, cr1, cr2, cr3).allMatch(cr0::equals);
    }

    private static boolean straddlesClockRegion(ModuleInst mi) {
        ClockRegion cr = mi.getAnchor().getSite().getClockRegion();
        for (SiteInst si : mi.getSiteInsts()) {
            if (si.getSite().getClockRegion() != cr) {
                return true;
            }
        }
        return false;
    }

    private static boolean straddlesClockRegionOrRCLK(ModuleInst mi) {
        ClockRegion cr = mi.getAnchor().getSite().getClockRegion();
        int centerRow = getRCLKRowIndex(cr);
        boolean inTop = false;
        boolean inBot = false;
        for (SiteInst si : mi.getSiteInsts()) {
            inTop |= si.getTile().getRow() > centerRow;
            inBot |= si.getTile().getRow() < centerRow;
            if ((inTop && inBot) || si.getSite().getClockRegion() != cr) {
                return true;
            }
        }
        return false;
    }

    private static int getRCLKRowIndex(ClockRegion cr) {
        Tile center = cr.getApproximateCenter();
        int searchGridDim = 0;
        outer:
        while (!center.getName().startsWith("RCLK_")) {
            searchGridDim++;
            for (int row = -searchGridDim; row < searchGridDim; row++) {
                for (int col = -searchGridDim; col < searchGridDim; col++) {
                    Tile neighbor = center.getTileNeighbor(col, row);
                    if (neighbor != null) {
                        neighbor.getName().startsWith("RCLK_");
                        center = neighbor;
                        break outer;
                    }
                }
            }
        }
        return center.getRow();
    }

    public static void main(String[] args) {
        CodePerfTracker t = new CodePerfTracker(ArrayBuilder.class.getName());
        t.start("Init");

        if (ArrayBuilderConfig.hasHelpArg(args)) {
            ArrayBuilderConfig.printHelp();
            return;
        }

        // Create config
        ArrayBuilderConfig config = new ArrayBuilderConfig(args);
        if (!config.isReuseResults()) {
            config.setWorkDir("ArrayBuilder-" + FileTools.getTimeStamp().replace(" ", "-"));
        }

        // Create array builder with config
        ArrayBuilder ab = new ArrayBuilder(config, t);
        ab.initializeArrayBuilder();

        ab.createArray();

        t.stop().start("Write DCP");
        ab.getArray().writeCheckpoint(ArrayBuilderConfig.getOutputName(args));
        t.stop().printSummary();
    }
}
