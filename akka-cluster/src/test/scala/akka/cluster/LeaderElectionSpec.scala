/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit._
import akka.dispatch._
import akka.actor._
import akka.remote._
import akka.util.duration._

import com.typesafe.config._

import java.net.InetSocketAddress

class LeaderElectionSpec extends ClusterSpec with ImplicitSender {

  var node1: Cluster = _
  var node2: Cluster = _
  var node3: Cluster = _

  var system1: ActorSystemImpl = _
  var system2: ActorSystemImpl = _
  var system3: ActorSystemImpl = _

  try {
    "A cluster of three nodes" must {

      // ======= NODE 1 ========
      system1 = ActorSystem("system1", ConfigFactory
        .parseString("""
          akka {
            remote.netty.port = 5550
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      node1 = Cluster(system1)
      val address1 = node1.remoteAddress

      // ======= NODE 2 ========
      system2 = ActorSystem("system2", ConfigFactory
        .parseString("""
          akka {
            remote.netty.port = 5551
            cluster.node-to-join = "akka://system1@localhost:5550"
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      node2 = Cluster(system2)
      val address2 = node2.remoteAddress

      // ======= NODE 3 ========
      system3 = ActorSystem("system3", ConfigFactory
        .parseString("""
          akka {
            remote.netty.port = 5552
            cluster.node-to-join = "akka://system1@localhost:5550"
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      node3 = Cluster(system3)
      val address3 = node3.remoteAddress

      "be able to 'elect' a single leader" taggedAs LongRunningTest in {

        println("Give the system time to converge...")
        awaitConvergence(node1 :: node2 :: node3 :: Nil)

        // check leader
        node1.isLeader must be(true)
        node2.isLeader must be(false)
        node3.isLeader must be(false)
      }

      "be able to 're-elect' a single leader after leader has left" taggedAs LongRunningTest in {

        // shut down system1 - the leader
        node1.shutdown()
        system1.shutdown()

        // user marks node1 as DOWN
        node2.scheduleNodeDown(address1)

        println("Give the system time to converge...")
        Thread.sleep(10.seconds.dilated.toMillis)
        awaitConvergence(node2 :: node3 :: Nil)

        // check leader
        node2.isLeader must be(true)
        node3.isLeader must be(false)
      }

      "be able to 're-elect' a single leader after leader has left (again, leaving a single node)" taggedAs LongRunningTest in {

        // shut down system1 - the leader
        node2.shutdown()
        system2.shutdown()

        // user marks node2 as DOWN
        node3.scheduleNodeDown(address2)

        println("Give the system time to converge...")
        Thread.sleep(10.seconds.dilated.toMillis)
        awaitConvergence(node3 :: Nil)

        // check leader
        node3.isLeader must be(true)
      }
    }
  } catch {
    case e: Exception ⇒
      e.printStackTrace
      fail(e.toString)
  }

  override def atTermination() {
    if (node1 ne null) node1.shutdown()
    if (system1 ne null) system1.shutdown()

    if (node2 ne null) node2.shutdown()
    if (system2 ne null) system2.shutdown()

    if (node3 ne null) node3.shutdown()
    if (system3 ne null) system3.shutdown()
  }
}
