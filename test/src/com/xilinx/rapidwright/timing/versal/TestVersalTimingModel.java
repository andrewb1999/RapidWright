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

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.timing.DelayModel;
import com.xilinx.rapidwright.timing.DelayModelBuilder;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Checks that the shipped Versal delay data files load and that the lookup rules behave.
 * Delay ranges below are loose bounds around values observed on xcv80 -2MHP with Vivado 2026.1.
 */
public class TestVersalTimingModel {

    @Test
    public void testChildKey() {
        Assertions.assertEquals(VersalDelayTerms.LEAF, VersalDelayTerms.childKey(Collections.emptyList()));
        Assertions.assertEquals("SDQNODE|VLONG12",
                VersalDelayTerms.childKey(Arrays.asList(IntentCode.NODE_VLONG12, IntentCode.NODE_SDQNODE, IntentCode.NODE_VLONG12)));
    }

    @Test
    public void testWireFamilies() {
        Assertions.assertEquals("EE_E", VersalTimingModel.hFamily("OUT_EE12_E_BEG3"));
        Assertions.assertEquals("RED", VersalTimingModel.hFamily("INT_SDQ_RED_1"));
        // dedicated nodes: the wire name without digits (CLE carry vs cascades vs IO pins)
        Assertions.assertEquals("CLE_SLICEM_TOP_COUT", VersalTimingModel.dedicatedFamily("CLE_SLICEM_TOP_1_COUT"));
        Assertions.assertEquals("XPHY_CORE_O_PIN", VersalTimingModel.dedicatedFamily("XPHY_CORE_0_O1_2__PIN"));
        Assertions.assertEquals("BLI_GRP_C_BLI_LOGIC_OUTS", VersalTimingModel.dedicatedFamily("BLI_GRP1_C_BLI_LOGIC_OUTS3"));
        // hard-block tile families for the IRI and OUTPUT classes
        Assertions.assertEquals("NOC_NSU", VersalTimingModel.tileFamily("NOC_NSU512_TOP"));
        Assertions.assertEquals("NOC_NMU", VersalTimingModel.tileFamily("NOC_NMU512_TOP"));
        Assertions.assertEquals("DSP", VersalTimingModel.tileFamily("DSP_ROCF_T_TILE"));
        Assertions.assertEquals("BRAM", VersalTimingModel.tileFamily("BRAM_ROCF_TL_TILE"));
    }

    @Test
    public void testOrderedSignatureColumnClasses() {
        // a NULL is a spacer column with no class and is counted; fabric types are dropped
        Assertions.assertEquals("INTF_ROCF_BL_TILEx1+INTF_ROCF_BR_TILEx1+MISR_TILEx1+NOC_NPS_VNOC_TOPx1",
                VersalTimingModel.orderedSignature(Arrays.asList("INTF_ROCF_BL_TILE", "NOC_NPS_VNOC_TOP", "MISR_TILE", "INTF_ROCF_BR_TILE")));
        Assertions.assertEquals("BRAM_ROCF_TR_TILEx1+NULLx1",
                VersalTimingModel.orderedSignature(Arrays.asList("BRAM_ROCF_TR_TILE", "CPIPE_TOP_TILE", "NULL")));
        Assertions.assertEquals("-", VersalTimingModel.orderedSignature(Arrays.asList("NULL", "NULL")));
        // xcv80 column classes: the vertical NoC column beside INT column 555/560, the DSP column beside 172/177,
        // the URAM column at 458 (its REBUF rows do not count), the CBRK spacer (fabric), a classless spacer
        com.xilinx.rapidwright.device.Device d = com.xilinx.rapidwright.device.Device.getDevice("xcv80");
        com.xilinx.rapidwright.device.TileTypeEnum[] cls = VersalTimingModel.columnClasses(d);
        Assertions.assertEquals(com.xilinx.rapidwright.device.TileTypeEnum.NOC_NPS_VNOC_TOP, cls[557]);
        Assertions.assertEquals(com.xilinx.rapidwright.device.TileTypeEnum.MISR_TILE, cls[558]);
        Assertions.assertEquals(com.xilinx.rapidwright.device.TileTypeEnum.DSP_ROCF_T_TILE, cls[174]);
        Assertions.assertEquals(com.xilinx.rapidwright.device.TileTypeEnum.URAM_ROCF_TL_TILE, cls[458]);
        Assertions.assertEquals(com.xilinx.rapidwright.device.TileTypeEnum.CBRK_TOP_TILE, cls[436]);
        Assertions.assertNull(cls[175], "a spacer column with no recurring tile type has no class");
        Assertions.assertEquals(com.xilinx.rapidwright.device.TileTypeEnum.INT, cls[555]);
        // on a row every crossed tile counts as its column class; in a column as itself
        Assertions.assertEquals(com.xilinx.rapidwright.device.TileTypeEnum.URAM_ROCF_TL_TILE,
                VersalTimingModel.crossingTileType(d, d.getTile(131, 458), true));
        Assertions.assertEquals(com.xilinx.rapidwright.device.TileTypeEnum.URAM_DELAY_ROCF_TL_TILE,
                VersalTimingModel.crossingTileType(d, d.getTile(131, 458), false));
    }

