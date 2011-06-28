/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.cluster.api.changelisteners.nodeconnected

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.cluster._
import ChangeListener._
import Cluster._

import java.util.concurrent._

object NodeConnectedChangeListenerMultiJvmSpec {
  var NrOfNodes = 2
}

class NodeConnectedChangeListenerMultiJvmNode1 extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import NodeConnectedChangeListenerMultiJvmSpec._

  "A NodeConnected change listener" must {

    "be invoked when a new node joins the cluster" in {
      val latch = new CountDownLatch(1)
      node.register(new ChangeListener {
        override def nodeConnected(node: String, client: ClusterNode) {
          latch.countDown
        }
      })

      barrier("start-node1", NrOfNodes) {
        node.start()
      }

      barrier("start-node2", NrOfNodes) {
        latch.await(5, TimeUnit.SECONDS) must be === true
      }

      node.shutdown()
    }
  }

  override def beforeAll() = {
    startLocalCluster()
  }

  override def afterAll() = {
    shutdownLocalCluster()
  }
}

class NodeConnectedChangeListenerMultiJvmNode2 extends WordSpec with MustMatchers {
  import NodeConnectedChangeListenerMultiJvmSpec._

  "A NodeConnected change listener" must {

    "be invoked when a new node joins the cluster" in {
      barrier("start-node1", NrOfNodes) {}

      barrier("start-node2", NrOfNodes) {
        node.start()
      }

      node.shutdown()
    }
  }
}
