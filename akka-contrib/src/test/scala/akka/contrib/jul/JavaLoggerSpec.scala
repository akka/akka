/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.contrib.jul

import com.typesafe.config.ConfigFactory
import akka.actor.{ Actor, ActorLogging, Props }
import akka.testkit.AkkaSpec
import java.util.logging

object JavaLoggerSpec {

  val config = ConfigFactory.parseString("""
    akka {
      loglevel = INFO
      loggers = ["akka.contrib.jul.JavaLogger"]
    }""")

  class LogProducer extends Actor with ActorLogging {
    def receive = {
      case e: Exception =>
        log.error(e, e.getMessage)
      case (s: String, x: Int) =>
        log.info(s, x)
    }
  }
}

class JavaLoggerSpec extends AkkaSpec(JavaLoggerSpec.config) {

  val logger = logging.Logger.getLogger("akka://JavaLoggerSpec/user/log")
  logger.setUseParentHandlers(false) // turn off output of test LogRecords
  logger.addHandler(new logging.Handler {
    def publish(record: logging.LogRecord): Unit = {
      testActor ! record
    }

    def flush(): Unit = {}
    def close(): Unit = {}
  })

  val producer = system.actorOf(Props[JavaLoggerSpec.LogProducer], name = "log")

  "JavaLogger" must {

    "log error with stackTrace" in {
      producer ! new RuntimeException("Simulated error")

      val record = expectMsgType[logging.LogRecord]

      record should not be (null)
      record.getMillis should not be (0)
      record.getThreadID should not be (0)
      record.getLevel should ===(logging.Level.SEVERE)
      record.getMessage should ===("Simulated error")
      record.getThrown.isInstanceOf[RuntimeException] should ===(true)
      record.getSourceClassName should ===(classOf[JavaLoggerSpec.LogProducer].getName)
      record.getSourceMethodName should ===(null)
    }

    "log info without stackTrace" in {
      producer ! (("{} is the magic number", 3))

      val record = expectMsgType[logging.LogRecord]

      record should not be (null)
      record.getMillis should not be (0)
      record.getThreadID should not be (0)
      record.getLevel should ===(logging.Level.INFO)
      record.getMessage should ===("3 is the magic number")
      record.getThrown should ===(null)
      record.getSourceClassName should ===(classOf[JavaLoggerSpec.LogProducer].getName)
      record.getSourceMethodName should ===(null)
    }
  }

}
