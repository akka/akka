/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.testkit.AkkaSpec
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.WordSpec
import akka.actor.ActorSystem
import org.scalatest.matchers.MustMatchers

object LoggerSpec {

  val defaultConfig = ConfigFactory.parseString("""
      akka {
        stdout-loglevel = "WARNING"
        loglevel = "DEBUG"
      }
    """).withFallback(AkkaSpec.testConf)

  val noLoggingConfig = ConfigFactory.parseString("""
      akka {
        stdout-loglevel = "OFF"
        loglevel = "OFF"
      }
    """).withFallback(AkkaSpec.testConf)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class LoggerSpec extends WordSpec with MustMatchers {

  import LoggerSpec._

  private def createSystemAndLogToBuffer(name: String, config: Config) = {
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      val system = ActorSystem(name, config)
      try {
        system.log.error("Danger! Danger!")
      } finally {
        system.shutdown()
      }
    }
    out
  }

  "A normally configured actor system" must {

    "log messages to standard output" in {
      val out = createSystemAndLogToBuffer("defaultLogger", defaultConfig)
      out.size must be > (0)
    }
  }

  "An actor system configured with the logging turned off" must {

    "not log messages to standard output" in {
      val out = createSystemAndLogToBuffer("noLogging", noLoggingConfig)
      out.size must be(0)
    }
  }
}
