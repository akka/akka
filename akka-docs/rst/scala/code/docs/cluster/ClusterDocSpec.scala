/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.cluster

import akka.cluster.Cluster
import akka.testkit.AkkaSpec

object ClusterDocSpec {

  val config =
    """
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.netty.tcp.port = 0
    """
}

class ClusterDocSpec extends AkkaSpec(ClusterDocSpec.config) {

  "demonstrate leave" in {
    //#leave
    val cluster = Cluster(system)
    cluster.leave(cluster.selfAddress)
    //#leave
  }

}
