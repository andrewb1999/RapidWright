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

import com.xilinx.rapidwright.device.Series;

/**
 * What a timing model promises about its numbers.
 *
 * <p>The timing package serves two different purposes and they want different
 * models. A router needs a delay for every candidate connection, including ones
 * that are not routed yet, and it needs it cheaply; it can tolerate error
 * because it only ranks connections against each other. Reporting slack or
 * programming clock delays needs numbers that agree with Vivado's signoff
 * analysis, which requires both process corners and the routed nodes, and is
 * only meaningful after routing.
 *
 * <p>Which fidelity a design gets depends on what has been characterized for
 * its device series, so this is a property of the model set, not of the
 * series itself; see {@link #forSeries(Series)} for what is available today.
 */
public enum TimingFidelity {
    /**
     * A single-corner estimate intended to guide routing. Interconnect delay is
     * a fitted function of wire class and distance, so the reported critical
     * path is an estimate and callers add pessimism to it before treating it as
     * a bound.
     */
    APPROXIMATE,

    /**
     * Two-corner analysis from characterized per-node, per-arc and per-clock-sink
     * data, intended to reproduce Vivado's report_timing on a routed design.
     * No pessimism is added because none is warranted.
     */
    SIGNOFF;

    /**
     * The fidelity the timing package provides for a device series.
     *
     * <p>UltraScale and UltraScale+ have the fitted interconnect model that
     * RWRoute was developed against. Versal has signoff-quality characterized
     * data instead, and no fitted model yet.
     */
    public static TimingFidelity forSeries(Series series) {
        if (series == Series.Versal) {
            return SIGNOFF;
        }
        return APPROXIMATE;
    }

    /**
     * Whether critical path estimates from this model should have a pessimism
     * margin applied before being presented as a timing closure bound.
     */
    public boolean needsPessimism() {
        return this == APPROXIMATE;
    }
}
