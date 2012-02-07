/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event.slf4j

import akka.testkit.AkkaSpec
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.util.duration._
import akka.event.Logging
import akka.actor.Props
import ch.qos.logback.core.OutputStreamAppender
import java.io.StringWriter
import java.io.ByteArrayOutputStream
import org.scalatest.BeforeAndAfterEach

object Slf4jEventHandlerSpec {

  // This test depends on logback configuration in src/test/resources/logback-test.xml

  val config = """
    akka {
      loglevel = INFO
      event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]
    }
    """

  class LogProducer extends Actor with ActorLogging {
    def receive = {
      case e: Exception ⇒
        log.error(e, e.getMessage)
      case (s: String, x: Int, y: Int) ⇒
        log.info(s, x, y)
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
class Slf4jEventHandlerSpec extends AkkaSpec(Slf4jEventHandlerSpec.config) with BeforeAndAfterEach {
  import Slf4jEventHandlerSpec._

  val producer = system.actorOf(Props[LogProducer], name = "logProducer")

  override def beforeEach(): Unit = {
    output.reset()
  }

  "Slf4jEventHandler" must {

    "log error with stackTrace" in {
      producer ! new RuntimeException("Simulated error")

      awaitCond(outputString.contains("----"), 5 seconds)
      val s = outputString
      s must include("akkaSource=[akka://Slf4jEventHandlerSpec/user/logProducer]")
      s must include("level=[ERROR]")
      s must include("logger=[akka.event.slf4j.Slf4jEventHandlerSpec$LogProducer]")
      s must include regex ("sourceThread=\\[ForkJoinPool-[1-9][0-9]*-worker-[1-9][0-9]*\\]")
      s must include("msg=[Simulated error]")
      s must include("java.lang.RuntimeException: Simulated error")
      s must include("at akka.event.slf4j.Slf4jEventHandlerSpec")
    }

    "log info with parameters" in {
      producer ! ("test x={} y={}", 3, 17)

      awaitCond(outputString.contains("----"), 5 seconds)
      val s = outputString
      s must include("akkaSource=[akka://Slf4jEventHandlerSpec/user/logProducer]")
      s must include("level=[INFO]")
      s must include("logger=[akka.event.slf4j.Slf4jEventHandlerSpec$LogProducer]")
      s must include regex ("sourceThread=\\[ForkJoinPool-[1-9][0-9]*-worker-[1-9][0-9]*\\]")
      s must include("msg=[test x=3 y=17]")
    }

    "include system info in akkaSource when creating Logging with system" in {
      val log = Logging(system, "akka.event.slf4j.Slf4jEventHandlerSpec.MyLogSource")
      log.info("test")
      awaitCond(outputString.contains("----"), 5 seconds)
      val s = outputString
      s must include("akkaSource=[akka.event.slf4j.Slf4jEventHandlerSpec.MyLogSource(akka://Slf4jEventHandlerSpec)]")
      s must include("logger=[akka.event.slf4j.Slf4jEventHandlerSpec.MyLogSource(akka://Slf4jEventHandlerSpec)]")
    }

    "not include system info in akkaSource when creating Logging with system.eventStream" in {
      val log = Logging(system.eventStream, "akka.event.slf4j.Slf4jEventHandlerSpec.MyLogSource")
      log.info("test")
      awaitCond(outputString.contains("----"), 5 seconds)
      val s = outputString
      s must include("akkaSource=[akka.event.slf4j.Slf4jEventHandlerSpec.MyLogSource]")
      s must include("logger=[akka.event.slf4j.Slf4jEventHandlerSpec.MyLogSource]")
    }

    "use short class name and include system info in akkaSource when creating Logging with system and class" in {
      val log = Logging(system, classOf[MyLogSource])
      log.info("test")
      awaitCond(outputString.contains("----"), 5 seconds)
      val s = outputString
      s must include("akkaSource=[Slf4jEventHandlerSpec$MyLogSource(akka://Slf4jEventHandlerSpec)]")
      s must include("logger=[akka.event.slf4j.Slf4jEventHandlerSpec$MyLogSource]")
    }

    "use short class name in akkaSource when creating Logging with system.eventStream and class" in {
      val log = Logging(system.eventStream, classOf[MyLogSource])
      log.info("test")
      awaitCond(outputString.contains("----"), 5 seconds)
      val s = outputString
      s must include("akkaSource=[Slf4jEventHandlerSpec$MyLogSource]")
      s must include("logger=[akka.event.slf4j.Slf4jEventHandlerSpec$MyLogSource]")
    }
  }

}
