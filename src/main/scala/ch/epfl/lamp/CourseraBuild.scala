package ch.epfl.lamp

import sbt._
import Keys._

import SbtCourseraPlugin._
import ch.epfl.lamp.SbtCourseraPlugin.autoImport._
import com.typesafe.sbteclipse.plugin.EclipsePlugin
import EclipsePlugin.EclipseKeys
import scalaz.{ Success, Failure }

trait CourseraBuild extends sbt.Build {
  // additional assignment settings
  def assignmentSettings: Seq[Setting[_]] = Seq()

  lazy val assignmentProject = Project(id = "assignment", base = file(".")).settings(
    // 'submit' depends on 'packageSrc', so needs to be a project-level setting: on build-level, 'packageSrc' is not defined
    submitSetting,
    createHandoutSetting,
    EclipseKeys.relativizeLibs := true,
    // Avoid generating eclipse source entries for the java directories
    (unmanagedSourceDirectories in Compile) <<= (scalaSource in Compile)(Seq(_)),
    (unmanagedSourceDirectories in Test) <<= (scalaSource in Test)(Seq(_)),
    commonSourcePackages := Seq(), // see build.sbt
    gradingTestPackages := Seq(), // see build.sbt
    scalaTestJavaAgents := Seq(),
    scalaTestJavaSysProps := Seq(),
    selectMainSources,
    selectTestSources,
    scalaTestSetting,
    styleCheckSetting,
    setTestPropertiesSetting,
    setTestPropertiesHook,
    name <<= submitProjectName(pname => pname),
    allProjectsSetting,
    javaOptions ++= testProperties.map { case (name, value) => s"-D$name=$value" }
  ) settings ((packageSubmissionFiles ++ dependencies ++ assignmentSettings): _*)

  lazy val submissionProject = Project(id = "submission", base = file(Settings.submissionDirName)) settings (
    /** settings we take over from the assignment project */
    version <<= (version in assignmentProject),
    name <<= (name in assignmentProject),
    scalaVersion <<= (scalaVersion in assignmentProject),
    scalacOptions <<= (scalacOptions in assignmentProject),
    libraryDependencies <<= (libraryDependencies in assignmentProject),
    unmanagedBase <<= (unmanagedBase in assignmentProject),
    scalaTestJavaAgents in Test <<= (scalaTestJavaAgents in (assignmentProject, Test)),
    scalaTestJavaSysProps in Test <<= (scalaTestJavaSysProps in (assignmentProject, Test)),

    /** settings specific to the grading project */
    initGradingSetting,
    // default value, don't change. see comment on `val partIdOfGradingProject`
    gradingUUID := "",
    partIdOfGradingProject := "",
    gradingCourseId := "",
    gradeProjectDetailsSetting,
    setMaxScoreSetting,
    setMaxScoreHook,
    // default value, don't change. see comment on `val apiKey`
    apiKey := "",
    getSubmissionSetting,
    getSubmissionHook,
    submissionLoggerSetting,
    readCompileLog,
    readTestCompileLog,
    setTestPropertiesSetting,
    setTestPropertiesHook,
    resourcesFromAssignment,
    selectResourcesForProject,
    testSourcesFromAssignment,
    selectTestsForProject,
    scalaTestSubmissionSetting,
    styleCheckSubmissionSetting,
    gradeSetting,
    EclipseKeys.skipProject := true
  )

  lazy val dependencies = Seq(
    libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.4" % "test",
    libraryDependencies += "junit" % "junit" % "4.10" % "test",
    libraryDependencies ++= {
      val projects = projectDetailsMap.value
      val current = currentProject.value
      if (current == "") projects.values.flatMap(_.dependencies).toSeq
      else projects(current).dependencies
    })

  /**
   * **********************************************************
   * SUBMITTING GRADES TO COURSERA
   */

  /** ProjectDetails of the project that we are grading */
  val gradeProjectDetails = TaskKey[ProjectDetails]("gradeProjectDetails")

  // here we depend on `initialize` because we already use the GradingFeedback
  lazy val gradeProjectDetailsSetting = gradeProjectDetails <<= (gradingCourseId, partIdOfGradingProject, projectDetailsMap in assignmentProject) map { (gradingCourseId, partId, detailsMap) =>
    detailsMap.find(_._2.assignmentPartId == partId) match {
      case Some((_, details)) =>
        details.copy(courseId = gradingCourseId)
      case None =>
        val validIds = detailsMap.map(_._2.assignmentPartId)
        val msgRaw =
          """Unknown assignment part id: %s
              |Valid part ids are: %s
              |
              |In order to grade a project, the `partIdOfGradingProject` setting has to be defined. If you are running
              |interactively in the sbt console, type `set (partIdOfGradingProject in submissionProject) := "idString"`.
              |When running the grading task from the command line, add the above `set` command, e.g. execute
              |
              |  sbt 'set (partIdOfGradingProject in submissionProject) := "idString"' submission/grade"""
        val msg = msgRaw.stripMargin.format(partId, validIds.mkString(", ")) + "\n "
        GradingFeedback.downloadUnpackFailed(msg)
        sys.error(msg)
    }
  }

