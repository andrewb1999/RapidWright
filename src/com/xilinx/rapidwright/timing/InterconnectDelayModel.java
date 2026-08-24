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

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;

/**
 * Prices the routing between a net's source and one of its sinks.
 *
 * <p>This is the delay of the net alone — source site pin to sink site pin —
 * with nothing from inside either cell, so it composes with
 * {@link LogicDelayModel} without double counting.
 *
 * <p>Implementations differ in what they need to know: an
 * {@link TimingFidelity#APPROXIMATE} model estimates from placement and can
 * price a connection that has not been routed, which is what a router needs;
 * a {@link TimingFidelity#SIGNOFF} model prices the nodes actually used and
 * so requires the net to be routed.
 */
public interface InterconnectDelayModel {

    TimingFidelity getFidelity();

    /**
     * Delay from the net's source to the given sink at one corner.
     *
     * @return the delay in picoseconds, or null if this model cannot price
     *         the connection (for instance an unrouted net under a signoff
     *         model).
     */
    Float getNetDelayPs(Net net, SitePinInst sink, Corner corner);
}
