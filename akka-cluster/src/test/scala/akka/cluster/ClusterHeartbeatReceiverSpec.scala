/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.actor.Props
import akka.cluster.ClusterHeartbeatSender.{ Heartbeat, HeartbeatRsp }
import akka.testkit.{ AkkaSpec, ImplicitSender }

class ClusterHeartbeatReceiverSpec extends AkkaSpec("""
    akka.actor.provider = cluster 
  """.stripMargin) with ImplicitSender {
  "ClusterHeartbeatReceiver" should {
    "responsd to heartbeats with the same sequenceNr and sendTime" in {
      val heartBeater = system.actorOf(Props(new ClusterHeartbeatReceiver(() => Cluster(system))))
      heartBeater ! Heartbeat(Cluster(system).selfAddress, 1, 2)
      expectMsg(HeartbeatRsp(Cluster(system).selfUniqueAddress, 1, 2))
    }
  }
}