    @Test
    public void testIntersiteTerms() {
        VersalDelayTerms t = new VersalDelayTerms();
        Assertions.assertTrue(t.getEdgeEntryCount() > 500, "expected a populated EDGE table");
        Assertions.assertTrue(t.getEdgeXEntryCount() > 100, "expected crossing-signature (EDGEX) corrections");
        // a long wire across a hard-block interface column is slower than the additive tile terms say
        float hb = t.crossingCorrection("NODE_HLONG6:EE", IntentCode.NODE_HLONG6, IntentCode.NODE_SDQNODE,
                "INTF_HB_ROCF_TL_TILEx1+INTF_HB_ROCF_TR_TILEx1", "INTF_HB_ROCF_TL_TILEx1+INTF_HB_ROCF_TR_TILEx1");
        float plain = t.crossingCorrection("NODE_HLONG6:EE", IntentCode.NODE_HLONG6, IntentCode.NODE_SDQNODE, "-", "-");
        Assertions.assertTrue(hb > plain, "hard-block crossing correction " + hb + " vs fabric-only " + plain);
        int[] miss = new int[1];
        // a vertical long line driving another vertical long line books about one long-line step
        float longStep = t.edgeDelay(IntentCode.NODE_VLONG12, IntentCode.NODE_VLONG12, "VLONG12", "VLONG12", miss);
        Assertions.assertTrue(longStep > 60 && longStep < 95, "VLONG12 step " + longStep);
        // ... and so does the last long line before a mux: the fitter re-attributes Vivado's per-instance
        // entry/exit split so that every wire's cost sits on its own exit hops (fit_versal_model.py load_edges)
        float lastLong = t.edgeDelay(IntentCode.NODE_VLONG12, IntentCode.NODE_VLONG12, "SDQNODE", "VLONG12", miss);
        Assertions.assertTrue(lastLong > 60 && lastLong < 95, "last VLONG12 " + lastLong);
        // an unseen sibling set falls back to the child-set entry, not to a miss
        float fallback = t.edgeDelay(IntentCode.NODE_VLONG12, IntentCode.NODE_VLONG12, "VLONG12", "NO_SUCH_SIBLING", miss);
        Assertions.assertTrue(fallback > 60 && fallback < 95, "fallback " + fallback);
        Assertions.assertEquals(0, miss[0]);
        // an SLR crossing is by far the most expensive single node
        float sll = t.edgeDelay(IntentCode.NODE_SLL_INPUT, IntentCode.NODE_SLL_DATA, "SLL_OUTPUT", "SLL_DATA", miss);
        Assertions.assertTrue(sll > 400 && sll < 600, "SLL " + sll);
        // completely unknown pair records a miss and returns 0
        Assertions.assertEquals(0f, t.edgeDelay(IntentCode.NODE_GLOBAL_VDISTR, IntentCode.NODE_GLOBAL_LEAF, "LEAF", "LEAF", miss));
        Assertions.assertEquals(1, miss[0]);
    }

