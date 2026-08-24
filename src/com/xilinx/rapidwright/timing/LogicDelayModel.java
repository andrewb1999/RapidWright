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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.device.SiteTypeEnum;

/**
 * Prices what happens inside a placed cell: propagation between two of its
 * pins, and the setup and hold requirements that bound a path at a register.
 *
 * <p>Pins are named by the BEL pin the cell pin is placed on, not by the
 * logical pin. The distinction matters for LUTs, whose input delays depend on
 * which physical input a logical pin landed on; a model keyed by logical pin
 * would have to average over placements.
 *
 * <p>Every method returns null for an arc the model does not know, rather
 * than a guess, so a caller can tell coverage gaps from small delays.
 */
public interface LogicDelayModel {

    TimingFidelity getFidelity();

    /**
     * Propagation delay from one BEL pin of a placed cell to another, at one
     * corner. For a register this is clock-to-output.
     *
     * @return the delay in picoseconds, or null if the arc is unknown.
     */
    Float getPropagationDelayPs(Cell cell, String fromBelPin, String toBelPin, Corner corner);

    /**
     * Setup requirement of a data pin relative to the cell's clock pin.
     *
     * @return the requirement in picoseconds, or null if unknown.
     */
    Float getSetupPs(Cell cell, String clockBelPin, String dataBelPin, Corner corner);

    /**
     * Hold requirement of a data pin relative to the cell's clock pin.
     *
     * @return the requirement in picoseconds, or null if unknown.
     */
    Float getHoldPs(Cell cell, String clockBelPin, String dataBelPin, Corner corner);

    /**
     * Delay of a connection routed entirely inside a site, from one BEL pin
     * to another ({@code A6LUT/O6} to {@code AFF2/D}). Such a connection never
     * reaches the interconnect, so it is priced here rather than by the
     * interconnect model, but Vivado still charges it as net delay.
     *
     * @return the delay in picoseconds, or null if unknown.
     */
    Float getIntraSiteDelayPs(SiteTypeEnum siteType, String fromBelPin, String toBelPin, Corner corner);
}
