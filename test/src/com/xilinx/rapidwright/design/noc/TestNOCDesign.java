/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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

package com.xilinx.rapidwright.design.noc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestNOCDesign {

    public void sanityChecks(Design d) {
        Device dev = d.getDevice();
        NOCDesign nocDesign = d.getNOCDesign();
        for (NOCConnection conn : nocDesign.getAllConnections()) {
            for (Entry<ChannelType,NOCChannel> e : conn.getChannels().entrySet()) {
                NOCChannel ch = e.getValue();
                Assertions.assertNotNull(ch);
                Assertions.assertEquals(ch.getRequiredLatency(), 300);
                Assertions.assertNotNull(ch.getChannelPath());
                Assertions.assertEquals(e.getKey(), ch.getChannelType());
            }
        }

        for (Entry<String,NOCClient> e : nocDesign.getClients().entrySet()) {
            Assertions.assertEquals(e.getKey(), e.getValue().getName());
            NOCClient client = e.getValue();
            Assertions.assertNotNull(client);
            Assertions.assertNotNull(dev.getSite(client.getLocation()));
        }
    }

    public void testClientsAreEqual(NOCClient gold, NOCClient test) {
        Assertions.assertEquals(gold.getName(),test.getName());
        Assertions.assertEquals(gold.getComponentType(),test.getComponentType());
        Assertions.assertEquals(gold.getConnections().size(),test.getConnections().size());
        Assertions.assertEquals(gold.getLocation(),test.getLocation());
        Assertions.assertEquals(gold.getProtocol(),test.getProtocol());
        Assertions.assertEquals(gold.isDDRC(),test.isDDRC());
        Assertions.assertEquals(gold.isFabricClient(),test.isFabricClient());
    }

    @Test
    public void testNOCDesign(@TempDir Path tempDir) {
        String dcpName = "noc_tutorial_routed.dcp";
        Design d = RapidWrightDCP.loadDCP(dcpName);
        sanityChecks(d);

        Path testDCP = tempDir.resolve(dcpName);
        d.writeCheckpoint(testDCP);

        Design d2 = Design.readCheckpoint(testDCP);
        sanityChecks(d2);

        NOCDesign nocGold = d.getNOCDesign();
        NOCDesign nocTest = d2.getNOCDesign();

        Assertions.assertEquals(nocGold.getFrequency(), nocTest.getFrequency());
        List<NOCConnection> goldConns = nocGold.getAllConnections();
        List<NOCConnection> testConns = nocTest.getAllConnections();
        Assertions.assertEquals(goldConns.size(), testConns.size());
        for (int i=0; i < goldConns.size(); i++) {
            NOCConnection goldConn = goldConns.get(i);
            NOCConnection testConn = testConns.get(i);

            Assertions.assertEquals(goldConn.getCommType(),testConn.getCommType());
            Assertions.assertEquals(goldConn.getEstimatedReadBandwidth(),testConn.getEstimatedReadBandwidth());
            Assertions.assertEquals(goldConn.getEstimatedWriteBandwidth(),testConn.getEstimatedWriteBandwidth());
            Assertions.assertEquals(goldConn.getPort(),testConn.getPort());
            Assertions.assertEquals(goldConn.getReadBandwidth(),testConn.getReadBandwidth());
            Assertions.assertEquals(goldConn.getReadLatency(),testConn.getReadLatency());
            Assertions.assertEquals(goldConn.getWriteBandwidth(),testConn.getWriteBandwidth());
            Assertions.assertEquals(goldConn.getWriteLatency(),testConn.getWriteLatency());
            Assertions.assertEquals(goldConn.isRouted(),testConn.isRouted());

            NOCMaster goldMaster = goldConn.getSource();
            NOCMaster testMaster = testConn.getSource();
            testClientsAreEqual(goldMaster, testMaster);
            Assertions.assertEquals(goldMaster.getReadTC(),testMaster.getReadTC());
            Assertions.assertEquals(goldMaster.getWriteTC(),testMaster.getWriteTC());

            NOCSlave goldSlave = goldConn.getDest();
            NOCSlave testSlave = testConn.getDest();
            testClientsAreEqual(goldSlave, testSlave);
            Assertions.assertEquals(goldSlave.getPorts(), testSlave.getPorts());


            Map<ChannelType, NOCChannel> goldMap = goldConn.getChannels();
            Map<ChannelType, NOCChannel> testMap = goldConn.getChannels();
            Assertions.assertEquals(goldMap.size(), testMap.size());
            for (Entry<ChannelType, NOCChannel> e : goldMap.entrySet()) {
                Assertions.assertTrue(testMap.containsKey(e.getKey()));
                NOCChannel goldCh = e.getValue();
                NOCChannel testCh = testMap.get(e.getKey());
                Assertions.assertEquals(goldCh.getChannelPath(),testCh.getChannelPath());
                Assertions.assertEquals(goldCh.getChannelType(),testCh.getChannelType());
                Assertions.assertEquals(goldCh.getEstimatedBandwidth(),testCh.getEstimatedBandwidth());
                Assertions.assertEquals(goldCh.getEstimatedLatency(),testCh.getEstimatedLatency());
                Assertions.assertEquals(goldCh.getRequiredBandwidth(),testCh.getRequiredBandwidth());
                Assertions.assertEquals(goldCh.getRequiredLatency(),testCh.getRequiredLatency());
            }
        }
    }

    /** A 2026.1 traffic file (Vivado 2026.1, Alveo V80 AMR shell: HBM and DDR controllers, CIPS, a PL NSU). */
    private static final String TRAFFIC_2026_1 = "/noc/v80_base_2026_1.nts";

    private static String readResource(String name) throws IOException {
        try (InputStream in = TestNOCDesign.class.getResourceAsStream(name)) {
            Assertions.assertNotNull(in, "missing test resource " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static NOCDesign loadTraffic(String json) {
        NOCDesign nd = new NOCDesign();
        nd.loadTraffic(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        return nd;
    }

    private static JSONObject writeTraffic(NOCDesign nd) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        nd.writeTraffic(out);
        return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    private static Map<String, JSONObject> byKey(JSONArray a, String... fields) {
        Map<String, JSONObject> m = new HashMap<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            StringBuilder key = new StringBuilder();
            for (String f : fields) key.append(o.optString(f)).append('|');
            Assertions.assertNull(m.put(key.toString(), o), "duplicate " + key);
        }
        return m;
    }

    /** Every instance, path and system property of {@code gold}, field by field, in {@code test}. */
    private static void assertTrafficEquivalent(JSONObject gold, JSONObject test) {
        Assertions.assertEquals(gold.keySet(), test.keySet());
        Assertions.assertTrue(gold.getJSONObject("SystemProperties").similar(test.getJSONObject("SystemProperties")),
                "SystemProperties " + gold.getJSONObject("SystemProperties") + " vs " + test.getJSONObject("SystemProperties"));
        for (String[] list : new String[][] {{"LogicalInstances", "Name"}, {"Paths", "From", "To", "Port"}}) {
            String[] key = java.util.Arrays.copyOfRange(list, 1, list.length);
            Map<String, JSONObject> g = byKey(gold.getJSONArray(list[0]), key), r = byKey(test.getJSONArray(list[0]), key);
            Assertions.assertEquals(g.keySet(), r.keySet(), list[0]);
            for (Entry<String, JSONObject> e : g.entrySet()) {
                Assertions.assertTrue(e.getValue().similar(r.get(e.getKey())),
                        list[0] + " " + e.getKey() + ":\n  gold " + e.getValue() + "\n  test " + r.get(e.getKey()));
            }
        }
    }

    @Test
    public void testTrafficRoundTrip2026_1() throws IOException {
        String json = readResource(TRAFFIC_2026_1);
        NOCDesign nd = loadTraffic(json);
        Assertions.assertEquals(27, nd.getClients().size());
        Assertions.assertEquals(84, nd.getAllConnections().size());
        // the HBM controllers' ports and memory parameters are modeled, like a DDR controller's
        NOCSlave hbm = nd.getSlaveClients().get("v80_base_i/axi_noc_cips/inst/MC_hbmc/inst/hbm_st1/I_hbm_chnl2/I_hbm_mc");
        Assertions.assertEquals(ComponentType.HBMMC, hbm.getComponentType());
        Assertions.assertEquals(java.util.Arrays.asList("PORT0", "PORT1", "PORT2", "PORT3"), hbm.getPorts());
        Assertions.assertEquals("1", hbm.getMemParams().get("StackNumber"));
        // what is not modeled is kept: the paths' InitialBoot and WriteOrder, a master's Remap
        NOCMaster pcie = nd.getMasterClients().get("v80_base_i/axi_noc_cips/inst/S00_AXI_nmu/bd_c2de_S00_AXI_nmu_0_top_INST/NOC_NMU128_INST");
        Assertions.assertTrue(pcie.getUnmodeledFields().has("Remap"));

        assertTrafficEquivalent(new JSONObject(json), writeTraffic(nd));
        // a second round trip is stable too
        assertTrafficEquivalent(new JSONObject(json), writeTraffic(loadTraffic(writeTraffic(nd).toString())));
    }

    @Test
    public void testTrafficEditsAndCopies2026_1() throws IOException {
        NOCDesign nd = loadTraffic(readResource(TRAFFIC_2026_1));
        // a modeled field changed in RapidWright is what is written, unmodeled fields stay
        NOCConnection path = nd.getAllConnections().get(0);
        path.setReadBandwidth(123);
        JSONObject written = null;
        JSONArray paths = writeTraffic(nd).getJSONArray("Paths");
        for (int i = 0; i < paths.length(); i++) {
            JSONObject p = paths.getJSONObject(i);
            if (p.getString("From").equals(path.getSource().getName()) && p.getString("To").equals(path.getDest().getName())
                    && p.getString("Port").equals(path.getPort())) {
                written = p;
            }
        }
        Assertions.assertNotNull(written);
        Assertions.assertEquals(123, written.getInt("ReadBW"));
        Assertions.assertEquals(path.getUnmodeledFields().getBoolean("InitialBoot"), written.getBoolean("InitialBoot"));
        // copies carry everything, and do not share it
        NOCSlave hbm = nd.getSlaveClients().get("v80_base_i/axi_noc_cips/inst/MC_hbmc/inst/hbm_st1/I_hbm_chnl2/I_hbm_mc");
        NOCSlave copy = new NOCSlave(hbm);
        Assertions.assertTrue(hbm.toTrafficJSONObject().similar(copy.toTrafficJSONObject()));
        copy.getMemParams().put("StackNumber", "0");
        copy.getUnmodeledFields().put("DesignName", "changed");
        Assertions.assertEquals("1", hbm.getMemParams().get("StackNumber"));
        Assertions.assertEquals("/axi_noc_cips/HBM10", hbm.getUnmodeledFields().getString("DesignName"));
    }
}
