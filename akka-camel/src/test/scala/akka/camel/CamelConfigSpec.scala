/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel
import org.scalatest.matchers.MustMatchers
import org.scalatest.WordSpec
import akka.actor.ActorSystem
import scala.concurrent.util.Duration
import java.util.concurrent.TimeUnit._

class CamelConfigSpec extends WordSpec with MustMatchers {

  "CamelConfigSpec" must {
    "have correct config" in {
      val system = ActorSystem("CamelConfigSpec")
      try {
        val settings = CamelExtension(system).settings

        val config = system.settings.config

        settings.activationTimeout must be === Duration(config.getMilliseconds("akka.camel.consumer.activation-timeout"), MILLISECONDS)

        settings.autoAck must be === config.getBoolean("akka.camel.consumer.auto-ack")

        settings.replyTimeout must be === Duration(config.getMilliseconds("akka.camel.consumer.reply-timeout"), MILLISECONDS)

        settings.streamingCache must be === config.getBoolean("akka.camel.streamingCache")

        settings.jmxStatistics must be === config.getBoolean("akka.camel.jmx")

        val conversions = config.getConfig("akka.camel.conversions")

        conversions.getString("file") must be === "java.io.InputStream"
        conversions.entrySet.size must be === 1

      } finally system.shutdown()
    }
  }
}

