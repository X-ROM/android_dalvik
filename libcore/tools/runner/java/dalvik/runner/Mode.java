/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dalvik.runner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * A Mode for running tests. Examples including running in a virtual
 * machine either on the host or a device or within a specific context
 * such as within an Activity.
 */
abstract class Mode {

    private static final Pattern JAVA_TEST_PATTERN = Pattern.compile("\\/(\\w)+\\.java$");

    private static final Logger logger = Logger.getLogger(Mode.class.getName());

    protected final Environment environment;
    protected final long timeoutSeconds;
    protected final File sdkJar;

    protected final Set<File> testRunnerJava = new HashSet<File>();
    protected final Classpath testRunnerClasspath = new Classpath();

    protected final Classpath testClasspath = Classpath.of(
            // TODO: we should be able to work with a shipping SDK, not depend on out/...
            // dalvik/libcore for tests
            new File("out/target/common/obj/JAVA_LIBRARIES/core-tests_intermediates/classes.jar").getAbsoluteFile(),
            // framework/base for tests
            new File("out/target/common/obj/JAVA_LIBRARIES/core_intermediates/classes.jar").getAbsoluteFile());


    protected final ExecutorService outputReaders
            = Executors.newFixedThreadPool(1, Threads.daemonThreadFactory());

    Mode(Environment environment, long timeoutSeconds, File sdkJar) {
        this.environment = environment;
        this.timeoutSeconds = timeoutSeconds;
        this.sdkJar = sdkJar;
    }

    /**
     * Initializes the temporary directories and test harness necessary to run
     * tests.
     */
    protected void prepare(Set<File> testRunnerJava, Classpath testRunnerClasspath) {
        this.testRunnerJava.addAll(testRunnerJava);
        this.testRunnerClasspath.addAll(testRunnerClasspath);
        environment.prepare();
        compileTestRunner();
    }

    private void compileTestRunner() {
        logger.fine("build testrunner");

        File base = environment.testRunnerClassesDir();
        new Mkdir().mkdirs(base);
        new Javac()
                .bootClasspath(sdkJar)
                .classpath(testRunnerClasspath)
                .sourcepath(DalvikRunner.HOME_JAVA)
                .destination(base)
                .compile(testRunnerJava);
        postCompileTestRunner();
    }

    /**
     * Hook method called after TestRunner compilation.
     */
    abstract protected void postCompileTestRunner();

    /**
     * Compiles classes for the given test and makes them ready for execution.
     * If the test could not be compiled successfully, it will be updated with
     * the appropriate test result.
     */
    public void buildAndInstall(TestRun testRun) {
        logger.fine("build " + testRun.getQualifiedName());

        Classpath testClasses;
        try {
            testClasses = compileTest(testRun);
            if (testClasses == null) {
                testRun.setResult(Result.UNSUPPORTED, Collections.<String>emptyList());
                return;
            }
        } catch (CommandFailedException e) {
            testRun.setResult(Result.COMPILE_FAILED, e.getOutputLines());
            return;
        } catch (IOException e) {
            testRun.setResult(Result.ERROR, e);
            return;
        }
        testRun.setTestClasspath(testClasses);
        environment.prepareUserDir(testRun);
    }

    /**
     * Compiles the classes for the described test.
     *
     * @return the path to the compiled classes (directory or jar), or {@code
     *      null} if the test could not be compiled.
     * @throws CommandFailedException if javac fails
     */
    private Classpath compileTest(TestRun testRun) throws IOException {
        if (!JAVA_TEST_PATTERN.matcher(testRun.getTestJava().toString()).find()) {
            return null;
        }

        String qualifiedName = testRun.getQualifiedName();
        File testClassesDir = environment.testClassesDir(testRun);
        new Mkdir().mkdirs(testClassesDir);
        FileOutputStream propertiesOut = new FileOutputStream(
                new File(testClassesDir, TestProperties.FILE));
        Properties properties = new Properties();
        fillInProperties(properties, testRun);
        properties.store(propertiesOut, "generated by " + Mode.class.getName());
        propertiesOut.close();

        Classpath classpath = new Classpath();
        classpath.addAll(testClasspath);
        classpath.addAll(testRun.getRunnerClasspath());

        // compile the test case
        new Javac()
                .bootClasspath(sdkJar)
                .classpath(classpath)
                .sourcepath(testRun.getTestDirectory())
                .destination(testClassesDir)
                .compile(testRun.getTestJava());
        return postCompileTest(testRun);
    }

    /**
     * Hook method called after test compilation.
     *
     * @param testRun The test being compiled
     * @return the new result file.
     */
    abstract protected Classpath postCompileTest(TestRun testRun);


    /**
     * Fill in properties for running in this mode
     */
    protected void fillInProperties(Properties properties, TestRun testRun) {
        properties.setProperty(TestProperties.TEST_CLASS, testRun.getTestClass());
        properties.setProperty(TestProperties.QUALIFIED_NAME, testRun.getQualifiedName());
    }

    /**
     * Runs the test, and updates its test result.
     */
    void runTest(TestRun testRun) {
        if (!testRun.isRunnable()) {
            throw new IllegalArgumentException();
        }

        final List<Command> commands = buildCommands(testRun);

        List<String> output = null;
        for (final Command command : commands) {
            try {
                command.start();

                // run on a different thread to allow a timeout
                output = outputReaders.submit(new Callable<List<String>>() {
                        public List<String> call() throws Exception {
                            return command.gatherOutput();
                        }
                    }).get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                testRun.setResult(Result.EXEC_TIMEOUT,
                        Collections.singletonList("Exceeded timeout! (" + timeoutSeconds + "s)"));
                return;
            } catch (Exception e) {
                testRun.setResult(Result.ERROR, e);
                return;
            } finally {
                if (command.isStarted()) {
                    command.getProcess().destroy(); // to release the output reader
                }
            }
        }
        // we only look at the output of the last command
        if (output.isEmpty()) {
            testRun.setResult(Result.ERROR,
                    Collections.singletonList("No output returned!"));
            return;
        }

        Result result = TestProperties.RESULT_SUCCESS.equals(output.get(output.size() - 1))
                ? Result.SUCCESS
                : Result.EXEC_FAILED;
        testRun.setResult(result, output.subList(0, output.size() - 1));
    }

    /**
     * Returns commands for test execution.
     */
    protected abstract List<Command> buildCommands(TestRun testRun);

    /**
     * Deletes files and releases any resources required for the execution of
     * the given test.
     */
    void cleanup(TestRun testRun) {
        environment.cleanup(testRun);
    }

    /**
     * Cleans up after all test runs have completed.
     */
    void shutdown() {
        outputReaders.shutdown();
        environment.shutdown();
    }
}