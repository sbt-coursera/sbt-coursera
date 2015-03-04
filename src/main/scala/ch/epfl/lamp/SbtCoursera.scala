package ch.epfl.lamp

import sbt._
import Keys._

import scalaz.{ Success, Failure }
import scalaz.syntax.validation._

import com.typesafe.sbteclipse.plugin.EclipsePlugin
import EclipsePlugin.EclipseKeys

object SbtCourseraPlugin extends AutoPlugin {

  /**
   * *********************************************************
   * SETTINGS AND TASKS
   */

  object autoImport {
    /** The 'submit' task uses this project name (defined in the build.sbt file) to know where to submit the solution */
    val submitProjectName = SettingKey[String]("submitProjectName")

    /** Project-specific settings, see main build.sbt */
    val projectDetailsMap = SettingKey[Map[String, ProjectDetails]]("projectDetailsMap")

    /**
     * The files that are handed out to students. Accepts a string denoting the project name for
     * which a handout will be generated.
     */
    val handoutFiles = TaskKey[String => PathFinder]("handoutFiles")

    /**
     * Displays the list of all projects.
     */
    val allProjects = taskKey[Unit]("allProjects")

    lazy val allProjectsSetting = allProjects := {
      println(projectDetailsMap.value.keys.mkString("\n"))
    }

    /**
     * This setting allows to restrict the source files that are compiled and tested
     * to one specific project. It should be either the empty string, in which case all
     * projects are included, or one of the project names from the projectDetailsMap.
     */
    val currentProject = SettingKey[String]("currentProject")

    /** Package names of source packages common for all projects, see comment in build.sbt */
    val commonSourcePackages = SettingKey[Seq[String]]("commonSourcePackages")

    /** Package names of test sources for grading, see comment in build.sbt */
    val gradingTestPackages = SettingKey[Seq[String]]("gradingTestPackages")

    /** Jars for Java agents to be used when running ScalaTest. */
    val scalaTestJavaAgents = TaskKey[Seq[File]]("scalaTestJavaAgents")

    /** Additional Java system properties to be used when running ScalaTest. */
    val scalaTestJavaSysProps = TaskKey[Seq[(String, String)]]("scalaTestJavaSysProps")

    /**
     * **********************************************************
     * SUBMITTING A SOLUTION TO COURSERA
     */

    val packageSubmission = TaskKey[File]("packageSubmission")

    /** Task to submit a solution to coursera */
    val submit = InputKey[Unit]("submit")

    /**
     * *********************************************************
     * CREATE THE HANDOUT ZIP FILE
     */

    val createHandout = inputKey[File]("createHandout")

    // depends on "compile in Test" to make sure everything compiles. also makes sure that
    lazy val createHandoutSetting = createHandout := {
      val args = Def.spaceDelimited("<arg>").parsed
      val _ = (compile in Test).value
      if (currentProject.value != "" && currentProject.value != submitProjectName.value)
        sys.error("\nthe 'currentProject' setting in build.sbt needs to be \"\" or equal to submitProjectName in order to create a handout")
      else args match {
        case handoutProjectName :: Nil =>
          if (handoutProjectName != submitProjectName.value)
            sys.error("\nThe `submitProjectName` setting in `build.sbt` must match the project name for which a handout is generated\n ")
          val filesFinder = handoutFiles.value
          val files = filesFinder(handoutProjectName).get
          val basedir = baseDirectory.value
          def withRelativeNames(fs: Seq[File]) = fs.pair(relativeTo(basedir)) map {
            case (file, name) => (file, handoutProjectName + "/" + name)
          }
          val preprocessedFiles = withRelativeNames(files).map {
            case (file, name) =>
              val contents = if (name.endsWith(".scala")) {
                val source = IO.read(file)
                val processedSource = source.replaceAll("""(?s)//\-\-\-.*?/*\+\+\+[\t\x0B\f\r]*[\n]?(.*?)\+\+\+\*/[\t\x0B\f\r]*[\n]?""", "$1")
                processedSource.getBytes
              } else {
                IO.readBytes(file)
              }

              val outputFile = new File(target.value, name)
              IO.write(outputFile, contents)
              (outputFile, name)
          }
          val manualDepsWithRelativeNames = withRelativeNames(IO.listFiles(basedir / "lib"))
          val targetZip = target.value / (handoutProjectName + ".zip")
          IO.zip(preprocessedFiles ++ manualDepsWithRelativeNames, targetZip)
          targetZip
        case _ =>
          val detailsMap = projectDetailsMap.value
          val msg = s"""
            |
            |Failed to create handout. Syntax: `createHandout <projectName>`
            |
            |Valid project names are: ${detailsMap.keys.mkString(", ")}
            |
            | """.stripMargin
          sys.error(msg)
      }
    }

