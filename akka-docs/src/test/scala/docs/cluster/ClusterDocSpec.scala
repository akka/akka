/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package scala.docs.cluster

import akka.cluster.Cluster
import akka.testkit.AkkaSpec
import docs.CompileOnlySpec

object ClusterDocSpec {

  val config =
    """
    akka.actor.provider = "cluster"
    akka.remote.netty.tcp.port = 0
    """
}

class ClusterDocSpec extends AkkaSpec(ClusterDocSpec.config) with CompileOnlySpec {

  "demonstrate leave" in compileOnlySpec {
    //#leave
    val cluster = Cluster(system)
    cluster.leave(cluster.selfAddress)
    //#leave
  }

  "demonstrate data center" in compileOnlySpec {
    {
      //#dcAccess
      val cluster = Cluster(system)
      // this node's data center
      val dc = cluster.selfDataCenter
      // all known data centers
      val allDc = cluster.state.allDataCenters
      // a specific member's data center
      val aMember = cluster.state.members.head
      val aDc = aMember.dataCenter
      //#dcAccess
    }
  }

}
