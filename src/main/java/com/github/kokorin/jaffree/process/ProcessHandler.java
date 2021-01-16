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

package com.github.kokorin.jaffree.process;

import com.github.kokorin.jaffree.util.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class ProcessHandler<T> {
    private final Path executable;
    private final String contextName;
    private StdReader<T> stdOutReader = new GobblingStdReader<>();
    private StdReader<T> stdErrReader = new GobblingStdReader<>();
    private List<Runnable> runnables = null;
    private Stopper stopper = null;
    private Supplier<List<String>> arguments = null;

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessHandler.class);

    public ProcessHandler(Path executable, String contextName) {
        this.executable = executable;
        this.contextName = contextName;
    }

    public synchronized ProcessHandler<T> setStdOutReader(StdReader<T> stdOutReader) {
        this.stdOutReader = stdOutReader;
        return this;
    }

    public synchronized ProcessHandler<T> setStdErrReader(StdReader<T> stdErrReader) {
        this.stdErrReader = stdErrReader;
        return this;
    }

    /**
     * Set extra {@link Runnable}s that must be executed in parallel with process
     *
     * @param runnables list
     * @return this
     */
    public synchronized ProcessHandler<T> setRunnables(List<Runnable> runnables) {
        this.runnables = runnables;
        return this;
    }

    public synchronized ProcessHandler<T> setStopper(Stopper stopper) {
        this.stopper = stopper;
        return this;
    }

    public synchronized ProcessHandler<T> setArguments(final List<String> arguments) {
        return setArguments(new Supplier<List<String>>() {
            @Override
            public List<String> supply() {
                return arguments;
            }
        });
    }

    public synchronized ProcessHandler<T> setArguments(Supplier<List<String>> arguments) {
        this.arguments = arguments;
        return this;
    }

    public synchronized T execute() {

        Process process = null;
        try {
            LOGGER.info("Starting process: {}", executable);

            Executor executor = new Executor(contextName);
            try {
                startAdditionalRunnables(executor);

                List<String> command = new ArrayList<>();
                command.add(executable.toString());
                command.addAll(arguments.supply());

                LOGGER.info("Command constructed:\n{}", joinArguments(command));

                process = new ProcessBuilder(command)
                        .start();
                if (stopper != null) {
                    stopper.setProcess(process);
                }

                return interactWithProcess(process, executor);
            } finally {
                executor.stop();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to start process.", e);
        } finally {
            if (process != null) {
                // TODO on Windows process sometimes doesn't stop and keeps running
                process.destroy();
                // Process must be destroyed before closing streams, can't use try-with-resources,
                // as resources are closing when leaving try block, before finally
                closeQuietly(process.getInputStream());
                closeQuietly(process.getOutputStream());
                closeQuietly(process.getErrorStream());
            }
        }
    }

    protected T interactWithProcess(Process process, Executor executor) {
        AtomicReference<T> resultRef = new AtomicReference<>();
        Integer status = null;
        Exception interrupted = null;

        try {
            startReadingOutput(process, executor, resultRef);

            LOGGER.info("Waiting for process to finish");
            status = process.waitFor();
            LOGGER.info("Process has finished with status: {}", status);

            waitForExecutorToStop(executor, 10_000);
        } catch (InterruptedException e) {
            LOGGER.warn("Process has been interrupted");
            interrupted = e;
        }

        Exception exception = null;
        if (executor != null) {
            exception = executor.getException();
        }
        if (exception != null) {
            throw new RuntimeException("Failed to execute, exception appeared in one of helper threads", exception);
        }

        if (interrupted != null) {
            throw new RuntimeException("Failed to execute, was interrupted", interrupted);
        }

        if (!Integer.valueOf(0).equals(status)) {
            throw new RuntimeException("Process execution has ended with non-zero status: " + status);
        }

        T result = resultRef.get();
        if (result == null) {
            throw new RuntimeException("Process execution has ended with null result");
        }

        return result;
    }

    protected void startAdditionalRunnables(final Executor executor) {
        if (runnables != null) {
            for (int i = 0; i < runnables.size(); i++) {
                Runnable runnable = runnables.get(i);
                executor.execute("Runnable-" + i, runnable);
            }
        }
    }

    protected void startReadingOutput(final Process process,
                                      final Executor executor,
                                      final AtomicReference<T> resultReference) {
        LOGGER.debug("Starting IO interaction with process");

        if (stdErrReader != null) {
            executor.execute("StdErr", new Runnable() {
                @Override
                public void run() {
                    T errResult = stdErrReader.read(process.getErrorStream());
                    if (errResult != null) {
                        boolean set = resultReference.compareAndSet(null, errResult);
                        if (!set) {
                            LOGGER.warn("Ignored result of reading STD ERR: {}", errResult);
                        }
                    }
                }
            });
        }

        if (stdOutReader != null) {
            executor.execute("StdOut", new Runnable() {
                @Override
                public void run() {
                    T result = stdOutReader.read(process.getInputStream());
                    if (result != null) {
                        boolean set = resultReference.compareAndSet(null, result);
                        if (!set) {
                            LOGGER.warn("Ignored result of reading STD OUT: {}", result);
                        }
                    }
                }
            });
        }
    }

    protected static String joinArguments(List<String> arguments) {
        StringBuilder result = new StringBuilder();
        boolean first = true;
        for (String argument : arguments) {
            if (!first) {
                result.append(" ");
            }
            String quote = argument.contains(" ") ? "\"" : "";
            result.append(quote).append(argument).append(quote);
            first = false;
        }

        return result.toString();
    }


    private static void closeQuietly(Closeable toClose) {
        try {
            if (toClose != null) {
                toClose.close();
            }
        } catch (Exception e) {
            LOGGER.warn("Ignoring exception: " + e.getMessage());
        }
    }

    private static void waitForExecutorToStop(Executor executor, long timeoutMillis) throws InterruptedException {
        LOGGER.debug("Waiting for Executor to stop");

        long waitStarted = System.currentTimeMillis();
        do {
            if (System.currentTimeMillis() - waitStarted > timeoutMillis) {
                LOGGER.warn("Executor hasn't stopped in {} millis, won't wait longer", timeoutMillis);
                break;
            }

            LOGGER.trace("Executor hasn't yet stopped, still running threads: {}", executor.getRunningThreadNames());
            Thread.sleep(100);
        } while (executor.isRunning());
    }
}
