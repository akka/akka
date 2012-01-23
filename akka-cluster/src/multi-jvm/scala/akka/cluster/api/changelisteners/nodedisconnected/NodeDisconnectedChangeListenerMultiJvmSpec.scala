/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.api.changelisteners.nodedisconnected

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.cluster._
import ChangeListener._
import Cluster._
import akka.cluster.LocalCluster._

import java.util.concurrent._

object NodeDisconnectedChangeListenerMultiJvmSpec {
  var NrOfNodes = 2
}

class NodeDisconnectedChangeListenerMultiJvmNode1 extends MasterClusterTestNode {
  import NodeDisconnectedChangeListenerMultiJvmSpec._

  val testNodes = NrOfNodes

  "A NodeDisconnected change listener" must {

    "be invoked when a new node leaves the cluster" in {
      val latch = new CountDownLatch(1)
      node.register(new ChangeListener {
        override def nodeDisconnected(node: String, client: ClusterNode) {
          latch.countDown
        }
      })

      barrier("start-node1", NrOfNodes) {
        Cluster.node.start()
      }

      barrier("start-node2", NrOfNodes).await()

      latch.await(10, TimeUnit.SECONDS) must be === true

      node.shutdown()
    }
  }
}

class NodeDisconnectedChangeListenerMultiJvmNode2 extends ClusterTestNode {
  import NodeDisconnectedChangeListenerMultiJvmSpec._

  "A NodeDisconnected change listener" must {

    "be invoked when a new node leaves the cluster" in {
      barrier("start-node1", NrOfNodes).await()

      barrier("start-node2", NrOfNodes) {
        Cluster.node.start()
      }

      node.shutdown()
    }
  }
}
