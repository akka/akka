/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import akka.actor.ActorSystem
import akka.testkit.SocketUtil
import com.typesafe.config.ConfigFactory
import org.jboss.netty.channel.ChannelException
import org.scalatest.{ Matchers, WordSpec }

class RemotingFailedToBindSpec extends WordSpec with Matchers {

  "an ActorSystem" must {
    "not start if port is taken" in {
      val port = SocketUtil.temporaryLocalPort()
      val config = ConfigFactory.parseString(s"""
           |akka {
           |  actor {
           |    provider = remote
           |  }
           |  remote {
           |    netty.tcp {
           |      hostname = "127.0.0.1"
           |      port = $port
           |    }
           |  }
           |}
       """.stripMargin)
      val as = ActorSystem("RemotingFailedToBindSpec", config)
      try {
        val ex = intercept[ChannelException] {
          ActorSystem("BindTest2", config)
        }
        ex.getMessage should startWith("Failed to bind")
      } finally {
        as.terminate()
      }
    }
  }
}
