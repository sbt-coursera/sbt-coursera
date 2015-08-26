# Sbt Plugin for Automatic Grading of Assignments on Coursera

[![Join the chat at https://gitter.im/sbt-coursera/sbt-coursera](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/sbt-coursera/sbt-coursera?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge) [![Build Status](https://travis-ci.org/sbt-coursera/sbt-coursera.png?branch=master)](https://travis-ci.org/sbt-coursera/sbt-coursera)

This is an SBT plugin for creating Scala and Java projects that can be automatically graded on [Coursera](https://www.coursera.org/). Starting with version 0.6, this plugin has been upgraded to run within Coursera's new on-demand platform's programming assignment infrastructure based upon Docker.

### Usage

To use the `sbt-coursera` plugin you need to do two things: 
  * Add the `plugins.sbt` file with the following contents to the `<base-dir>/project` folder. The file should contain the following:
    
        addSbtPlugin("ch.epfl.lamp" % "sbt-coursera" % "0.6")

  * Add your custom build file to the `<base-dir>/project` by filling in the [template file](TODO).

### ProjectDetailsMap

The build can be reused for different project handouts, it abstracts over all project specific settings. This is done in the `projectDetailsMap` defined in the main `build.sbt` file.

The elements in the `projectDetailsMap` are mostly self-explanatory. The `packageName` field
is used in two places during the build process

  - To select the source files that are handed out to students, see the `handoutFiles` in your scala build file that extends.
  - To select which tests are compiled and executed when testing a submission, see `selectTestsForProject` in `ProgFunBuild.scala` and `runScalaTest` in `ScalaTestRunner.scala`

### Main Project

The main project (named `assignment`) is used by the students to compile, run and test their code. It's also used by the TAs to write assignments, write private and public test.

It is a standard SBT project, main soruces in `src/main/scala`, tests `src/test/scala`. Use tasks such as `compile`, `test` or `eclipse` as usual. The project additionally defines the `submit` task which allows the students to upload their solution to the coursera servers.

Finally, the project defines a `createHandout` task which will create a `.zip` file with all the files that we hand out to the students. The content of this `.zip` file is defined in the  `handoutFiles` setting of the main `build.sbt` file. To prevent unwanted jars in the `.zip` file one needs to do a `clean`, `set currentProject := "<project-name>"`, `eclipse, and then run the `createHandout` command.

### Grading Project

We define a second sbt project (named `submission` with project root is directory `submission`) that is used to grade an assignment that has been submitted to coursera. First of all,  it decodes the student's source code from the json file (downloaded by the `gradingImpl` script) and unpacks it in the directory `submission/src/main/scala`. Note that the test sources are picked up from the main project, i.e. `src/test/scala` (which contains all private tests if we are in the `solutions` branch).

In order to grade an assignment, one has to run the `submission/grade` task. But before, one has to define the project that's being graded, so that the correct tests are executed. This has to be done using the `set` command:

 > `set (partIdOfGradingProject in submissionProject) := "idString"`

where `partIdOfGradingProject` is one of the assignment part ids used in the `projectDetailsMap` of the main `build.sbt` file. Defining the `partIdOfGradingProject` setting can also be done when running from the command line:

 > ``sbt 'set (partIdOfGradingProject in submissionProject) := "idString"' submission/grade``

The `submission/grade` task will then unpack the source, compile it, compile the tests, run the tests, run the stylechecker, and output the feedback and grade to stderr.

### Settings.offlineMode

In `Settings.scala` there's a boolean `offlineMode` which is useful when coding on the build definition. If it is set to `true`, the build will

- not decode the json file and extract the source code
- not clean the sources in the `submission` project


### Creating handouts

In `sbt` in the master branch, do the following (replace NAME_OF_THE_PROJECT with the actual project name):

    > createHandout NAME_OF_THE_PROJECT

Grab the generated zip file from the `target` folder.        

### Working on one specific assignment

When working on one specific assignment, you'd like to avoid the sources and tests of other assignments from being compiled / tested. This can be achieved using the setting `currentProject` in the main `build.sbt` file.

If `currentProject == ""`, then all sources are compiled and all tests are executed.

### Test Weights and Sandboxing

A public test suite (for students) should extend `org.scalatest.FunSuite` and use the "test" method defined there. In order to add test weights and test execution sandboxing, the private test suites mix in the trait `grading.GradingSuite` which is defined in the `solutions` branch. This triat overrides the "test" method to sandbox the executed code and adds an additional `weight` argument allowing to define the grade weight of each test.

The entire source code for all the assignments is kept in this repository. The sources for each assignment have to be in a separate package (`project1`, ...).

The `createHandout` task (available in `master`) will only include the tests and sources of the specific project for which a handout archive is generated. Similarly, the `submission/grade` task will only compile and execute the tests of the project being tested.

**NOTE**: after adding the `grading.GradingSuite` trait to a test suite (as in the `solutions` branch), it can no longer be executed in eclipse using the eclipse JUint runner.

### Running weighted tests and Scalastyle

When working on an assignment, you can use the `sbt` task `styleCheck` to run Scalastyle on the source files.

To run the test suite with test weights (only works in branch `solutions`) while working on an assignment, you can use the `scalaTest` task. In order to run the tests without computing a score, use the ordinary `test` task (in both branches).

**NOTE**: all of these tasks tasks take the `currentProject` setting into account, see above.

### Building docker container images for grading

To build a docker container image to upload to Coursera to use for grading, be sure to have the following prerequisites installed:

 - [`docker`](https://docker.com) - A popular container system for Linux.
 - [`courseraprogramming`](https://github.com/coursera/courseraprogramming) - A toolkit to help instructional teams build grading containers

To dockerize an sbt project, copy the sample docker file in `docker/Dockerfile.sample` to `Dockerfile` at the root of your project. Also copy `docker/grade.sh.template` to the root of your project. Then run: `docker build -t <your-project-name> .` to build and tag your container image (e.g. `docker build -t reactive .` to tag it as `reactive`).

You can run `courseraprogramming inspect <your-project-name>` to launch an interactive terminal within the container image to poke at the file system, or run `courseraprogramming grade local <your-project-name> path/to/submission/folder` to run the grader on a sample submission. The submission folder should have a single jar inside named `submission.jar` containing the sources to grade. (You can generate this JAR by running `packageSubmission` in an student sbt project. Look for the resulting `jar` inside `target/scala-2.11` or display it by executing `show packageSubmission` in the `sbt` interactive console.)

To upload the grader to coursera, execute: `courseraprogramming upload <your-project-name> <coursera-course-id> <programming-assignment-id> <part-id-of-grader>`.

#### Security guarantees

Note: sbt and the test runner must carefully protect the stderr output such that only grade information is output to stderr. Otherwise grading failures will result. The current test runner runs the submission code in a separate JVM and capture stdout and stderr appropriately. `scalac` and `sbt` only output to stdout through `sbt`'s streams interface, and thus leave stderr clean. Beware of an assignment that allows users to define macros, however, as that would give user submitted code control of execution within the primary JVM, allowing them to potentially manipulate stderr, and thus their grades.

---

# BUILD DEFINITION

Students will never have to touch any build files.

The main `build.sbt` file defines settings for all projects.