    @Test
    public void testNodeDelayEstimator() {
        Device device = Device.getDevice("xcv80");
        VersalDelayEstimator est = new VersalDelayEstimator(device, false);
        Assertions.assertTrue(est.chargesEveryNode());
        // one node of each intent of interest from an INT tile in the middle of the device
        Tile intTile = device.getTile("INT_X100Y200");
        Assertions.assertNotNull(intTile);
        Node vlong = null, hlong = null, inode = null, imux = null;
        for (int w = 0; w < intTile.getWireCount(); w++) {
            Node n = Node.getNode(intTile, w);
            if (n == null || n.getTile() != intTile) continue;
            switch (n.getIntentCode()) {
                case NODE_VLONG12: if (vlong == null) vlong = n; break;
                case NODE_HLONG10: if (hlong == null) hlong = n; break;
                case NODE_INODE:   if (inode == null) inode = n; break;
                case NODE_IMUX:    if (imux == null) imux = n; break;
                default: break;
            }
        }
        Assertions.assertNotNull(vlong);
        Assertions.assertNotNull(hlong);
        Assertions.assertNotNull(inode);
        Assertions.assertNotNull(imux);
        // marginals: a wire's own delay plus its typical load (node_delay_terms.txt)
        short dv = est.getDelayOf(vlong), dh = est.getDelayOf(hlong), di = est.getDelayOf(inode), dm = est.getDelayOf(imux);
        Assertions.assertTrue(dv > 60 && dv < 95, "VLONG12 " + dv);
        Assertions.assertTrue(dh > 120 && dh < 220, "HLONG10 " + dh);
        Assertions.assertTrue(di > 20 && di < 45, "INODE " + di);
        Assertions.assertEquals(0, dm, "IMUX (Vivado books its delay on the pin node) " + dm);
        // the long-wire helpers know the Versal intents
        Assertions.assertTrue(DelayEstimatorBase.isLong(vlong));
        Assertions.assertTrue(DelayEstimatorBase.isLong(hlong));
        Assertions.assertFalse(DelayEstimatorBase.isLong(inode));
        Assertions.assertEquals(Math.round(est.getLongToLongExtra()), DelayEstimatorBase.getExtraDelay(vlong, true));
        Assertions.assertEquals(0, DelayEstimatorBase.getExtraDelay(vlong, false));
        Assertions.assertEquals(0, DelayEstimatorBase.getExtraDelay(inode, true));
        // the A* estimate: long wires are the fastest way across the device, so a few ps per tile
        Assertions.assertTrue(est.getPsPerTileX() > 5 && est.getPsPerTileX() < 40, "ps/tile X " + est.getPsPerTileX());
        Assertions.assertTrue(est.getPsPerTileY() > 2 && est.getPsPerTileY() < 20, "ps/tile Y " + est.getPsPerTileY());
    }

    @Test
    public void testAllCornersLoad() {
        float prevStep = -1;
        for (VersalCorner c : VersalCorner.values()) {
            VersalDelayTerms t = new VersalDelayTerms(c);
            Assertions.assertTrue(t.getEdgeEntryCount() > 500, c + ": expected a populated EDGE table");
            float step = t.edgeDelay(IntentCode.NODE_VLONG12, IntentCode.NODE_VLONG12, "VLONG12", "VLONG12", null);
            Assertions.assertTrue(step > 30 && step < 100, c + ": VLONG12 step " + step);
            DelayModel dm = DelayModelBuilder.getDelayModel("versal", c.getSuffix());
            short clkq = dm.getLogicDelay(dm.getBELIndex("AFF@SLICEL"), "CLK", "Q");   // BEL sections are per site type
            Assertions.assertTrue(clkq > 40 && clkq < 120, c + ": CLK->Q " + clkq);
            if (c == VersalCorner.SLOW_MAX) prevStep = step;
            // the fast corners are faster than the slow-max corner
            if (c == VersalCorner.FAST_MAX || c == VersalCorner.FAST_MIN) Assertions.assertTrue(step < prevStep, c + " not faster than slow max");
        }
    }

