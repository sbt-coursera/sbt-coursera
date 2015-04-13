package ch.epfl.lamp.grading

import java.io.{ File, PrintWriter }
import scala.Predef.{ any2stringadd => _, _ }
import scala.util.Try
import scala.collection.mutable
import scala.pickling.Defaults._
import scala.pickling.json._

/**
 *  Test report produced by the scala-grading consists of a json-serialized
 *  list of entries delimited by new lines.
 *
 *  For example for a test suite "Suite1" with tests "a" (weight 1) and
 *  "b" (weight 2) where second test failed and first one succeed one would
 *  get a following report:
 *
 *    Seq(
 *      SuiteStart("foo::3"),
 *      TestStart("foo::a::1"),
 *      TestSuccess("foo::a::1"),
 *      TestStart("foo::a::2"),
 *      TestFailure("foo::b::2", "FooBarException"),
 *      SuiteEnd("foo::3")
 *    )
 *
 *  Due to matching start/end records it's easy to tell if the thing crashed or not.
 */
object Report {
  final case class SuiteStart(suiteId: SuiteId) extends Entry
  final case class SuiteEnd(suiteId: SuiteId) extends Entry
  final case class TestStart(testId: TestId) extends Entry
  final case class TestSuccess(testId: TestId) extends Entry
  final case class TestFailure(testId: TestId, msg: String) extends Entry
  // Entry has to be defined after subclasses so that
  // directSubclasses work out to generate pickler and unpickler
  sealed abstract class Entry { def toJson: String = this.pickle.value }
  object Entry { def fromJson(value: String) = value.unpickle[Entry] }

  class StringSplitExtractor(splitter: String) {
    def unapplySeq(str: String) = Array.unapplySeq(str.split(splitter))
  }
  object ToInt {
    def unapply(s: String) = Try(s.toInt).toOption
  }

  /** suiteName::suiteWeight */
  type SuiteId = String
  object SuiteId extends StringSplitExtractor("::")

  /** suiteName::testName::testWeight */
  type TestId = String
  object TestId extends StringSplitExtractor("::")

  final case class Suite(val name: String, val weight: Int,
    var complete: Boolean = false,
    val tests: mutable.Map[String, Test] = mutable.Map.empty)
  final case class Test(val name: String, val weight: Int,
    var failure: Option[String])

  /** Replay the event records from the file and reconstruct
   *  a sequence of suites that they represent.
   */
  def summarize(file: File) = {
    val lines = io.Source.fromFile(file).getLines
    val entries = lines.map(Entry.fromJson).toSeq
    val suites = mutable.Map.empty[String, Suite]
    entries.foreach {
      case SuiteStart(SuiteId(name, ToInt(weight))) =>
        suites += name -> Suite(name, weight)
      case SuiteEnd(SuiteId(name, _)) =>
        suites(name).complete = true
      case TestStart(TestId(suiteName, name, ToInt(weight))) =>
        suites(suiteName).tests +=
          name -> Test(name, weight, failure = Some("test has been aborted"))
      case TestSuccess(TestId(suiteName, name, _)) =>
        suites(suiteName).tests(name).failure = None
      case TestFailure(TestId(suiteName, name, _), msg: String) =>
        suites(suiteName).tests(name).failure = Some(msg)
    }
    Summary(suites.values.toList)
  }

  /**
   * Summary of the test report that contains test entries
   *  grouped by their status and computes feedback and score of the submission.
   */
  final case class Summary(suites: List[Suite]) {
    def score: Int = suites.map { _.tests.values.map { t => t.failure.fold(t.weight)(_ => 0) }.sum }.sum
    def maxScore: Int = suites.map { _.weight }.sum

    def feedback: String = {
      val sb = new StringBuilder
      sb.append {
        "Your solution achieved a testing score of %d out of %d.\n\n".format(score, maxScore)
      }
      if (score == maxScore)
        sb.append("Great job!!!\n\n")
      else {
        sb.append("""Below you can see a short feedback for every test that failed,
                    |indicating the reason for the test failure and how many points
                    |you lost for each individual test.
                    |
                    |Tests that were aborted took too long too complete or crashed the
                    |JVM. Such crashes can arise due to infinite non-terminitaing
                    |loops or recursion (StackOverflowException) or excessive mamory
                    |consumption (OutOfMemoryException).
                    |
                    |""".stripMargin)
        for {
          s <- suites
          t <- s.tests.values
          msg <- t.failure
        } {
          sb.append(s"[Test Description] ${s.name}::${t.name}\n")
          sb.append(s"[Observed Error] $msg\n")
          sb.append(s"[Lost Points] ${t.weight}\n\n")
        }
      }
      sb.toString
    }
  }
}
