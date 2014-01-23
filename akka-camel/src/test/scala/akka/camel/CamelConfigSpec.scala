/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel
import org.scalatest.Matchers
import org.scalatest.WordSpec
import akka.actor.ActorSystem
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit._
import akka.testkit.TestKit
import akka.util.Helpers.ConfigOps

class CamelConfigSpec extends WordSpec with Matchers {

  val (settings, config) = {
    val system = ActorSystem("CamelConfigSpec")
    val result = (CamelExtension(system).settings, system.settings.config)
    TestKit.shutdownActorSystem(system)
    result
  }
  "CamelConfigSpec" must {
    "have correct activationTimeout config" in {
      settings.ActivationTimeout should equal(config.getMillisDuration("akka.camel.consumer.activation-timeout"))
    }

    "have correct autoAck config" in {
      settings.AutoAck should equal(config.getBoolean("akka.camel.consumer.auto-ack"))
    }

    "have correct replyTimeout config" in {
      settings.ReplyTimeout should equal(config.getMillisDuration("akka.camel.consumer.reply-timeout"))
    }

    "have correct streamingCache config" in {
      settings.StreamingCache should equal(config.getBoolean("akka.camel.streamingCache"))
    }

    "have correct jmxStatistics config" in {
      settings.JmxStatistics should equal(config.getBoolean("akka.camel.jmx"))
    }

    "have correct body conversions config" in {
      val conversions = config.getConfig("akka.camel.conversions")

      conversions.getString("file") should equal("java.io.InputStream")
      conversions.entrySet.size should equal(1)
    }
  }
}

