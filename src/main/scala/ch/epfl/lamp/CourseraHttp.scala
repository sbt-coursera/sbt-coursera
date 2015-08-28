package ch.epfl.lamp

import dispatch.classic.{ Request, Http, NoLogging, StatusCode, ConfiguredHttpClient, url }
import spray.json.{ JsNull, JsonParser, DefaultJsonProtocol, JsValue }
import RichJsValue._
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.codec.binary.{ Hex, Base64 }
import java.io.{ IOException, File, FileInputStream }

import scalaz._
import Scalaz._

import Settings._
import sbt._
import SbtCourseraPlugin.autoImport._

case class JsonSubmission(api_state: String, user_info: JsValue, submission_metadata: JsValue, solutions: JsValue, submission_encoding: String, submission: String)
//case class JsonQueueResult(submission: JsonSubmission)
object SubmitJsonProtocol extends DefaultJsonProtocol {
  implicit val jsonSubmissionFormat = jsonFormat6(JsonSubmission)
  //  implicit val jsonQueueResultFormat = jsonFormat1(JsonQueueResult)
}

// forwarder to circumvent deprecation
object DeprectaionForwarder {

  @deprecated("", "") class FwdClass { def insecureClientForwarder(credentials: Http.CurrentCredentials) = insecureClient(credentials) }; object FwdClass extends FwdClass
  import org.apache.http.impl.client.DefaultHttpClient
  import org.apache.http.conn.ssl._
  import org.apache.http.conn.scheme._
  import javax.net.ssl.{ X509TrustManager, SSLContext }
  import java.security.cert.X509Certificate
  import org.apache.http.impl.conn.SingleClientConnManager
  import java.security.SecureRandom

  class NaiveTrustManager extends X509TrustManager {

    override def checkClientTrusted(arg0: Array[X509Certificate], arg1: String) {
    }
    override def checkServerTrusted(arg0: Array[X509Certificate], arg1: String) {
    }
    override def getAcceptedIssuers(): Array[X509Certificate] = {
      return null;
    }
  }

  @deprecated("", "") def insecureClient(credentials: Http.CurrentCredentials) = {
    val sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, Array(new NaiveTrustManager()), new SecureRandom());
    val sf = new SSLSocketFactory(sslContext, SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

    val httpsScheme = new Scheme("https", sf, 443);
    val schemeRegistry = new SchemeRegistry();
    schemeRegistry.register(httpsScheme);

    val dispatch_client = new ConfiguredHttpClient(credentials)
    val params = dispatch_client.createHttpParams
    val cm = new SingleClientConnManager(params, schemeRegistry);

    val client = new DefaultHttpClient(cm, params)
    client
  }

}

object CourseraHttp {
  private lazy val http = new Http with NoLogging
  private lazy val insecureHttp = new Http with NoLogging {
    override def make_client = DeprectaionForwarder.FwdClass.insecureClientForwarder(credentials)
  }

  private def executeRequest[T](req: Request)(parse: String => ValidationNel[String, T]): ValidationNel[String, T] = {
    try {
      try http(req >- { res => parse(res) }) catch {
        case ex: javax.net.ssl.SSLPeerUnverifiedException =>
          insecureHttp(req >- { res => parse(res) }) // try the insecure version
      }
    } catch {
      case ex: IOException =>
        ("Connection failed\n" + ex.toString()).failureNel
      case StatusCode(code, message) =>
        ("HTTP failed with status " + code + "\n" + message).failureNel
    }
  }

  /**
   * ****************************
   * SUBMITTING
   */