    /**
     * **********************************************************
     * RUNNING SCALATEST
     */

    val scalaTestSubmission = TaskKey[Unit]("scalaTestSubmission")
    val scalaTestSubmissionSetting = scalaTestSubmission := {
      val classpath = (fullClasspath in Test).value
      val resources = (copyResources in Compile).value
      val testClasses = (classDirectory in Test).value
      val agents = (scalaTestJavaAgents in Test).value
      val javaSysProps = (scalaTestJavaSysProps in Test).value
      val basedir = baseDirectory.value

      // this is only executed if all dependencies succeed.
      // no need to check `GradingFeedback.isFailed`
      val outfile = basedir / Settings.testResultsFileName
      val policyFile = basedir / ".." / Settings.policyFileName
      ScalaTestRunner.scalaTestGrade(classpath, testClasses, outfile,
        policyFile, copiedResourceFiles(resources), agents.toList, javaSysProps)
    }

    /**
     * **********************************************************
     * STYLE CHECKING
     */

    val styleCheckSubmission = TaskKey[Unit]("styleCheckSubmission")

    /**
     * - depend on scalaTestSubmission so that test get executed before style checking. the transitive
     *   dependencies also ensures that the "sources in Compile" don't have compilation errors
     * - using `map` makes this task execute only if all its dependencies succeeded.
     */
    val styleCheckSubmissionSetting = styleCheckSubmission <<= (sources in Compile, scalaTestSubmission) map { (sourceFiles, _) =>
      val (feedback, score) = StyleChecker.assess(sourceFiles)
      if (score == StyleChecker.maxResult) {
        GradingFeedback.perfectStyle()
      } else {
        val gradeScore = GradingFeedback.maxStyleScore * score / StyleChecker.maxResult
        GradingFeedback.styleProblems(feedback, gradeScore)
      }
    }

    /**
     * **********************************************************
     * PROJECT DEFINITION FOR INSTRUMENTATION AGENT
     */

    // lazy val instragentProject = Project(id = "instragent", base = file("instragent")) settings (
    // EclipseKeys.skipProject := true
    // )
    case class ProjectDetails(packageName: String,
      assignmentPartId: String,
      maxScore: Double,
      styleScoreRatio: Double,
      courseId: String = "",
      dependencies: Seq[ModuleID] = Seq())

    /**
     * Only include the test files which are defined in the package of the current project.
     * Also keeps test sources in packages listed in 'gradingTestPackages'.
     */
    val selectTestSources = {
      (unmanagedSources in Test) <<= (unmanagedSources in Test, scalaSource in Test, projectDetailsMap, currentProject, gradingTestPackages) map { (sources, srcTestScalaDir, detailsMap, projectName, gradingSrcs) =>
        projectFiles(sources, srcTestScalaDir, projectName, gradingSrcs, detailsMap)
      }
    }

    /**
     * **********************************************************
     * PARAMETERS FOR RUNNING THE TESTS
     *
     * Setting some system properties that are parameters for the GradingSuite test
     * suite mixin. This is for running the `test` task in SBT's JVM. When running
     * the `scalaTest` task, the ScalaTestRunner creates a new JVM and passes the
     * same properties.
     */

    val setTestProperties = TaskKey[Unit]("setTestProperties")
    val setTestPropertiesSetting = setTestProperties := {
      import scala.util.Properties._
      import Settings._
      setProp(scalaTestIndividualTestTimeoutProperty, individualTestTimeout.toString)
      setProp(scalaTestDefaultWeigthProperty, scalaTestDefaultWeigth.toString)
    }

    val setTestPropertiesHook = (test in Test) <<= (test in Test).dependsOn(setTestProperties)

