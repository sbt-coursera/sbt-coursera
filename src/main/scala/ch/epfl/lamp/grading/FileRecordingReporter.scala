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

  private def extractWeight(s: String): (String, Int) = {
    val (num, rest) = s.span(c => c != '\n')
    try {
      (rest.drop(1), num.toInt)
    } catch {
      case e: NumberFormatException =>
        sys.error("Could not extract weight from test name string\n" + s)
    }
  }

  def apply(event: Event): Unit = {
    event match {
      /* We don't get a `TestStarting` for ignored tests, but we do get here
       * one tests that use `pending`.
       */
      case e: SuiteStarting =>
        val (_, weight) = extractWeight(e.suiteName)
        record(Start(weight))
      case e: SuiteCompleted =>
        record(End())
      case e: TestStarting =>
        val (name, weight) = extractWeight(e.testName)
        record(TestStart(name, weight))
      case e: TestSucceeded =>
        val (name, weight) = extractWeight(e.testName)
        record(TestSuccess(name, weight))
      case e: TestPending =>
        val (name, weight) = extractWeight(e.testName)
        record(TestSuccess(name, weight))
      case e: TestFailed =>
        val (name, weight) = extractWeight(e.testName)
        record(TestFailure(name, weight, e.message + (e.throwable match {
          case None => ""
          case Some(testFailed: TestFailedException) => ""
          case Some(thrown) =>
            /* The standard output is captured by sbt and printed as
             * `testing tool debug output` in the feedback.
             */
            println("[test failure log] test name: " + name)
            println(exceptionString(thrown) + "\n\n")
            "\n[exception was thrown] detailed error message in debug output section below"
        })))
      case _ => ()
    }
  }

  def exceptionString(e: Throwable): String =
    e.toString + "\n" + e.getStackTrace.take(25).map(_.toString).mkString("\n")
}
