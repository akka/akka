/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata

import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import org.scalatest.{ BeforeAndAfterAll, WordSpecLike }

object ReplicatorSettingsSpec {

  val config = ConfigFactory.parseString("""
    akka.actor.provider = "cluster"
    akka.remote.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    akka.remote.artery.canonical.hostname = 127.0.0.1""")
}

class ReplicatorSettingsSpec extends AkkaSpec(ReplicatorSettingsSpec.config) with WordSpecLike with BeforeAndAfterAll {

  "DistributedData" must {
    "have the default replicator name" in {
      ReplicatorSettings.name(system, None) should ===("ddataReplicator")
    }
    "have the prefixed replicator name" in {
      ReplicatorSettings.name(system, Some("other")) should ===("otherDdataReplicator")
    }
  }
}
