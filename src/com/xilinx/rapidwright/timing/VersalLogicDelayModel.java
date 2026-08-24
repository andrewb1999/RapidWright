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

package com.xilinx.rapidwright.timing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Versal logic delay as a lookup of characterized timing arcs.
 *
 * <p>Every arc Vivado reports for a placed cell — propagation between two
 * pins, or a setup or hold requirement — is keyed by the BEL the cell sits on
 * and the BEL pins involved, and carries a delay at each corner. Logic delay
 * is a library constant, so this is an exact lookup rather than a model: the
 * characterization found identical delays for the same arc everywhere it
 * appeared.
 *
 * <p>The BEL is qualified by site type ({@code SLICEL.AFF}) because Vivado's
 * delays are specific to it: the same flip-flop in a SLICEL and a SLICEM need
 * not share a clock-to-Q.
 *
 * <p>The table files have two kinds of section:
 * <pre>
 * logic_delay
 * SLICEL.A6LUT A1 O6 prop 91 66
 * SLICEL.AFF CLK D setup 45 30
 *
 * intrasite_delay
 * SLICEL A6LUT/O6 AFF2/D 67 16
 * </pre>
 * The first lists arcs by qualified BEL, from pin, to pin and kind
 * ({@code prop}, {@code setup}, {@code hold}); the second lists connections
 * routed inside a site by site type and the BEL pins at each end. Both carry
 * the slow-max and slow-min values in picoseconds; further columns are
 * ignored.
 */
public class VersalLogicDelayModel implements LogicDelayModel {

    /** Where the arc table lives relative to the RapidWright installation. */
    public static final String DEFAULT_FILE = TimingModel.TIMING_DATA_DIR + "/versal/logic_delay.txt";
    /** Where the intra-site table lives relative to the RapidWright installation. */
    public static final String DEFAULT_INTRASITE_FILE = TimingModel.TIMING_DATA_DIR
            + "/versal/intrasite_delay.txt";

    public static final String KIND_PROP = "prop";
    public static final String KIND_SETUP = "setup";
    public static final String KIND_HOLD = "hold";

    private final Map<String, float[]> arcs = new HashMap<>();
    private final Map<String, float[]> intraSite = new HashMap<>();

    /**
     * Reads one or more table files. Each may hold a {@code logic_delay}
     * section, an {@code intrasite_delay} section, or both; at least one arc
     * must be found overall.
     */
    public VersalLogicDelayModel(Path... tables) throws IOException {
        for (Path t : tables) {
            read(t);
        }
        if (arcs.isEmpty()) {
            throw new IOException("No logic_delay entries in " + java.util.Arrays.toString(tables));
        }
    }

    /**
     * Loads the tables from their default locations in the RapidWright
     * installation. The intra-site table is optional: without it, intra-site
     * connections are unknown rather than the model failing to load.
     */
    public static VersalLogicDelayModel load() throws IOException {
        String file = FileTools.getRapidWrightResourceFileName(DEFAULT_FILE);
        if (file == null) {
            throw new IOException("Cannot locate " + DEFAULT_FILE + ": RAPIDWRIGHT_PATH is not set");
        }
        String intra = FileTools.getRapidWrightResourceFileName(DEFAULT_INTRASITE_FILE);
        if (intra != null && Files.exists(Paths.get(intra))) {
            return new VersalLogicDelayModel(Paths.get(file), Paths.get(intra));
        }
        return new VersalLogicDelayModel(Paths.get(file));
    }

    private void read(Path table) throws IOException {
        String section = null;
        for (String line : Files.readAllLines(table, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.equals("logic_delay") || t.equals("intrasite_delay")) {
                section = t;
                continue;
            }
            if (section == null) {
                continue;
            }
            String[] f = t.split("\\s+");
            if (section.equals("logic_delay")) {
                if (f.length < 6) {
                    continue;
                }
                arcs.put(key(f[0], f[1], f[2], f[3]),
                        new float[] { Float.parseFloat(f[4]), Float.parseFloat(f[5]) });
            } else {
                // site_type from_bel/pin to_bel/pin max min
                if (f.length < 5) {
                    continue;
                }
                intraSite.put(f[0] + " " + f[1] + " " + f[2],
                        new float[] { Float.parseFloat(f[3]), Float.parseFloat(f[4]) });
            }
        }
    }

    public int getIntraSiteArcCount() {
        return intraSite.size();
    }

    private static String key(String bel, String from, String to, String kind) {
        return bel + " " + from + " " + to + " " + kind;
    }

    /** The site-type-qualified BEL name the table is keyed by, or null if the cell is unplaced. */
    public static String qualifiedBEL(Cell cell) {
        SiteInst si = cell.getSiteInst();
        String bel = cell.getBELName();
        if (si == null || bel == null) {
            return null;
        }
        return si.getSiteTypeEnum().name() + "." + bel;
    }

    public int getArcCount() {
        return arcs.size();
    }

    @Override
    public TimingFidelity getFidelity() {
        return TimingFidelity.SIGNOFF;
    }

    private Float lookup(Cell cell, String from, String to, String kind, Corner corner) {
        String bel = qualifiedBEL(cell);
        if (bel == null) {
            return null;
        }
        float[] d = arcs.get(key(bel, from, to, kind));
        return d == null ? null : d[corner == Corner.SLOW_MIN ? 1 : 0];
    }

    @Override
    public Float getPropagationDelayPs(Cell cell, String fromBelPin, String toBelPin, Corner corner) {
        return lookup(cell, fromBelPin, toBelPin, KIND_PROP, corner);
    }

    @Override
    public Float getSetupPs(Cell cell, String clockBelPin, String dataBelPin, Corner corner) {
        return lookup(cell, clockBelPin, dataBelPin, KIND_SETUP, corner);
    }

    @Override
    public Float getHoldPs(Cell cell, String clockBelPin, String dataBelPin, Corner corner) {
        return lookup(cell, clockBelPin, dataBelPin, KIND_HOLD, corner);
    }

    @Override
    public Float getIntraSiteDelayPs(SiteTypeEnum siteType, String fromBelPin, String toBelPin,
                                     Corner corner) {
        float[] d = intraSite.get(siteType.name() + " " + fromBelPin + " " + toBelPin);
        return d == null ? null : d[corner == Corner.SLOW_MIN ? 1 : 0];
    }
}
