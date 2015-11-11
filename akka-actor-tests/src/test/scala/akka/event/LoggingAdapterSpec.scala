package akka.event

import akka.actor._
import akka.event.Logging.Error.NoCause
import akka.event.Logging.{ Info, Warning, Debug }
import akka.testkit.{ TestKit, TestProbe }
import com.typesafe.config.{ ConfigFactory, Config }
import org.scalatest.{ Matchers, WordSpec }
import scala.concurrent.duration._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class LoggingAdapterSpec extends WordSpec with Matchers {

  val config: Config = ConfigFactory.parseString("""
        akka {
          loglevel = "DEBUG"
          actor.serialize-messages = "off"
        }
  """)

  "LoggingAdapter" must {
    "log debug message with varargs" in {

      implicit val system = ActorSystem("testsystem", config)

      try {

        val testProbe = TestProbe()
        system.eventStream.subscribe(testProbe.ref, classOf[Debug])

        val log: LoggingAdapter = Logging.getLogger(system, this)
        log.debug("{},{},{},{},{},{}", 1, 2, 3, 4, 5, 6)

        val expectedMsg = "1,2,3,4,5,6"
        testProbe.fishForMessage(1.second, hint = expectedMsg) {
          case Logging.Debug(_, _, msg) if msg equals expectedMsg ⇒ true
          case other ⇒ false
        }

      } finally {
        TestKit.shutdownActorSystem(system)
      }
    }
    "log warning message with fixed number of arguments" in {

      implicit val system = ActorSystem("testsystem", config)

      try {

        val testProbe = TestProbe()
        system.eventStream.subscribe(testProbe.ref, classOf[Warning])

        val log: LoggingAdapter = Logging.getLogger(system, this)
        log.warning("{},{},{}", "abc", 2, 1.0)

        val expectedMsg = "abc,2,1.0"
        testProbe.fishForMessage(1.second, hint = expectedMsg) {
          case Logging.Warning(_, _, msg) if msg equals expectedMsg ⇒ true
          case other ⇒ false
        }

      } finally {
        TestKit.shutdownActorSystem(system)
      }
    }
    "log info message with a single argument" in {

      implicit val system = ActorSystem("testsystem", config)

      try {

        val testProbe = TestProbe()
        system.eventStream.subscribe(testProbe.ref, classOf[Info])

        val log: LoggingAdapter = Logging.getLogger(system, this)
        log.info("akka!")

        val expectedMsg = "akka!"
        testProbe.fishForMessage(1.second, hint = expectedMsg) {
          case Logging.Info(_, _, msg) if msg equals expectedMsg ⇒ true
          case other ⇒ false
        }

      } finally {
        TestKit.shutdownActorSystem(system)
      }
    }
    "log error message with a template and one argument" in {

      implicit val system = ActorSystem("testsystem", config)

      try {

        val testProbe = TestProbe()
        system.eventStream.subscribe(testProbe.ref, classOf[Logging.Error])

        val log: LoggingAdapter = Logging.getLogger(system, this)
        log.error("let it crash!")

        val expectedMsg = "let it crash!"
        testProbe.fishForMessage(1.second, hint = expectedMsg) {
          case Logging.Error(_, _, _, msg) if msg equals expectedMsg ⇒ true
          case other ⇒ false
        }

      } finally {
        TestKit.shutdownActorSystem(system)
      }
    }
  }
}