    /**
     * **********************************************************
     * RUNNING WEIGHTED SCALATEST & STYLE CHECKER ON DEVELOPMENT SOURCES
     */

    def copiedResourceFiles(copied: collection.Seq[(java.io.File, java.io.File)]): List[File] = {
      copied.collect {
        case (from, to) if to.isFile => to
      }.toList
    }

    val scalaTest = TaskKey[Unit]("scalaTest")
    val scalaTestSetting = scalaTest := {
      val classpath = (fullClasspath in Test).value
      val resources = (copyResources in Compile).value
      val testClasses = (classDirectory in Test).value
      val agents = (scalaTestJavaAgents in Test).value
      val javaSysProps = (scalaTestJavaSysProps in Test).value
      val basedir = baseDirectory.value
      val s = streams.value

      // this is only executed if all dependencies succeed.
      // no need to check `GradingFeedback.isFailed`
      val logger = s.log
      val outfile = basedir / Settings.testResultsFileName
      val policyFile = basedir / Settings.policyFileName
      val (score, maxScore, feedback, runLog) =
        ScalaTestRunner.runScalaTest(classpath, testClasses, outfile,
          policyFile, copiedResourceFiles(resources), agents.toList,
          javaSysProps, logger.error(_))
      logger.info(feedback)
      logger.info("Test Score: " + score + " out of " + maxScore)
      if (!runLog.isEmpty) {
        logger.info("Console output of ScalaTest process")
        logger.info(runLog)
      }
    }

    val styleCheck = TaskKey[Unit]("styleCheck")

    /**
     * depend on compile to make sure the sources pass the compiler
     */
    val styleCheckSetting = styleCheck <<= (compile in Compile, sources in Compile, streams) map { (_, sourceFiles, s) =>
      val logger = s.log
      val (feedback, score) = StyleChecker.assess(sourceFiles)
      logger.info(feedback)
      logger.info("Style Score: " + score + " out of " + StyleChecker.maxResult)
    }

    /**
     * **********************************************************
     * PROJECT DEFINITION FOR GRADING
     */

    /**
     * The assignment uuid that is used for uniquely connecting feedback to the logs.
     */
    val gradingUUID = SettingKey[String]("gradingUUID")

    /**
     * The assignment part id of the project to be graded. Don't hard code this setting in .sbt or .scala, this
     * setting should remain a (command-line) parameter of the `submission/grade` task, defined when invoking sbt.
     * See also feedback string in "val gradeProjectDetailsSetting".
     */
    val partIdOfGradingProject = SettingKey[String]("partIdOfGradingProject")

    /**
     * she assignment part id of the project to be graded. Don't hard code this setting in .sbt or .scala, this
     * setting should remain a (command-line) parameter of the `submission/grade` task, defined when invoking sbt.
     * See also feedback string in "val gradeProjectDetailsSetting".
     */
    val gradingCourseId = SettingKey[String]("gradingCourseId")

    /**
     * The api key to access non-public api parts on coursera. This key is secret! It's defined in
     * 'submission/settings.sbt', which is not part of the handout.
     *
     * Default value 'apiKey' to make the handout sbt project work
     *  - In the handout, apiKey needs to be defined, otherwise the build doesn't compile
     *  - When correcting, we define 'apiKey' in the 'submission/sectrets.sbt' file
     *  - The value in the .sbt file will take precedence when correcting (settings in .sbt take
     *    precedence over those in .scala)
     */
    val apiKey = SettingKey[String]("apiKey")

    /**
     * **********************************************************
     * GRADING INITIALIZATION
     */

    val initGrading = TaskKey[Unit]("initGrading")
    lazy val initGradingSetting = initGrading <<= (clean, baseDirectory, sourceDirectory) map { (_, baseDir, submissionSrcDir) =>
      deleteFiles(submissionSrcDir, baseDir)
      GradingFeedback.initialize()
      RecordingLogger.clear()
    }

    def deleteFiles(submissionSrcDir: File, baseDir: File) {
      // don't delete anything in offline mode, useful for us when hacking testing / stylechecking
      if (!Settings.offlineMode) {
        IO.delete(submissionSrcDir)
        IO.delete(baseDir / Settings.submissionJarFileName)
        IO.delete(baseDir / Settings.testResultsFileName)
      }
    }

