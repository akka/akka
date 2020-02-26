/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.cluster.sharding.typed.scaladsl

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object ClusterEntitySetSpec {
  def config = ConfigFactory.parseString("""
      akka.actor.provider = cluster

      akka.remote.classic.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      akka.remote.artery.canonical.hostname = 127.0.0.1

      akka.cluster.jmx.multi-mbeans-in-same-jvm = on

      akka.cluster.sharding.number-of-shards = 10

      akka.coordinated-shutdown.terminate-actor-system = off
      akka.coordinated-shutdown.run-by-actor-system-terminate = off
      """)
}
class ClusterEntitySetSpec
    extends ScalaTestWithActorTestKit(ClusterEntitySetSpec.config)
    with AnyWordSpecLike
    with LogCapturing {}
