/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.api.changelisteners.nodeconnected

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.cluster._
import ChangeListener._
import Cluster._
import akka.cluster.LocalCluster._

import java.util.concurrent._

object NodeConnectedChangeListenerMultiJvmSpec {
  var NrOfNodes = 2
}

class NodeConnectedChangeListenerMultiJvmNode1 extends MasterClusterTestNode {
  import NodeConnectedChangeListenerMultiJvmSpec._

  val testNodes = NrOfNodes

  "A NodeConnected change listener" must {

    "be invoked when a new node joins the cluster" in {
      val latch = new CountDownLatch(1)
      node.register(new ChangeListener {
        override def nodeConnected(node: String, client: ClusterNode) {
          latch.countDown
        }
      })

      barrier("start-node1", NrOfNodes) {
        Cluster.node.start()
      }

      barrier("start-node2", NrOfNodes) {
        latch.await(5, TimeUnit.SECONDS) must be === true
      }

      node.shutdown()
    }
  }
}

class NodeConnectedChangeListenerMultiJvmNode2 extends ClusterTestNode {
  import NodeConnectedChangeListenerMultiJvmSpec._

  "A NodeConnected change listener" must {

    "be invoked when a new node joins the cluster" in {
      barrier("start-node1", NrOfNodes).await()

      barrier("start-node2", NrOfNodes) {
        Cluster.node.start()
      }

      node.shutdown()
    }
  }
}
