/*
 *    Copyright  2017 Denis Kokorin
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package com.github.kokorin.jaffree;

import com.github.kokorin.jaffree.result.FFmpegResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FFmpeg extends Executable<FFmpegResult> {
    private final List<Input> inputs = new ArrayList<>();
    private final List<Output> outputs = new ArrayList<>();
    private final List<Option> additionalOptions = new ArrayList<>();
    private boolean overwriteOutput;
    private ProgressListener progressListener;
    //-progress url (global)
    //-filter_threads nb_threads (global)
    //-debug_ts (global)
    private FilterGraph complexFilter;

    private static final Logger LOGGER = LoggerFactory.getLogger(FFmpeg.class);

    public FFmpeg(Path executablePath) {
        super(executablePath, true);
    }

    public FFmpeg addInput(Input input) {
        inputs.add(input);
        return this;
    }

    public FFmpeg addOption(Option option) {
        additionalOptions.add(option);
        return this;
    }

    public FFmpeg setComplexFilter(FilterGraph graph) {
        this.complexFilter = graph;
        return this;
    }

    public FFmpeg addOutput(Output output) {
        outputs.add(output);
        return this;
    }


    /**
     * Whether to overwrite or to stop. False by default.
     * @param overwriteOutput true if forcibly overwrite, false if to stop
     * @return this
     */
    public FFmpeg setOverwriteOutput(boolean overwriteOutput) {
        this.overwriteOutput = overwriteOutput;
        return this;
    }

    public FFmpeg setProgressListener(ProgressListener progressListener) {
        this.progressListener = progressListener;
        return this;
    }

    @Override
    protected List<Option> buildOptions() {
        List<Option> result = new ArrayList<>();

        if (inputs != null) {
            for (Input input : inputs) {
                List<Option> inputOptions = input.buildOptions();
                if (inputOptions != null) {
                    result.addAll(inputOptions);
                }
            }
        }

        if (overwriteOutput) {
            //Overwrite output files without asking.
            result.add(new Option("-y"));
        } else {
            // Do not overwrite output files, and exit immediately if a specified output file already exists.
            result.add(new Option("-n"));
        }

        if (complexFilter != null) {
            result.add(new Option("-filter_complex", complexFilter.getValue()));
        }

        if (additionalOptions != null) {
            result.addAll(additionalOptions);
        }

        if (outputs != null) {
            for (Output output : outputs) {
                List<Option> outputOptions = output.buildOptions();
                if (outputOptions != null) {
                    result.addAll(outputOptions);
                }
            }
        }


        return result;
    }

    @Override
    protected FFmpegResult parseStdOut(InputStream stdOut) {
        //just read stdOut fully
        BufferedReader reader = new BufferedReader(new InputStreamReader(stdOut));
        String line;
        FFmpegResult result = null;

        try {
            while ((line = reader.readLine()) != null) {
                LOGGER.info(line);
                if (progressListener != null) {
                    FFmpegProgress progress = FFmpegProgress.fromString(line);
                    if (progress != null) {
                        progressListener.onProgress(progress);
                        continue;
                    }
                }

                FFmpegResult possibleResult = FFmpegResult.fromString(line);

                if (possibleResult != null) {
                    result = possibleResult;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    @Override
    protected void parseStdErr(InputStream stdErr) throws Exception {
        throw new RuntimeException("Error output stream should be redirected to standard output");
    }

    public static FFmpeg atPath(Path pathToDir) {
        String os = System.getProperty("os.name");
        if (os == null) {
            throw new RuntimeException("Failed to detect OS");
        }

        Path executable;
        if (os.toLowerCase().contains("win")) {
            executable = pathToDir.resolve("ffmpeg.exe");
        } else {
            executable = pathToDir.resolve("ffmpeg");
        }

        return new FFmpeg(executable);
    }
}
