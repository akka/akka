package akka.contrib.jul

import akka.actor.{ActorSystem, Actor, ActorLogging}
import java.util.logging
import akka.testkit.{TestActorRef, TestKit}
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.{Before, Specification}
import java.util.concurrent.{TimeUnit, LinkedBlockingQueue}


object JavaLoggingEventHandlerSpec {

  val config = ConfigFactory.parseString( """
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
  val logger = logging.Logger.getLogger("akka://test/user/log")
  logger.addHandler(new logging.Handler {
    def publish(record: logging.LogRecord) {
      queue.add(record)
    }

    def flush() {}

    def close() {}
  })
}

import JavaLoggingEventHandlerSpec._

class JavaLoggingEventHandlerSpec extends TestKit(ActorSystem("test", config))
with Specification with Before {

  val producer = TestActorRef[LogProducer]("log")

  def before() {
    queue.clear()
  }

  "JavaLoggingEventHandler" should {

    "log error with stackTrace" in {
      producer ! new RuntimeException("Simulated error")

      val record = queue.poll(5, TimeUnit.SECONDS)

      record mustNotEqual null
      record.getMillis mustNotEqual 0
      record.getThreadID mustNotEqual 0
      record.getLevel mustEqual logging.Level.SEVERE
      record.getMessage mustEqual "Simulated error"
      record.getThrown must beAnInstanceOf[RuntimeException]
      record.getSourceClassName mustEqual "akka.contrib.jul.JavaLoggingEventHandlerSpec$LogProducer"
      record.getSourceMethodName mustEqual "<unknown>"
    }

    "log info without stackTrace" in {
      producer ! ("{} is the magic number", 3)

      val record = queue.poll(5, TimeUnit.SECONDS)

      record mustNotEqual null
      record.getMillis mustNotEqual 0
      record.getThreadID mustNotEqual 0
      record.getLevel mustEqual logging.Level.INFO
      record.getMessage mustEqual "3 is the magic number"
      record.getThrown mustEqual null
      record.getSourceClassName mustEqual "akka.contrib.jul.JavaLoggingEventHandlerSpec$LogProducer"
      record.getSourceMethodName mustEqual "<unknown>"
    }
  }

}