  def getChallenge(email: String, submitProject: ProjectDetails): ValidationNel[String, Challenge] = {
    val baseReq = url(challengeUrl(submitProject.courseId))
    val withArgs = baseReq << Map("email_address" -> email,
      "assignment_part_sid" -> submitProject.assignmentPartId,
      "response_encoding" -> "delim")

    executeRequest(withArgs) { res =>
      // example result. there might be an `aux_data` value at the end.
      // |email_address|a@b.com|challenge_key|XXYYXXYYXXYY|state|XXYYXXYYXXYY|challenge_aux_data|
      val parts = res.split('|').filterNot(_.isEmpty)
      if (parts.length < 7)
        ("Unexpected challenge format: \n" + res + "\nNOTE: Make sure you have a freshly" +
          " downloaded version of the assignment from the correct course.").failureNel
      else
        Challenge(parts(1), parts(3), parts(5)).successNel
    }
  }

  def submitSolution(sourcesJar: File, submitProject: ProjectDetails, challenge: Challenge, chResponse: String): ValidationNel[String, String] = {
    val fileLength = sourcesJar.length()
    if (!sourcesJar.exists()) {
      ("Sources jar archive does not exist\n" + sourcesJar.getAbsolutePath).failureNel
    } else if (fileLength == 0l) {
      ("Sources jar archive is empty\n" + sourcesJar.getAbsolutePath).failureNel
    } else if (fileLength > maxSubmitFileSize) {
      ("Sources jar archive is too big. Allowed size: " +
        maxSubmitFileSize + " bytes, found " + fileLength + " bytes.\n" +
        sourcesJar.getAbsolutePath).failureNel
    } else {
      val bytes = new Array[Byte](fileLength.toInt)
      val sizeRead = try {
        val is = new FileInputStream(sourcesJar)
        val read = is.read(bytes)
        is.close()
        read
      } catch {
        case ex: IOException =>
          ("Failed to read sources jar archive\n" + ex.toString()).failureNel
      }
      if (sizeRead != bytes.length) {
        ("Failed to read the sources jar archive, size read: " + sizeRead).failureNel
      } else {
        val fileData = encodeBase64(bytes)
        val baseReq = url(submitUrl(submitProject.courseId))
        val withArgs = baseReq << Map("assignment_part_sid" -> submitProject.assignmentPartId,
          "email_address" -> challenge.email,
          "submission" -> fileData,
          "submission_aux" -> "",
          "challenge_response" -> chResponse,
          "state" -> challenge.state)
        executeRequest(withArgs) { res =>
          // the API returns HTTP 200 even if there are failures, how impolite...
          if (res.contains("Your submission has been accepted"))
            res.successNel
          else
            res.failureNel
        }
      }
    }
  }

  def challengeResponse(challenge: Challenge, otPassword: String): String =
    shaHexDigest(challenge.challengeKey + otPassword)

  /**
   * ******************************
   * DOWNLOADING SUBMISSIONS
   */

  def unpackJar(file: File, targetDirectory: File): ValidationNel[String, Unit] = {
    try {
      val files = sbt.IO.unzip(file, targetDirectory)
      if (files.isEmpty)
        ("No files found when unpacking jar file " + file.getAbsolutePath).failureNel
      else
        ().successNel
    } catch {
      case e: IOException =>
        val msg = "Error while unpacking the jar file " + file.getAbsolutePath + " to " + targetDirectory.getAbsolutePath + "\n" + e.toString
        if (Settings.offlineMode) {
          println("[offline mode] " + msg)
          ().successNel
        } else {
          msg.failureNel
        }
    }
  }

  /**
   * *******************************
   * TOOLS AND STUFF
   */

  def shaHexDigest(s: String): String = {
    val chars = Hex.encodeHex(DigestUtils.sha(s))
    new String(chars)
  }

  def fullExceptionString(e: Throwable) =
    e.toString + "\n" + e.getStackTrace.map(_.toString).mkString("\n")

  /* Base 64 tools */

  def encodeBase64(bytes: Array[Byte]): String =
    new String(Base64.encodeBase64(bytes))

  def decodeBase64(str: String): Array[Byte] = {
    // codecs 1.4 has a version accepting a string, but not 1.2; jar hell.
    Base64.decodeBase64(str.getBytes)
  }
}

case class Challenge(email: String, challengeKey: String, state: String)

case class QueueResult(apiState: String)

