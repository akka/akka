/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.cluster.changelisteners.nodeconnected

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.cluster._
import ChangeListener._

import java.util.concurrent._

object NodeConnectedChangeListenerMultiJvmSpec {
  var NrOfNodes = 2
}

class NodeConnectedChangeListenerMultiJvmNode1 extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import NodeConnectedChangeListenerMultiJvmSpec._

  "A NodeConnected change listener" must {

    "be invoked when a new node joins the cluster" in {
      System.getProperty("akka.cluster.nodename", "") must be("node1")
      System.getProperty("akka.cluster.port", "") must be("9991")

      val latch = new CountDownLatch(1)
      Cluster.node.register(new ChangeListener {
        override def nodeConnected(node: String, client: ClusterNode) {
          latch.countDown
        }
      })

      Cluster.node.shutdown()

      Cluster.barrier("start-node1", NrOfNodes) {
        Cluster.node.start()
      }

      Cluster.barrier("start-node2", NrOfNodes) {
        latch.await(5, TimeUnit.SECONDS) must be === true
      }

      Cluster.barrier("shutdown", NrOfNodes) {
        //        Cluster.node.shutdown()
      }
    }
  }

  override def beforeAll() = {
    Cluster.startLocalCluster()
  }

  override def afterAll() = {
    Cluster.shutdownLocalCluster()
  }
}

class NodeConnectedChangeListenerMultiJvmNode2 extends WordSpec with MustMatchers {
  import NodeConnectedChangeListenerMultiJvmSpec._

  "A NodeConnected change listener" must {

    "be invoked when a new node joins the cluster" in {
      System.getProperty("akka.cluster.nodename", "") must be("node2")
      System.getProperty("akka.cluster.port", "") must be("9992")

      Cluster.barrier("start-node1", NrOfNodes) {}

      Cluster.barrier("start-node2", NrOfNodes) {
        Cluster.node.start()
      }

      Cluster.barrier("shutdown", NrOfNodes) {
        //        Cluster.node.shutdown()
      }
    }
  }
}