    @Test
    public void testIntrasiteAndLogicDelays() {
        DelayModel dm = DelayModelBuilder.getDelayModel("versal");
        // LUT A1 -> O6 is the slowest LUT input, clock-to-Q of a slice flop about 90 ps
        short lutIdx = dm.getBELIndex("A6LUT@SLICEL");
        short a1 = dm.getLogicDelay(lutIdx, "A1", "O6");
        short a6 = dm.getLogicDelay(lutIdx, "A6", "O6");
        Assertions.assertTrue(a1 > 100 && a1 < 160, "A1->O6 " + a1);
        Assertions.assertTrue(a6 > 15 && a6 < 50 && a6 < a1, "A6->O6 " + a6);
        short ffIdx = dm.getBELIndex("AFF@SLICEL");
        short clkq = dm.getLogicDelay(ffIdx, "CLK", "Q");
        Assertions.assertTrue(clkq > 70 && clkq < 110, "CLK->Q " + clkq);
        // per-letter and per-site-type sections: the CE setup check is 87 ps on an A flop, 90 on an H flop
        short ceA = dm.getLogicDelay(dm.getBELIndex("AFF@SLICEL"), "CLK", "CE");
        short ceH = dm.getLogicDelay(dm.getBELIndex("HFF2@SLICEM"), "CLK", "CE");
        Assertions.assertTrue(ceA >= 85 && ceA <= 89 && ceH >= 89 && ceH <= 92, "CE setup A " + ceA + " H " + ceH);
        Assertions.assertNotNull(dm.getIntraSiteDelay(SiteTypeEnum.SLICEM, "A4", "A6LUT/A4"), "SLICEM has its own site section");
        // site pin to LUT input is a few tens of ps
        Short a4 = dm.getIntraSiteDelay(SiteTypeEnum.SLICEL, "A4", "A6LUT/A4");
        Assertions.assertNotNull(a4);
        Assertions.assertTrue(a4 > 20 && a4 < 90, "A4 -> A6LUT/A4 " + a4);
        // letters B..H map onto A in the model
        Assertions.assertEquals("A3", VersalTimingModel.toLetterA("C3"));
        Assertions.assertEquals("AFF/Q", VersalTimingModel.toLetterA("HFF/Q"));
        Assertions.assertEquals("A6LUT/A4", VersalTimingModel.toLetterA("H6LUT/A4"));
        Assertions.assertEquals("CLK", VersalTimingModel.toLetterA("CLK"));
    }

    @Test
    public void testClockModelTables() {
        VersalClockModel cm = new VersalClockModel();
        Assertions.assertTrue(cm.getContextCount() > 1000);
        // VT-drift coefficients are negative (capture-side delay elements with more delay reduce the credit)
        for (int c = 0; c < 4; c++) {
            float[] f = cm.getVtDrift(c);
            Assertions.assertEquals(3, f.length);
            Assertions.assertTrue(f[0] < 0 && f[1] < 0 && f[2] < 0, "VTDRIFT corner " + c);
        }
        // the leaf clock node's own wire delay is known and ordered slow max > slow min > fast max > fast min
        float[] w = cm.wireDelayByFamily("CLK_LEAF_SITES_0_O");
        Assertions.assertNotNull(w);
        Assertions.assertTrue(w[0] > w[1] && w[1] > w[2] && w[2] > w[3], java.util.Arrays.toString(w));
        Assertions.assertTrue(w[0] > 50 && w[0] < 150, java.util.Arrays.toString(w));
    }

    @Test
    public void testClockStateBuckets() {
        // a state keeps totals and per-bucket moments; arrival = sum of means + sqrt(sum of variances)
        float[] s = VersalClockModel.expand(new float[] {100, 90, 70, 60, 0, 0, 0, 0});
        float[] stepLocal = {10, 9, 7, 6, 4, 4, 1, 1, 0};
        float[] stepX = {20, 18, 14, 12, 5, 5, 2, 2, 1};
        s = VersalClockModel.add(s, stepLocal);
        s = VersalClockModel.add(s, stepX);
        Assertions.assertEquals(VersalClockModel.STATE_SIZE, s.length);
        Assertions.assertEquals(130f, s[0]);                 // total mean, slow max
        Assertions.assertEquals(9f, s[4]);                   // total variance, slow max
        Assertions.assertEquals(10f, s[8]);                  // Local bucket mean
        Assertions.assertEquals(4f, s[8 + 4]);               // Local bucket variance
        Assertions.assertEquals(20f, s[16]);                 // GlobalX bucket mean
        Assertions.assertEquals(0f, s[24]);                  // GlobalY untouched
        float[] a = VersalClockModel.ClockTree.arrival(s);
        Assertions.assertEquals(130 + 3, a[0], 1e-4);
        Assertions.assertEquals(117 - 3, a[1], 1e-4);
    }
}
