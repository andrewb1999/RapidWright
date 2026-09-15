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

/**
 * The process corner a delay is quoted at.
 *
 * <p>Setup analysis is asymmetric: the launching clock and the data path are
 * taken at their slowest and the capturing clock at its fastest, so one delay
 * per element is not enough to reproduce a signoff slack. An
 * {@link TimingFidelity#APPROXIMATE} model has only one number and answers
 * both corners with it; a {@link TimingFidelity#SIGNOFF} model carries both.
 *
 * <p>These are the two corners of Vivado's default slow-process analysis. The
 * fast-process corners are not represented because timing closure on these
 * devices is checked at the slow process.
 */
public enum Corner {
    /**
     * Slow process, maximum delay: the launch clock and data path in setup
     * analysis, the capture clock in hold analysis.
     */
    SLOW_MAX,

    /**
     * Slow process, minimum delay: the capture clock in setup analysis, the
     * launch clock and data path in hold analysis.
     */
    SLOW_MIN;
}
