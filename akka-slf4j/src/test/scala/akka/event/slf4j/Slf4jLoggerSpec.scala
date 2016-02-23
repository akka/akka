/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.event.slf4j

import language.postfixOps

import akka.testkit.AkkaSpec
import akka.actor.{ DiagnosticActorLogging, Actor, Props }
import scala.concurrent.duration._
import akka.event.Logging
import ch.qos.logback.core.OutputStreamAppender
import java.io.ByteArrayOutputStream
import org.scalatest.BeforeAndAfterEach

object Slf4jLoggerSpec {

  // This test depends on logback configuration in src/test/resources/logback-test.xml

  val config = """
    akka {
      loglevel = INFO
      loggers = ["akka.event.slf4j.Slf4jLogger"]
      logger-startup-timeout = 30s
    }
    """

  case class StringWithMDC(s: String, mdc: Map[String, Any])

  class LogProducer extends Actor with DiagnosticActorLogging {

    def receive = {
      case e: Exception ⇒
        log.error(e, e.getMessage)
      case (s: String, x: Int, y: Int) ⇒
        log.info(s, x, y)
      case StringWithMDC(s, mdc) ⇒
        log.mdc(mdc)
        log.info(s)
        log.clearMDC()
    }
  }

  class MyLogSource

  val output = new ByteArrayOutputStream
  def outputString: String = output.toString("UTF-8")

  class TestAppender extends OutputStreamAppender {

    override def start(): Unit = {
      setOutputStream(output)
      super.start()
    }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Slf4jLoggerSpec extends AkkaSpec(Slf4jLoggerSpec.config) with BeforeAndAfterEach {
  import Slf4jLoggerSpec._

  val producer = system.actorOf(Props[LogProducer], name = "logProducer")

  override def beforeEach(): Unit = {
    output.reset()
  }

  val sourceThreadRegex = "sourceThread=\\[Slf4jLoggerSpec-akka.actor.default-dispatcher-[1-9][0-9]*\\]"

  "Slf4jLogger" must {

    "log error with stackTrace" in {
      producer ! new RuntimeException("Simulated error")

      awaitCond(outputString.contains("----"), 5 seconds)
      val s = outputString
      s should include("akkaSource=[akka://Slf4jLoggerSpec/user/logProducer]")
      s should include("level=[ERROR]")
      s should include("logger=[akka.event.slf4j.Slf4jLoggerSpec$LogProducer]")
      s should include regex (sourceThreadRegex)
      s should include("msg=[Simulated error]")
      s should include("java.lang.RuntimeException: Simulated error")
      s should include("at akka.event.slf4j.Slf4jLoggerSpec")
    }

    "log info with parameters" in {
      producer ! (("test x={} y={}", 3, 17))

      awaitCond(outputString.contains("----"), 5 seconds)
      val s = outputString
      s should include("akkaSource=[akka://Slf4jLoggerSpec/user/logProducer]")
      s should include("level=[INFO]")
      s should include("logger=[akka.event.slf4j.Slf4jLoggerSpec$LogProducer]")
      s should include regex (sourceThreadRegex)
      s should include("msg=[test x=3 y=17]")
    }

    "put custom MDC values when specified" in {
      producer ! StringWithMDC("Message with custom MDC values", Map("ticketNumber" -> 3671, "ticketDesc" -> "Custom MDC Values"))

      awaitCond(outputString.contains("----"), 5 seconds)
      val s = outputString
      s should include("akkaSource=[akka://Slf4jLoggerSpec/user/logProducer]")
      s should include("level=[INFO]")
      s should include("logger=[akka.event.slf4j.Slf4jLoggerSpec$LogProducer]")
      s should include regex (sourceThreadRegex)
      s should include("mdc=[ticket-#3671: Custom MDC Values]")
      s should include("msg=[Message with custom MDC values]")
    }

    "Support null values in custom MDC" in {
      producer ! StringWithMDC("Message with null custom MDC values", Map("ticketNumber" -> 3671, "ticketDesc" -> null))

      awaitCond(outputString.contains("----"), 5 seconds)
      val s = outputString
      s should include("akkaSource=[akka://Slf4jLoggerSpec/user/logProducer]")
      s should include("level=[INFO]")
      s should include("logger=[akka.event.slf4j.Slf4jLoggerSpec$LogProducer]")
      s should include regex (sourceThreadRegex)
      s should include("mdc=[ticket-#3671: null]")
      s should include("msg=[Message with null custom MDC values]")
    }

    "include system info in akkaSource when creating Logging with system" in {
      val log = Logging(system, "akka.event.slf4j.Slf4jLoggerSpec.MyLogSource")
      log.info("test")
      awaitCond(outputString.contains("----"), 5 seconds)
      val s = outputString
      s should include("akkaSource=[akka.event.slf4j.Slf4jLoggerSpec.MyLogSource(akka://Slf4jLoggerSpec)]")
      s should include("logger=[akka.event.slf4j.Slf4jLoggerSpec.MyLogSource(akka://Slf4jLoggerSpec)]")
    }

    "not include system info in akkaSource when creating Logging with system.eventStream" in {
      val log = Logging(system.eventStream, "akka.event.slf4j.Slf4jLoggerSpec.MyLogSource")
      log.info("test")
      awaitCond(outputString.contains("----"), 5 seconds)
      val s = outputString
      s should include("akkaSource=[akka.event.slf4j.Slf4jLoggerSpec.MyLogSource]")
      s should include("logger=[akka.event.slf4j.Slf4jLoggerSpec.MyLogSource]")
    }

    "use short class name and include system info in akkaSource when creating Logging with system and class" in {
      val log = Logging(system, classOf[MyLogSource])
      log.info("test")
      awaitCond(outputString.contains("----"), 5 seconds)
      val s = outputString
      s should include("akkaSource=[Slf4jLoggerSpec$MyLogSource(akka://Slf4jLoggerSpec)]")
      s should include("logger=[akka.event.slf4j.Slf4jLoggerSpec$MyLogSource]")
    }

    "use short class name in akkaSource when creating Logging with system.eventStream and class" in {
      val log = Logging(system.eventStream, classOf[MyLogSource])
      log.info("test")
      awaitCond(outputString.contains("----"), 5 seconds)
      val s = outputString
      s should include("akkaSource=[Slf4jLoggerSpec$MyLogSource]")
      s should include("logger=[akka.event.slf4j.Slf4jLoggerSpec$MyLogSource]")
    }

    "include actorSystem name in sourceActorSystem" in {
      val log = Logging(system.eventStream, classOf[MyLogSource])
      log.info("test")
      awaitCond(outputString.contains("----"), 5 seconds)
      val s = outputString
      s should include("sourceActorSystem=[Slf4jLoggerSpec]")
    }
  }

}
