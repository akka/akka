/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata

import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import org.scalatest.{ BeforeAndAfterAll, WordSpecLike }

object DistributedDataSpec {

  val config = ConfigFactory.parseString("""
    akka.actor.provider = "cluster"
    akka.remote.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    akka.remote.artery.canonical.hostname = 127.0.0.1""")
}

class DistributedDataSpec extends AkkaSpec(DistributedDataSpec.config)
  with WordSpecLike with BeforeAndAfterAll {

  override protected def atStartup(): Unit =
    DistributedData(system).isTerminated shouldBe false

  override protected def afterTermination(): Unit =
    DistributedData(system).isTerminated shouldBe true

  "DistributedData" must {
    "have the default replicator name" in {
      DistributedData(system).replicatorName(None) should ===("ddataReplicator")
    }
    "have the prefixed replicator name" in {
      DistributedData(system).replicatorName(Some("other")) should ===("otherDdataReplicator")
    }
  }
}
