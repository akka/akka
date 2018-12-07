/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.scaladsl

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object DistributedDataSpec {

  val config = ConfigFactory.parseString("""
    akka.actor.provider = "cluster"
    akka.remote.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    akka.remote.artery.canonical.hostname = 127.0.0.1""")
}

class DistributedDataSpec extends ScalaTestWithActorTestKit(DistributedDataSpec.config) with WordSpecLike {
  "DistributedData" must {
    "have the prefixed replicator name" in {
      DistributedData(system).replicatorName(Some("typed")) should ===("typedDdataReplicator")
    }
  }
}
