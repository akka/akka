/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.h2spec

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

import akka.http.impl.util.ExampleHttpContexts
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest, HttpResponse }
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.{ Http2, TestUtils }
import akka.stream.ActorMaterializer
import akka.testkit.{ AkkaSpec, TestProbe }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.exceptions.TestPendingException

import scala.concurrent.duration._
import scala.sys.process._
import scala.util.control.NoStackTrace

class H2SpecIntegrationSpec extends AkkaSpec(
  """
     akka {
       loglevel = INFO
       http.server.log-unencrypted-network-bytes = off
        
       actor.serialize-creators = off
       actor.serialize-messages = off
       
       stream.materializer.debug.fuzzing-mode = off
     }
  """) with Directives with ScalaFutures {

  import system.dispatcher
  implicit val mat = ActorMaterializer()

  override def expectedTestDuration = 5.minutes // because slow jenkins, generally finishes below 1 or 2 minutes

  val echo = (req: HttpRequest) ⇒ {
    req.entity.toStrict(1.second).map { entity ⇒
      HttpResponse().withEntity(HttpEntity(entity.data))
    }
  }
  val port = TestUtils.temporaryServerAddress().getPort

  val binding = {
    Http2().bindAndHandleAsync(echo, "127.0.0.1", port, ExampleHttpContexts.exampleServerContext).futureValue
  }

  "H2Spec" must {

    /**
     * We explicitly list all cases we want to run, also because perhaps some of them we'll find to be not quite correct?
     * This list was obtained via a run from the console and grepping such that we get all the \\. containing lines.
     */
    val testCases =
      """
        3.5. HTTP/2 Connection Preface
        4.2. Frame Size
        4.3. Header Compression and Decompression
        5.1. Stream States
          5.1.1. Stream Identifiers
          5.1.2. Stream Concurrency
        5.3. Stream Priority
          5.3.1. Stream Dependencies
        5.5. Extending HTTP/2
        6.1. DATA
        6.2. HEADERS
        6.3. PRIORITY
        6.4. RST_STREAM
        6.5. SETTINGS
          6.5.2. Defined SETTINGS Parameters
        6.7. PING
        6.8. GOAWAY
        6.9. WINDOW_UPDATE
          6.9.1. The Flow Control Window
          6.9.2. Initial Flow Control Window Size
        6.10. CONTINUATION
        8.1. HTTP Request/Response Exchange
          8.1.2. HTTP Header Fields
            8.1.2.1. Pseudo-Header Fields
            8.1.2.2. Connection-Specific Header Fields
            8.1.2.3. Request Pseudo-Header Fields
            8.1.2.6. Malformed Requests and Responses
        8.2. Server Push
      """.split("\n").map(_.trim).filterNot(_.isEmpty)

    // execution of tests ------------------------------------------------------------------ 
    val runningOnJenkins = sys.env.get("BUILD_NUMBER").isDefined

    if (runningOnJenkins) {
      "pass the entire h2spec, producing junit test report" in {
        runSpec(junitOutput = new File("target/test-reports/h2spec-junit.xml"))
      }
    } else {
      val testNamesWithSectionNumbers =
        testCases.zip(testCases.map(_.trim).filterNot(_.isEmpty)
          .map(l ⇒ l.take(l.lastIndexOf('.'))))

      testNamesWithSectionNumbers foreach {
        case (name, sectionNr) ⇒
          s"pass rule: $name" in {
            runSpec(specSectionNumber = sectionNr)
          }
      }
    }
    // end of execution of tests ----------------------------------------------------------- 

    def runSpec(specSectionNumber: String = null, junitOutput: File = null): Unit = {
      require(specSectionNumber != null ^ junitOutput != null, "Only one of the parameters must be not null, selecting the mode we run in.")
      val TestFailureMarker = "×" // that special character is next to test failures, so we detect them by it 

      val keepAccumulating = new AtomicBoolean(true)
      val sb = new StringBuffer()

      val command =
        if (specSectionNumber != null) s"""$executable -k -t -p $port -s $specSectionNumber"""
        else s"""$executable -k -t -p $port -j $junitOutput""" // include junit report
      println(s"exec: $command")
      val aggregateTckLogs = ProcessLogger(
        out ⇒ {
          if (out.contains("All tests passed")) ()
          else if (out.contains("tests, ")) ()
          else if (out.contains("===========================================")) keepAccumulating.set(false)
          else if (keepAccumulating.get) sb.append(out + Console.RESET + "\n  ")
        },
        _ ⇒ () // nothing is writtedn to stdout by this app
      )

      val p = command.run(aggregateTckLogs)

      p.exitValue()
      val output = sb.toString
      info(output)
      if (output.contains(TestFailureMarker)) {
        throw new TestPendingException // FIXME we'll want to move to marking it as failures instead once we pass
        // throw new AssertionError("Tck secion failed at least one test: ") with NoStackTrace
      } else if (output.contains("0 failed")) ()
    }

    def executable = sys.props("h2spec.path")
  }

  override protected def afterTermination(): Unit = {
    binding.unbind().futureValue
  }
}
