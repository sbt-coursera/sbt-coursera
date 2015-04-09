package ch.epfl.lamp

import java.io.{ File, PrintWriter }
import scala.Predef.{ any2stringadd => _, _ }
import scala.collection.mutable
import scala.pickling.Defaults._
import scala.pickling.json._

/**
 * Test report produced by the scala-grading consists of a json-serialized
 *  list of entries delimited by new lines. Each entry corresponds to a scalatest event.
 *
 *  For example for a test suite "Suite1" with tests "a" (weight 1) and
 *  "b" (weight 2) where second test failed and first one succeed one would
 *  get a following report:
 *
 *    Seq(
 *      Start(3),
 *      TestStart("a", 1),
 *      TestSuccess("a", 1),
 *      TestStart("b", 2),
 *      TestFailure("b", 2, "FooBarException"),
 *      End()
 *    )
 *
 *  Due to matching start/end entries it's easy and report if test crashed midway.
 *
 *  Pending tests are considered to be successful, ignored tests are not recorded.
 */
object TestReport {
  final case class Start(totalWeight: Int) extends Entry
  final case class End() extends Entry
  final case class TestStart(testName: String, testWeight: Int) extends Entry
  final case class TestSuccess(testName: String, testWeight: Int) extends Entry
  final case class TestFailure(testName: String, testWeight: Int, msg: String) extends Entry
  // Entry has to be defined after subclasses so that
  // directSubclasses work out to generate pickler and unpickler
  sealed abstract class Entry { def toJson: String = this.pickle.value }
  object Entry { def fromJson(value: String) = value.unpickle[Entry] }

  /** Replay the records from the report file and construct a summary. */
  def summarize(file: File) = {
    val lines = io.Source.fromFile(file).getLines
    val entries = lines.map(Entry.fromJson).toSeq

    var complete = false
    var totalWeight = 0
    val started = mutable.Map.empty[String, TestStart]
    var succeeded = List.empty[TestSuccess]
    var failed = List.empty[TestFailure]
    entries.foreach {
      case Start(weight) =>
        totalWeight = weight
      case End() =>
        complete = true
      case e @ TestStart(name, _) =>
        started += name -> e
      case e @ TestSuccess(name, _) =>
        succeeded ::= e
        started -= name
      case e @ TestFailure(name, _, _) =>
        failed ::= e
        started -= name
    }
    assert(totalWeight > 0)

    Summary(complete, totalWeight, started.values.toList, succeeded, failed)
  }

  /**
   * Summary of the test report that contains test entries
   *  grouped by their status and computes feedback and score of the submission.
   */
  final case class Summary(
      complete: Boolean,
      maxScore: Int,
      unfinishedTests: List[TestStart],
      succeededTests: List[TestSuccess],
      failedTests: List[TestFailure]) {
    def score: Int = succeededTests.map(_.testWeight).sum
    def feedback: String = {
      val sb = new StringBuilder
      sb.append {
        "Your solution achieved a testing score of %d out of %d.\n".format(score, maxScore)
      }
      if (score == maxScore)
        sb.append("Great job!!!\n\n")
      else {
        sb.append("""Below you can see a short feedback for every test that failed,
                    |indicating the reason for the test failure and how many points
                    |you lost for each individual test.
                    |
                    |""".stripMargin)
        failedTests.foreach { e =>
          sb.append(s"[Test Description] ${e.testName}")
          sb.append(s"[Observed Error] ${e.msg}")
          sb.append(s"[Lost Points] ${e.testWeight}")
        }
        sb.append("\n\n")
      }
      if (unfinishedTests.nonEmpty) {
        sb.append("""Below are names of the tests that crashed the jvm or took too long
                    |too complete. Such crashes can arise due to infinite non-terminitaing
                    |loops or recursion (StackOverflowException) or excessive mamory
                    |consumption (OutOfMemoryException). You can see more details in the
                    |tool log.
                    |
                    |""".stripMargin)
        unfinishedTests.foreach { e =>
          sb.append(s"[Test Description] ${e.testName}")
        }
        sb.append("\n\n")
      } else if (!complete) {
        sb.append("""An internal error happened while testing your code. Please send your
                   | entire feedback message to one of the teaching assistants.
                   |
                   |""".stripMargin)
      }
      sb.toString
    }
  }
}
