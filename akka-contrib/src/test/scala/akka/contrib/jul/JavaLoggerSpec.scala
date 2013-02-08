package akka.contrib.jul

import com.typesafe.config.ConfigFactory
import akka.actor.{ ActorSystem, Actor, ActorLogging, Props }
import akka.testkit.AkkaSpec
import java.util.logging
import java.io.ByteArrayInputStream

object JavaLoggerSpec {

  val config = ConfigFactory.parseString("""
    akka {
      loglevel = INFO
      loggers = ["akka.contrib.jul.JavaLogger"]
    }""")

  class LogProducer extends Actor with ActorLogging {
    def receive = {
      case e: Exception ⇒
        log.error(e, e.getMessage)
      case (s: String, x: Int) ⇒
        log.info(s, x)
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class JavaLoggerSpec extends AkkaSpec(JavaLoggerSpec.config) {

  val logger = logging.Logger.getLogger("akka://JavaLoggerSpec/user/log")
  logger.setUseParentHandlers(false) // turn off output of test LogRecords
  logger.addHandler(new logging.Handler {
    def publish(record: logging.LogRecord) {
      testActor ! record
    }

    def flush() {}
    def close() {}
  })

  val producer = system.actorOf(Props[JavaLoggerSpec.LogProducer], name = "log")

  "JavaLogger" must {

    "log error with stackTrace" in {
      producer ! new RuntimeException("Simulated error")

      val record = expectMsgType[logging.LogRecord]

      record must not be (null)
      record.getMillis must not be (0)
      record.getThreadID must not be (0)
      record.getLevel must be(logging.Level.SEVERE)
      record.getMessage must be("Simulated error")
      record.getThrown.isInstanceOf[RuntimeException] must be(true)
      record.getSourceClassName must be(classOf[JavaLoggerSpec.LogProducer].getName)
      record.getSourceMethodName must be(null)
    }

    "log info without stackTrace" in {
      producer ! ("{} is the magic number", 3)

      val record = expectMsgType[logging.LogRecord]

      record must not be (null)
      record.getMillis must not be (0)
      record.getThreadID must not be (0)
      record.getLevel must be(logging.Level.INFO)
      record.getMessage must be("3 is the magic number")
      record.getThrown must be(null)
      record.getSourceClassName must be(classOf[JavaLoggerSpec.LogProducer].getName)
      record.getSourceMethodName must be(null)
    }
  }

}