  val setMaxScore = TaskKey[Unit]("setMaxScore")
  val setMaxScoreSetting = setMaxScore <<= (gradeProjectDetails) map { project =>
    GradingFeedback.setMaxScore(project.maxScore, project.styleScoreRatio)
  }

  // set the maximal score before running compile / test / ...
  val setMaxScoreHook = (compile in Compile) <<= (compile in Compile).dependsOn(setMaxScore)

  val grade = TaskKey[Unit]("grade")

  // mapR: submit the grade / feedback in any case, also on failure
  lazy val gradeSetting = grade <<= (gradingUUID, scalaTestSubmission, styleCheckSubmission, apiKey, gradeProjectDetails, streams) mapR { (uuidR, sts, scs, apiKeyR, projectDetailsR, s) =>
    val Value(uuid) = uuidR
    val logOpt = s match {
      case Value(v) => Some(v.log)
      case _ => None
    }
    logOpt.foreach(_.info(GradingFeedback.feedbackString(uuid, html = false)))
    val Value(projectDetails) = projectDetailsR
    apiKeyR match {
      case Value(apiKey) if (!apiKey.isEmpty) =>
        logOpt.foreach(_.debug("Course Id for submission: " + projectDetails.courseId))
        logOpt.foreach(_.debug("Corresponding API key: " + apiKey))
        // if build failed early, we did not even get the api key from the submission queue
        if (!GradingFeedback.apiState.isEmpty && !Settings.offlineMode) {
          val scoreString = "%.2f".format(GradingFeedback.totalScore)
          CourseraHttp.submitGrade(GradingFeedback.feedbackString(uuid), scoreString, GradingFeedback.apiState, apiKey, projectDetails, logOpt) match {
            case Failure(msgs) =>
              sys.error(msgs.list.mkString("\n"))
            case _ =>
              ()
          }
        } else if (Settings.offlineMode) {
          logOpt.foreach(_.info(" \nSettings.offlineMode enabled, not uploading the feedback"))
        } else {
          sys.error("Could not submit feedback - apiState not initialized")
        }
      case _ =>
        sys.error("Could not submit feedback - apiKey not defined: " + apiKeyR)
    }
  }

  /** The submission project takes resource files from the main (assignment) project */
  val resourcesFromAssignment = {
    (resourceDirectory in Compile) <<= (resourceDirectory in (assignmentProject, Compile))
  }

  /**
   * Only include the resource files which are defined in the package of the current project.
   */
  val selectResourcesForProject = {
    (resources in Compile) <<= (resources in Compile, resourceDirectory in (assignmentProject, Compile), gradeProjectDetails) map { (resources, resourceDir, project) =>
      val finder = resources ** filter(resourceDir, List(project.packageName))
      finder.get
    }
  }

  /** The submission project takes test files from the main (assignment) project */
  val testSourcesFromAssignment = {
    (sourceDirectory in Test) <<= (sourceDirectory in (assignmentProject, Test))
  }

  /**
   * Only include the test files which are defined in the package of the current project.
   * Also keeps test sources in packages listed in 'gradingTestPackages'
   */
  val selectTestsForProject = {
    (unmanagedSources in Test) <<= (unmanagedSources in Test, scalaSource in (assignmentProject, Test), gradingTestPackages in assignmentProject, gradeProjectDetails) map { (sources, testSrcScalaDir, gradingSrcs, project) =>
      val finder = sources ** filter(testSrcScalaDir, gradingSrcs :+ project.packageName)
      finder.get
    }
  }

  /**
   * **********************************************************
   * STYLE CHECKING
   */
  /**
   * - depend on scalaTestSubmission so that test get executed before style checking. the transitive
   *   dependencies also ensures that the "sources in Compile" don't have compilation errors
   * - using `map` makes this task execute only if all its dependencies succeeded.
   */
  val styleCheckSubmissionSetting = styleCheckSubmission <<= (sources in Compile, scalaTestSubmission, projectDetailsMap in assignmentProject, submitProjectName in assignmentProject) map { (sourceFiles, _, projectDetails, submitProjectName) =>
    val project = projectDetails(submitProjectName)
    if (project.styleSheet != "") {
      val (styleSheet, courseId) = (project.styleSheet, project.courseId)
      val (feedback, score) = StyleChecker.assess(sourceFiles, styleSheet, courseId)
      if (score == StyleChecker.maxResult) {
        GradingFeedback.perfectStyle()
      } else {
        val gradeScore = GradingFeedback.maxStyleScore * score / StyleChecker.maxResult
        GradingFeedback.styleProblems(feedback, gradeScore)
      }
    }
  }

}
