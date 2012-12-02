package akka.contrib.jul

import com.typesafe.config.ConfigFactory
import akka.actor.{ ActorSystem, Actor, ActorLogging, Props }
import akka.testkit.AkkaSpec
import java.util.logging
import java.util.concurrent.{ TimeUnit, LinkedBlockingQueue }
import org.scalatest.BeforeAndAfterEach

object JavaLoggingEventHandlerSpec {

  val config = ConfigFactory.parseString("""
    akka {
      loglevel = INFO
      event-handlers = ["akka.contrib.jul.JavaLoggingEventHandler"]
    }""")

  class LogProducer extends Actor with ActorLogging {
    def receive = {
      case e: Exception ⇒
        log.error(e, e.getMessage)
      case (s: String, x: Int) ⇒
        log.info(s, x)
    }
  }

  val queue = new LinkedBlockingQueue[logging.LogRecord]
  val logger = logging.Logger.getLogger("akka://JavaLoggingEventHandlerSpec/user/log")
  logger.addHandler(new logging.Handler {
    def publish(record: logging.LogRecord) {
      queue.add(record)
    }

    def flush() {}

    def close() {}
  })
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class JavaLoggingEventHandlerSpec extends AkkaSpec(JavaLoggingEventHandlerSpec.config) with BeforeAndAfterEach {

  import JavaLoggingEventHandlerSpec._

  val producer = system.actorOf(Props[LogProducer], name = "log")

  override def beforeEach(): Unit = {
    queue.clear()
  }

  "JavaLoggingEventHandler" must {

    "log error with stackTrace" in {
      producer ! new RuntimeException("Simulated error (ignore the stacktrace)")

      val record = queue.poll(5, TimeUnit.SECONDS)

      record must not be (null)
      record.getMillis must not be (0)
      record.getThreadID must not be (0)
      record.getLevel must be(logging.Level.SEVERE)
      record.getMessage must be("Simulated error (ignore the stacktrace)")
      record.getThrown.isInstanceOf[RuntimeException] must be(true)
      record.getSourceClassName must be("akka.contrib.jul.JavaLoggingEventHandlerSpec$LogProducer")
      record.getSourceMethodName must be(null)
    }

    "log info without stackTrace" in {
      producer ! ("{} is the magic number", 3)

      val record = queue.poll(5, TimeUnit.SECONDS)

      record must not be (null)
      record.getMillis must not be (0)
      record.getThreadID must not be (0)
      record.getLevel must be(logging.Level.INFO)
      record.getMessage must be("3 is the magic number")
      record.getThrown must be(null)
      record.getSourceClassName must be("akka.contrib.jul.JavaLoggingEventHandlerSpec$LogProducer")
      record.getSourceMethodName must be(null)
    }
  }

}
