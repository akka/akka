/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.scaladsl

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

class ReplicatorSpec extends ScalaTestWithActorTestKit with WordSpecLike with LogCapturing {
  "Replicator" must {
    "have the prefixed replicator name" in {
      ReplicatorSettings.name(system) should ===("typedDdataReplicator")
    }
  }
}