    /**
     * **********************************************************
     * DOWNLOADING AND EXTRACTING SUBMISSION
     */

    val getSubmission = TaskKey[Unit]("getSubmission")
    val getSubmissionSetting = getSubmission <<= (initGrading, baseDirectory, scalaSource in Compile) map { (_, baseDir, scalaSrcDir) =>
      readAndUnpackSubmission(baseDir, scalaSrcDir)
    }

    def readAndUnpackSubmission(baseDir: File, targetSourceDir: File) {
      try {
        val jsonFile = baseDir / Settings.submissionJsonFileName
        val targetJar = baseDir / Settings.submissionJarFileName
        val res = for {
          queueResult <- {
            if (Settings.offlineMode) {
              println("[not unpacking from json file]")
              QueueResult("").successNel
            } else {
              CourseraHttp.readJsonFile(jsonFile, targetJar)
            }
          }
          _ <- {
            GradingFeedback.apiState = queueResult.apiState
            CourseraHttp.unpackJar(targetJar, targetSourceDir)
          }
        } yield ()

        res match {
          case Failure(msgs) =>
            GradingFeedback.downloadUnpackFailed(msgs.list.mkString("\n"))
          case _ =>
            ()
        }
      } catch {
        case e: Throwable =>
          // generate some useful feedback in case something fails
          GradingFeedback.downloadUnpackFailed(CourseraHttp.fullExceptionString(e))
          throw e
      }
      if (GradingFeedback.isFailed) failDownloadUnpack()
    }

    // dependsOn makes sure that `getSubmission` is executed *before* `unmanagedSources`
    val getSubmissionHook = (unmanagedSources in Compile) <<= (unmanagedSources in Compile).dependsOn(getSubmission)

    def failDownloadUnpack(): Nothing = {
      sys.error("Download or Unpack failed")
    }

    /**
     * **********************************************************
     * READING COMPILATION AND TEST COMPILATION LOGS
     */

    // extraLoggers need to be defined globally. (extraLoggers in Compile) does not work - sbt only
    // looks at the global extraLoggers when creating the LogManager.
    val submissionLoggerSetting = extraLoggers ~= { currentFunction =>
      (key: ScopedKey[_]) => {
        new FullLogger(RecordingLogger) +: currentFunction(key)
      }
    }

    val readCompileLog = (compile in Compile) <<= (compile in Compile).result map handleFailure(compileFailed)
    val readTestCompileLog = (compile in Test) <<= (compile in Test).result map handleFailure(compileTestFailed)

    def handleFailure[R](handler: (Incomplete, String) => Unit) = (res: Result[R]) => res match {
      case Inc(inc) =>
        // Only call the handler of the task that actually failed. See comment in GradingFeedback.failed
        if (!GradingFeedback.isFailed)
          handler(inc, RecordingLogger.readAndClear())
        throw inc
      case Value(v) => v
    }

    def compileFailed(inc: Incomplete, log: String) {
      GradingFeedback.compileFailed(log)
    }

    def compileTestFailed(inc: Incomplete, log: String) {
      GradingFeedback.testCompileFailed(log)
    }

    lazy val packageSubmissionFiles = {
      // the packageSrc task uses Defaults.packageSrcMappings, which is defined as concatMappings(resourceMappings, sourceMappings)
      // in the packageSubmisson task we only use the sources, not the resources.
      inConfig(Compile)(Defaults.packageTaskSettings(packageSubmission, Defaults.sourceMappings))
    }

