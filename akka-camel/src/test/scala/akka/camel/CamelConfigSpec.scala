/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel
import org.scalatest.matchers.MustMatchers
import org.scalatest.WordSpec
import akka.actor.ActorSystem
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit._

class CamelConfigSpec extends WordSpec with MustMatchers {

  val (settings, config) = {
    val system = ActorSystem("CamelConfigSpec")
    val result = (CamelExtension(system).settings, system.settings.config)
    system.shutdown()
    result
  }
  "CamelConfigSpec" must {
    "have correct activationTimeout config" in {
      settings.ActivationTimeout must be === Duration(config.getMilliseconds("akka.camel.consumer.activation-timeout"), MILLISECONDS)
    }

    "have correct autoAck config" in {
      settings.AutoAck must be === config.getBoolean("akka.camel.consumer.auto-ack")
    }

    "have correct replyTimeout config" in {
      settings.ReplyTimeout must be === Duration(config.getMilliseconds("akka.camel.consumer.reply-timeout"), MILLISECONDS)
    }

    "have correct streamingCache config" in {
      settings.StreamingCache must be === config.getBoolean("akka.camel.streamingCache")
    }

    "have correct jmxStatistics config" in {
      settings.JmxStatistics must be === config.getBoolean("akka.camel.jmx")
    }

    "have correct body conversions config" in {
      val conversions = config.getConfig("akka.camel.conversions")

      conversions.getString("file") must be === "java.io.InputStream"
      conversions.entrySet.size must be === 1
    }
  }
}

