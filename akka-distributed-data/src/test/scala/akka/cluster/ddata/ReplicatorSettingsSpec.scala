/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike

import akka.testkit.AkkaSpec

object ReplicatorSettingsSpec {

  val config = ConfigFactory.parseString("""
    akka.actor.provider = "cluster"
    akka.remote.classic.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    akka.remote.artery.canonical.hostname = 127.0.0.1""")
}

class ReplicatorSettingsSpec
    extends AkkaSpec(ReplicatorSettingsSpec.config)
    with AnyWordSpecLike
    with BeforeAndAfterAll {

  "DistributedData" must {
    "have the default replicator name" in {
      ReplicatorSettings.name(system, None) should ===("ddataReplicator")
    }
    "have the prefixed replicator name" in {
      ReplicatorSettings.name(system, Some("other")) should ===("otherDdataReplicator")
    }
  }
}
