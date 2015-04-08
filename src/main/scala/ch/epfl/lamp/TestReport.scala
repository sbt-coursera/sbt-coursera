package ch.epfl.lamp

import scala.pickling.static._
import scala.pickling.json._
import java.io.{File, PrintWriter}

/** Test report produced by the scala-grading consists of a json-serialized
 *  list of entries delimited by new lines. Each entry corresponds to a scalatest event.
 *
 *  For example for a test suite "Suite1" with tests "a" (weight 1) and
 *  "b" (weight 2) where second test failed and first one succeed one would
 *  get a following report:
 *
 *    Seq(
 *      SuiteStart("Suite1"),
 *      TestStart("a"),
 *      TestSuccess("a", 1),
 *      TestStart("b"),
 *      TestFailure("b", 2),
 *      SuiteEnd("Suite1")
 *    )
 *
 *  Due to matching start/end entries it's easy and report if test crashed midway.
 *
 *  Pending tests are considered to be successful, ignored tests are not recorded.
 */
object TestReport {
  sealed abstract class Entry { def json: String = this.pickle.value }
  object Entry { def fromJson(value: String) = value.unpickle[Entry] }
  final case class SuiteStart(suiteName: String) extends Entry
  final case class SuiteEnd(suiteName: String) extends Entry
  final case class TestStart(testName: String) extends Entry
  final case class TestSuccess(testName: String, testWeight: Int) extends Entry
  final case class TestFailure(testName: String, testWeight: Int, msg: String) extends Entry

  /** Replay the records from the report file and construct a summary. */
  def summarize(file: File) = {
    val lines     = io.Source.fromFile(file).getLines
    val entries   = lines.map(Entry.fromJson).toSeq

    val suites    = mut.Set.empty[Entry.SuiteStart]
    val started   = mut.Set.empty[Entry.TestStart]
    var succeeded = List.empty[Entry.TestSuccess]
    var failed    = List.empty[Entry.TestFailure]
    entries.foreach {
      case e @ SuiteStart(_)        => suites += e
      case SuiteEnd(name)           => suites -= SuiteStart(name)
      case e @ TestStart(name)      => started += e
      case e @ TestSuccess(name, _) =>
        started  -= TestStart(name)
        succeded += e
      case TestFailure(name, _, _) =>
        started -= TestStart(name)
        failed  += e
    }

    Summary(suites.nonEmpty, started, succeeded, failed)
  }

  /** Summary of the test report that contains test entries
   *  grouped by their status and computes feedback and score of the submission.
   */
  final case class Summary(suitesDone: Boolean, crashedTests: List[TestStart],
                           succeededTests: List[TestSucces], failedTests: List[TestFailure])
    def score: Int = succeededTest.map(_.weight).sum
    def maxScore: Int = score + failedTests.map(_.weight).sum + hangTests.map(_.weight)
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
      if (crashedTests.nonEmpty) {
        sb.append("""Below are names of the tests that crashed the jvm or took too long too complete.
                    |Such crashes can arise due to infinite non-terminitaing loops
                    |or recursion (StackOverflowException) or excessive mamory consumption
                    |(OutOfMemoryException). You can see more details in tool log.
                    |
                    |""".stripMargin)
        crashedTests.foreach { e =>
          sb.append(s"[Test Description] ${e.testName}")
        }
        sb.append("\n\n")
      } else if (!suitesDone) {
        sb.append("""An internal error happened while testing your code. Please send your
                   | entire feedback message to one of the teaching assistants.
                   |
                   |""".stripMargin)
      }
      sb.toString
    }
  }
}
