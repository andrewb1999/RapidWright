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

    /** An HBM NMU as a memory tile brings one (its name under the array's hierarchy). */
    private static NOCMaster hbmNmu(String name) {
        NOCMaster m = new NOCMaster();
        m.setName(name);
        m.setComponentType(ComponentType.HBM_NMU);
        m.setProtocol(ProtocolType.AXI_MEMORY_MAPPED);
        m.setReadTC(TrafficClass.BEST_EFFORT);
        m.setWriteTC(TrafficClass.BEST_EFFORT);
        return m;
    }

    private static NOCConnection path(NOCMaster from, NOCSlave to, String port) {
        NOCConnection c = new NOCConnection();
        c.setSource(from);
        c.setDest(to);
        c.setPort(port);
        c.setCommType(CommunicationType.MEMORY_MAPPED_FULL);
        c.setReadBandwidth(500);
        c.setWriteBandwidth(500);
        c.setReadLatency(300);
        c.setWriteLatency(300);
        c.setReadAverageBurst(4);
        c.setWriteAverageBurst(4);
        return c;
    }

    @Test
    public void testAddConnection2026_1() throws IOException {
        NOCDesign nd = loadTraffic(readResource(TRAFFIC_2026_1));
        String hbmName = "v80_base_i/axi_noc_cips/inst/MC_hbmc/inst/hbm_st1/I_hbm_chnl2/I_hbm_mc";
        NOCSlave hbm = nd.getSlaveClients().get(hbmName);
        NOCMaster nmu = hbmNmu("mem_x0/mem_tile_bd_i/axi_noc_0/inst/HBM00_AXI_nmu/bd_e038_HBM00_AXI_nmu_0_top_INST/NOC_NMU_HBM2E_INST");

        // a new client is registered with the design; the existing destination is kept
        NOCConnection c = path(nmu, hbm, "PORT0");
        nd.addConnection(c);
        Assertions.assertSame(nmu, nd.getMasterClients().get(nmu.getName()));
        Assertions.assertSame(hbm, nd.getSlaveClients().get(hbmName));
        Assertions.assertEquals(28, nd.getClients().size());
        Assertions.assertEquals(85, nd.getAllConnections().size());
        Assertions.assertTrue(nmu.getConnections().contains(c) && hbm.getConnections().contains(c));
        // adding it again changes nothing
        nd.addConnection(c);
        Assertions.assertEquals(85, nd.getAllConnections().size());
        Assertions.assertEquals(1, nmu.getConnections().size());

        // written and read back: the new client and path are there, the rest as before
        JSONObject written = writeTraffic(nd);
        NOCDesign back = loadTraffic(written.toString());
        Assertions.assertEquals(28, back.getClients().size());
        Assertions.assertEquals(85, back.getAllConnections().size());
        NOCMaster nmuBack = back.getMasterClients().get(nmu.getName());
        Assertions.assertNotNull(nmuBack);
        Assertions.assertEquals(ComponentType.HBM_NMU, nmuBack.getComponentType());
        NOCConnection cBack = nmuBack.getConnections().get(0);
        Assertions.assertEquals(hbmName, cBack.getDest().getName());
        Assertions.assertEquals("PORT0", cBack.getPort());
        Assertions.assertEquals(500, cBack.getReadBandwidth());

        // a different client under a name the design already uses is refused
        Assertions.assertThrows(IllegalArgumentException.class, () -> nd.addConnection(path(hbmNmu(nmu.getName()), hbm, "PORT1")));
        NOCSlave impostor = new NOCSlave(hbm);
        Assertions.assertThrows(IllegalArgumentException.class, () -> nd.addConnection(path(nmu, impostor, "PORT1")));
        // and a connection without an end
        Assertions.assertThrows(IllegalArgumentException.class, () -> nd.addConnection(new NOCConnection()));
        Assertions.assertEquals(85, nd.getAllConnections().size());
    }

    @Test
    public void testMergeConnectRemove2026_1() throws IOException {
        NOCDesign nd = loadTraffic(readResource(TRAFFIC_2026_1));
        String hbm0 = "v80_base_i/axi_noc_cips/inst/MC_hbmc/inst/hbm_st0/I_hbm_chnl0/I_hbm_mc";
        String nmu = "mem_tile_bd_i/axi_noc_0/inst/HBM00_AXI_nmu/bd_e038_HBM00_AXI_nmu_0_top_INST/NOC_NMU_HBM2E_INST";
        String nmu0 = "mem_x0/" + nmu, nmu1 = "mem_x1/" + nmu;

        // another design's clients (a memory row's HBM NMUs) join the shell's
        NOCDesign row = new NOCDesign();
        row.addClient(hbmNmu(nmu0));
        row.addClient(hbmNmu(nmu1));
        nd.merge(row);
        Assertions.assertEquals(29, nd.getClients().size());
        Assertions.assertEquals(84, nd.getAllConnections().size());
        // a client both have: refused, and nothing merged
        NOCDesign again = new NOCDesign();
        again.addClient(hbmNmu("mem_x2/" + nmu));
        again.addClient(hbmNmu(nmu0));
        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class, () -> nd.merge(again));
        Assertions.assertTrue(e.getMessage().contains(nmu0));
        Assertions.assertEquals(29, nd.getClients().size());

        // connected by name to both of a controller's pseudo channels, at Vivado's defaults
        NOCConnection pc0 = nd.addConnection(nmu0, hbm0, "PORT0", 16, 16);
        NOCConnection pc1 = nd.addConnection(nmu0, hbm0, "PORT2", 16, 16);
        nd.addConnection(nmu1, hbm0, "PORT1", 16, 16);
        Assertions.assertEquals(87, nd.getAllConnections().size());
        Assertions.assertEquals(CommunicationType.MEMORY_MAPPED_FULL, pc0.getCommType());
        Assertions.assertEquals(NOCDesign.DEFAULT_LATENCY, pc1.getReadLatency());
        Assertions.assertEquals(NOCDesign.DEFAULT_AVERAGE_BURST, pc1.getWriteAverageBurst());
        JSONObject written = writeTraffic(nd);
        int found = 0;
        for (Object o : written.getJSONArray(NOCJSONUtil.JSON_FIELD_PATHS)) {
            JSONObject path = (JSONObject) o;
            if (!path.getString("From").equals(nmu0)) continue;
            found++;
            Assertions.assertEquals(hbm0, path.getString("To"));
            Assertions.assertEquals("MM_ReadWrite", path.getString("CommType"));
            Assertions.assertEquals(16, path.getInt("ReadBW"));
            Assertions.assertEquals(300, path.getInt("WriteLatency"));
        }
        Assertions.assertEquals(2, found);
        Assertions.assertEquals(87, loadTraffic(written.toString()).getAllConnections().size());

        // an NSU's one port is PORT0, as Vivado names it, whether given or not
        String nsu = "v80_base_i/axi_noc_cips/inst/M00_AXI_nsu/bd_c2de_M00_AXI_nsu_0_top_INST/NOC_NSU512_INST";
        NOCMaster host = nd.getMasterClients().get("v80_base_i/axi_noc_cips/inst/S00_AXI_nmu/bd_c2de_S00_AXI_nmu_0_top_INST/NOC_NMU128_INST");
        Assertions.assertEquals(NOCDesign.SINGLE_PORT, nd.addConnection(host.getName(), nsu, null, 1, 1).getPort());
        Assertions.assertThrows(IllegalArgumentException.class, () -> nd.addConnection(nmu0, nsu, "PORT1", 1, 1));
        nd.removeConnection(host.getConnections().get(host.getConnections().size() - 1));

        // refused: a port the controller lacks, none for a controller, a client the design lacks, a repeat
        Assertions.assertThrows(IllegalArgumentException.class, () -> nd.addConnection(nmu0, hbm0, "PORT4", 16, 16));
        Assertions.assertThrows(IllegalArgumentException.class, () -> nd.addConnection(nmu0, hbm0, null, 16, 16));
        Assertions.assertThrows(IllegalArgumentException.class, () -> nd.addConnection("mem_x9/" + nmu, hbm0, "PORT0", 16, 16));
        Assertions.assertThrows(IllegalArgumentException.class, () -> nd.addConnection(nmu0, hbm0, "PORT0", 16, 16));
        Assertions.assertEquals(87, nd.getAllConnections().size());

        // removing a client removes its connections, from the controller's list too
        int hbmPaths = nd.getSlaveClients().get(hbm0).getConnections().size();
        nd.removeClient(nmu0);
        Assertions.assertNull(nd.getMasterClients().get(nmu0));
        Assertions.assertEquals(85, nd.getAllConnections().size());
        Assertions.assertEquals(hbmPaths - 2, nd.getSlaveClients().get(hbm0).getConnections().size());
        nd.removeClient(nmu0);
        Assertions.assertEquals(28, nd.getClients().size());

        // an address range off a controller with two
        NOCSlave mc = nd.getSlaveClients().get(hbm0);
        Assertions.assertEquals(2, mc.getSysAddresses().size());
        mc.removeSysAddress(mc.getSysAddresses().get(0).getFirst());
        Assertions.assertEquals(1, mc.getSysAddresses().size());
    }
}
