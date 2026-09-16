/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.edif;

import com.xilinx.rapidwright.util.FileTools;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class YosysTools {
    public static final String yosysExec = "yosys";
    /** Environment variable naming the yosys executable to use instead of {@code yosys} on the PATH. */
    public static final String YOSYS_ENV = "YOSYS";
    /** The slang SystemVerilog frontend plugin ({@code yosys -m slang}, {@code read_slang}); prebuilt in the OSS CAD Suite. */
    public static final String SLANG_PLUGIN = "slang";

    public static final String SYNTH_XILINX = "synth_xilinx";

    public static final String SYNTH_XILINX_FLAG_FAMILY_XCUP = " -family xcup";
    public static final String SYNTH_XILINX_FLAG_EDIF = " -edif ";
    public static final String SYNTH_XILINX_FLAG_FLATTEN = " -flatten";
    public static final String SYNTH_XILINX_FLAG_OUT_OF_CONTEXT = " -noclkbuf -noiopad";

    /**
     * Run the given command string in Yosys, on the files given.
     * @param command Yosys command(s), separated by ';'
     * @param workDir Working directory
     * @param paths Path objects of input files
     */
    public static void run(String command, Path workDir, Path... paths) {
        List<String> exec = new ArrayList<>();
        exec.add(FileTools.getExecutablePath(yosysExec));
        exec.add("-p");
        exec.add(command);
        for (Path path : paths) {
            exec.add(path.toString());
        }

        boolean verbose = true;
        String[] environ = null;
        Integer exitCode = FileTools.runCommand(exec.toArray(new String[0]), verbose, environ, workDir.toFile());
        if (exitCode != 0) {
            throw new RuntimeException("Yosys exited with code: " + exitCode);
        }
    }

    /**
     * Call Yosys' 'synth_xilinx' command with the given flags on the files given.
     * @param flags String with flags to be provided to 'synth_xilinx', in addition to
     *              '-edif <workDir>/output.edf'.
     * @param workDir Working directory
     * @param paths Path objects of input files
     * @return EDIFNetlist object of Yosys' result
     */
    public static EDIFNetlist synthXilinxWithWorkDir(String flags, Path workDir, Path... paths) {
        final Path edf = workDir.resolve("output.edf");
        String command = SYNTH_XILINX;
        command += flags;

        // Workaround an issue in Yosys' EDIF writer where it may incorrectly emit $scopeinfo cells
        // created during -flatten
        // BEGIN WORKAROUND
        command += "; delete t:$scopeinfo; ";
        command += SYNTH_XILINX;
        command += " -run edif:";
        // END WORKAROUND

        command += SYNTH_XILINX_FLAG_EDIF + edf;
        run(command, workDir, paths);
        return EDIFTools.readEdifFile(edf);
    }

    /**
     * Call Yosys' 'synth_xilinx' command with the default flags '-family xcvup -flatten
     * -edif <workDir>/output.edf' on the files given.
     * @param workDir Working directory
     * @param paths Path objects of input files
     * @return EDIFNetlist object of Yosys' result
     */
    public static EDIFNetlist synthXilinxWithWorkDir(Path workDir, Path... paths) {
        return synthXilinxWithWorkDir(SYNTH_XILINX_FLAG_FAMILY_XCUP + SYNTH_XILINX_FLAG_FLATTEN, workDir, paths);
    }

    /**
     * Call Yosys' 'synth_xilinx' command with the given flags on the files given.
     * @param flags String with flags to be provided to 'synth_xilinx', in addition to
     *              '-edif <workDir>/output.edf'.
     * @param paths Path objects of input files
     * @return EDIFNetlist object of Yosys' result
     */
    public static EDIFNetlist synthXilinx(String flags, Path... paths) {
        final Path workDir = FileSystems.getDefault()
                .getPath("yosysToolsWorkdir" + FileTools.getUniqueProcessAndHostID());
        workDir.toFile().mkdirs();

        EDIFNetlist netlist = synthXilinxWithWorkDir(flags, workDir, paths);

        FileTools.deleteFolder(workDir.toString());
        return netlist;
    }

    /**
     * Call Yosys' 'synth_xilinx' command with the default flags '-family xcvup -flatten
     * -edif <workDir>/output.edf' on the files given.
     * @param paths Path objects of input files
     * @return EDIFNetlist object of Yosys' result
     */
    public static EDIFNetlist synthXilinx(Path... paths) {
        final Path workDir = FileSystems.getDefault()
                .getPath("yosysToolsWorkdir" + FileTools.getUniqueProcessAndHostID());
        workDir.toFile().mkdirs();

        EDIFNetlist netlist = synthXilinxWithWorkDir(workDir, paths);

        FileTools.deleteFolder(workDir.toString());
        return netlist;
    }

    /**
     * Checks if yosys is available on current PATH (uses unix 'which' or windows 'where').
     * @return true if yosys is on current PATH, false otherwise.
     */
    public static boolean isYosysOnPath() {
        return FileTools.isExecutableOnPath(yosysExec);
    }

    /** The yosys executable: {@link #YOSYS_ENV} when set, else {@code yosys} resolved on the PATH. */
    public static String getYosysExecutable() {
        String env = System.getenv(YOSYS_ENV);
        if (env != null && !env.isEmpty()) return env;
        return FileTools.getExecutablePath(yosysExec);
    }

    /**
     * Runs a Yosys script with no input files on the command line (the script reads them itself,
     * e.g. with {@code read_slang}), optionally with plugins loaded first ({@code -m}) and a log file.
     * Yosys' console output is captured and included in the exception when it fails.
     * @param script Yosys commands, separated by ';'
     * @param workDir Working directory (relative paths in the script resolve against it)
     * @param logFile Log file for Yosys' own log ({@code -l}), or null
     * @param plugins Plugins to load before the script runs (e.g. {@link #SLANG_PLUGIN})
     */
    public static void runScript(String script, Path workDir, Path logFile, String... plugins) {
        List<String> exec = new ArrayList<>();
        exec.add(getYosysExecutable());
        exec.add("-q");
        for (String plugin : plugins) { exec.add("-m"); exec.add(plugin); }
        if (logFile != null) { exec.add("-l"); exec.add(logFile.toString()); }
        exec.add("-p");
        exec.add(script);
        try {
            Process p = new ProcessBuilder(exec).directory(workDir.toFile()).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try (java.io.InputStream in = p.getInputStream()) { for (int n; (n = in.read(buf)) > 0; ) bos.write(buf, 0, n); }
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Yosys exited with code " + exitCode + (logFile != null ? " (log " + logFile + ")" : "") + "\n"
                        + new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException | InterruptedException e) {
            throw new RuntimeException("Could not run yosys (" + exec.get(0) + "; set " + YOSYS_ENV + " to the executable)", e);
        }
    }

    /**
     * The {@code read_slang} command that elaborates SystemVerilog sources with the slang frontend
     * (load it with {@link #SLANG_PLUGIN}): {@code read_slang <sources> --top <top> -G<param>=<value>...}.
     * Compose it with the rest of a script for {@link #runScript}, e.g.
     * {@code readSlangCommand(files, "top", params) + "; hierarchy -top top; clean; write_json out.json"}.
     * @param sources SystemVerilog files, in read order
     * @param top top module name
     * @param parameters parameter overrides for the top module, or null
     */
    public static String readSlangCommand(List<Path> sources, String top, java.util.Map<String, String> parameters) {
        StringBuilder cmd = new StringBuilder("read_slang");
        for (Path p : sources) cmd.append(' ').append(p);
        cmd.append(" --top ").append(top);
        if (parameters != null) for (java.util.Map.Entry<String, String> e : parameters.entrySet()) cmd.append(" -G").append(e.getKey()).append('=').append(e.getValue());
        return cmd.toString();
    }
}
