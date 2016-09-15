/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.testkit.SocketUtil
import com.typesafe.config.ConfigFactory

object CompressionIntegrationSpec {
  // need the port before systems are started
  val portB = SocketUtil.temporaryServerAddress("localhost", udp = true).getPort

  val commonConfig = ConfigFactory.parseString(s"""
     akka {
       loglevel = INFO

       actor {
         provider = "akka.remote.RemoteActorRefProvider"

         serializers {
           test-message = "akka.remote.artery.compress.TestMessageSerializer"
         }
         serialization-bindings {
           "akka.remote.artery.compress.TestMessage" = test-message
         }
       }
       remote.artery.enabled = on
       remote.artery.canonical.hostname = localhost
       remote.artery.canonical.port = 0
       remote.artery.advanced.handshake-timeout = 10s

       remote.artery.advanced.compression {
         actor-refs.advertisement-interval = 3 seconds
         manifests.advertisement-interval = 3 seconds
       }

     }
  """)

  val configB = ConfigFactory.parseString(s"akka.remote.artery.canonical.port = $portB")
    .withFallback(commonConfig)
}
