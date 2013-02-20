/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.testkit._
import scala.concurrent.duration._
import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor.{ ActorRef, Actor, ActorSystem }
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.event.Logging.{ LogEvent, LoggerInitialized, InitializeLogger }

object LoggerSpec {

  val defaultConfig = ConfigFactory.parseString("""
      akka {
        stdout-loglevel = "WARNING"
        loglevel = "DEBUG"
        event-handlers = ["akka.event.LoggerSpec$TestLogger"]
      }
    """).withFallback(AkkaSpec.testConf)

  val noLoggingConfig = ConfigFactory.parseString("""
      akka {
        stdout-loglevel = "OFF"
        loglevel = "OFF"
        event-handlers = ["akka.event.LoggerSpec$TestLogger"]
      }
    """).withFallback(AkkaSpec.testConf)

  case class SetTarget(ref: ActorRef)

  class TestLogger extends Actor with Logging.StdOutLogger {
    var target: Option[ActorRef] = None
    override def receive: Receive = {
      case InitializeLogger(bus) ⇒
        bus.subscribe(context.self, classOf[SetTarget])
        sender ! LoggerInitialized
      case SetTarget(ref) ⇒
        target = Some(ref)
        ref ! ("OK")
      case event: LogEvent ⇒
        print(event)
        target foreach { _ ! event.message }
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class LoggerSpec extends WordSpec with MustMatchers {

  import LoggerSpec._

  private def createSystemAndLogToBuffer(name: String, config: Config, shouldLog: Boolean) = {
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      implicit val system = ActorSystem(name, config)
      try {
        val probe = TestProbe()
        system.eventStream.publish(SetTarget(probe.ref))
        probe.expectMsg("OK")
        system.log.error("Danger! Danger!")
        // since logging is asynchronous ensure that it propagates
        if (shouldLog) {
          probe.fishForMessage(0.5.seconds.dilated) {
            case "Danger! Danger!" ⇒ true
            case _                 ⇒ false
          }
        } else {
          probe.expectNoMsg(0.5.seconds.dilated)
        }
      } finally {
        system.shutdown()
        system.awaitTermination(5.seconds.dilated)
      }
    }
    out
  }

  "A normally configured actor system" must {

    "log messages to standard output" in {
      val out = createSystemAndLogToBuffer("defaultLogger", defaultConfig, true)
      out.size must be > (0)
    }
  }

  "An actor system configured with the logging turned off" must {

    "not log messages to standard output" in {
      val out = createSystemAndLogToBuffer("noLogging", noLoggingConfig, false)
      out.size must be(0)
    }
  }
}