    lazy val submitSetting = submit := {
      val args = Def.spaceDelimited("<arg>").parsed
      val _ = (compile in Compile).value
      val s = streams.value
      if (currentProject.value != "") {
        val msg =
          """The 'currentProject' setting is not empty: '%s'
          |
          |This error only appears if there are mistakes in the build scripts. Please re-download the assignment
          |from the coursera webiste. Make sure that you did not perform any changes to the build files in the
          |`project/` directory. If this error persits, ask for help on the course forums.""".format(currentProject.value).stripMargin + "\n "
        s.log.error(msg)
        failSubmit()
      } else {
        val projectName = submitProjectName.value

        lazy val wrongNameMsg =
          """Unknown project name: %s
          |
          |This error only appears if there are mistakes in the build scripts. Please re-download the assignment
          |from the coursera webiste. Make sure that you did not perform any changes to the build files in the
          |`project/` directory. If this error persits, ask for help on the course forums.""".format(projectName).stripMargin + "\n "
        // log strips empty lines at the ond of `msg`. to have one we add "\n "
        val detailsMap = projectDetailsMap.value
        val details = detailsMap.getOrElse(projectName, { s.log.error(wrongNameMsg); failSubmit() })
        args match {
          case email :: otPassword :: Nil =>
            val sourcesJar = (packageSubmission in Compile).value
            submitSources(sourcesJar, details, email, otPassword, s.log)
          case _ =>
            val msg =
              """No e-mail address and / or submission password provided. The required syntax for `submit` is
              |  submit <e-mail> <submissionPassword>
              |
              |The submission password, which is NOT YOUR LOGIN PASSWORD, can be obtained from the assignment page
              |  https:/%s/assignment/index""".format(details.courseId).stripMargin + "\n"
            s.log.error(msg)
            failSubmit()
        }
      }
    }

    def submitSources(sourcesJar: File, submitProject: ProjectDetails, email: String, otPassword: String, logger: Logger) {
      import CourseraHttp._
      logger.info("Connecting to coursera. Obtaining challenge...")
      val res = for {
        challenge <- getChallenge(email, submitProject)
        chResponse <- {
          logger.info("Computing challenge response...")
          challengeResponse(challenge, otPassword).successNel[String]
        }
        response <- {
          logger.info("Submitting solution...")
          submitSolution(sourcesJar, submitProject, challenge, chResponse)
        }
      } yield response

      res match {
        case Failure(msgs) =>
          for (msg <- msgs.list) logger.error(msg)
          logger.warn("""NOTE:
          |   - Make sure that you have the freshly downloaded assignment from the
          |     correct course.
          |   - Make sure that your email is correct and that you have copied the
          |     password correctly from the assignments page.""".stripMargin)
          failSubmit()
        case Success(response) =>
          logger.success("""
          | Your code was successfully submitted: %s
          | NOTE:
          |   - The final grade is calculated based on the score you get for this assignment
          |     and the number of days that this submission is after the soft deadline. If your
          |     final score does not match please make sure to check the exact deadlines.
          |   - For each assignment there is a limit on the maximum number of attempts you can make.
          """.format(response).stripMargin)
      }
    }

    def failSubmit(): Nothing = {
      sys.error("Submission failed")
    }

    /**
     * Only include source files of 'currentProject', helpful when preparing a specific assignment.
     * Also keeps the source packages in 'commonSourcePackages'.
     */
    lazy val selectMainSources = {
      (unmanagedSources in Compile) <<= (unmanagedSources in Compile, scalaSource in Compile, projectDetailsMap, currentProject, commonSourcePackages) map { (sources, srcMainScalaDir, detailsMap, projectName, commonSrcs) =>
        projectFiles(sources, srcMainScalaDir, projectName, commonSrcs, detailsMap)
      }
    }

  }
  import autoImport._

  /**
   * **********************************************************
   * LIMITING SOURCES TO CURRENT PROJECT
   */

  def filter(basedir: File, packages: Seq[String]) = new FileFilter {
    def accept(file: File) = {
      basedir.equals(file) || {
        IO.relativize(basedir, file) match {
          case Some(str) =>
            packages exists { pkg =>
              str.startsWith(pkg)
            }
          case _ =>
            sys.error("unexpected test file: " + file + "\nbase dir: " + basedir)
        }
      }
    }
  }

  def projectFiles(allFiles: Seq[File], basedir: File, projectName: String, globalPackages: Seq[String], detailsMap: Map[String, ProjectDetails]) = {
    if (projectName == "") allFiles
    else detailsMap.get(projectName) match {
      case Some(project) =>
        val finder = allFiles ** filter(basedir, globalPackages :+ project.packageName)
        finder.get
      case None =>
        sys.error("currentProject is set to an invalid name: " + projectName)
    }
  }

}
