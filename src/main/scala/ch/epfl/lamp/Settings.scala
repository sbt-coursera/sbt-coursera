package ch.epfl.lamp

object Settings {

  def baseURL(courseId: String) =
    "https://class.coursera.org/" + courseId

  def challengeUrl(courseId: String) =
    baseURL(courseId) + "/assignment/challenge"

  def submitUrl(courseId: String) =
    baseURL(courseId) + "/assignment/submit"

  def uploadFeedbackUrl(courseId: String) =
    baseURL(courseId) + "/assignment/api/score"

  val maxSubmitFileSize = {
    val mb = 1024 * 1024
    10 * mb
  }
  val submissionDirName = "submission"

  val testResultsFileName = "scalaTestLog"
  val testSummarySuffix = ".summary"
  val policyFileName = "allowAllPolicy"
  val submissionJsonFileName = "submission.json"
  val submissionJarFileName = "submittedSrc.jar"

  // time in seconds that we give scalatest for running
  val scalaTestTimeout = 850 // coursera has a 15 minute timeout anyhow
  val individualTestTimeout = 240

  // default weight of each test in a GradingSuite, in case no weight is given
  val scalaTestDefaultWeigth = 10

  // when students leave print statements in their code, they end up in the output of the
  // system process running ScalaTest (ScalaTestRunner.scala); we need some limits.
  val maxOutputLines = 10 * 1000
  val maxOutputLineLength = 1000

  val scalaTestReportFileProperty = "scalatest.reportFile"
  val scalaTestIndividualTestTimeoutProperty = "scalatest.individualTestTimeout"
  val scalaTestReadableFilesProperty = "scalatest.readableFiles"
  val scalaTestDefaultWeigthProperty = "scalatest.defaultWeight"
  val scalaTestReporter = "ch.epfl.lamp.grading.GradingReporter"

  // debugging / developping options

  // don't decode json and unpack the submission sources, don't upload feedback
  val offlineMode = false
}
