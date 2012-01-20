/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.api.leader.election

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.cluster._
import ChangeListener._
import Cluster._
import akka.cluster.LocalCluster._

import java.util.concurrent._

object LeaderElectionMultiJvmSpec {
  var NrOfNodes = 2
}
/*
class LeaderElectionMultiJvmNode1 extends MasterClusterTestNode {
  import LeaderElectionMultiJvmSpec._

  val testNodes = NrOfNodes

  "A cluster" must {

    "be able to elect a single leader in the cluster and perform re-election if leader resigns" in {

      barrier("start-node1", NrOfNodes) {
        Cluster.node.start()
      }
      node.isLeader must be === true

      barrier("start-node2", NrOfNodes) {
      }
      node.isLeader must be === true

      barrier("stop-node1", NrOfNodes) {
        node.resign()
      }
    }
  }
}

class LeaderElectionMultiJvmNode2 extends ClusterTestNode {
  import LeaderElectionMultiJvmSpec._

  "A cluster" must {

    "be able to elect a single leader in the cluster and perform re-election if leader resigns" in {

      barrier("start-node1", NrOfNodes) {
      }
      node.isLeader must be === false

      barrier("start-node2", NrOfNodes) {
        Cluster.node.start()
      }
      node.isLeader must be === false

      barrier("stop-node1", NrOfNodes) {
      }
      Thread.sleep(1000) // wait for re-election

      node.isLeader must be === true
    }
  }
}
*/
