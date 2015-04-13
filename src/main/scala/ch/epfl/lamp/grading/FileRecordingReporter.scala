package ch.epfl.lamp.grading

import java.io.File
import scala.collection.mutable.ListBuffer
import org.scalatest.exceptions.TestFailedException
import org.scalatest.Reporter
import org.scalatest.events._
import ch.epfl.lamp.grading.Report._

class FileRecordingReporter extends Reporter {
  private val outfile: File = {
    val prop = System.getProperty("scalatest.reportFile")
    if (prop == null) sys.error("scalatest.reportFile property not defined")
    new File(prop)
  }

  private def writeOutfile(s: String): Unit = {
    val p = new java.io.PrintWriter(outfile)
    try p.print(s)
    finally p.close()
  }

  private def record(entry: Entry) = writeOutfile(entry.toJson)

  def apply(event: Event): Unit = event match {
    /* We don't get a `TestStarting` for ignored tests, but we do get here
     * one tests that use `pending`.
     */
    case e: SuiteStarting => record(SuiteStart(e.suiteName))
    case e: SuiteCompleted => record(SuiteEnd(e.suiteName))
    case e: TestStarting => record(TestStart(e.testName))
    case e: TestSucceeded => record(TestSuccess(e.testName))
    case e: TestPending => record(TestSuccess(e.testName))
    case e: TestFailed =>
      record(TestFailure(e.testName, e.message + (e.throwable match {
        case None => ""
        case Some(testFailed: TestFailedException) => ""
        case Some(thrown) =>
          /* The standard output is captured by sbt and printed as
           * `testing tool debug output` in the feedback.
           */
          println("[test failure log] test name: " + e.testName)
          println(exceptionString(thrown) + "\n\n")
          "\n[exception was thrown] detailed error message in debug output section below"
      })))
    case _ => ()
  }

  def exceptionString(e: Throwable): String =
    e.toString + "\n" + e.getStackTrace.take(25).map(_.toString).mkString("\n")
}
