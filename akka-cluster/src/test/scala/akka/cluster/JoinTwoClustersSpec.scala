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

class JoinTwoClustersSpec extends ClusterSpec("akka.cluster.failure-detector.threshold = 5") with ImplicitSender {

  var node1: Cluster = _
  var node2: Cluster = _
  var node3: Cluster = _
  var node4: Cluster = _
  var node5: Cluster = _
  var node6: Cluster = _

  var system1: ActorSystemImpl = _
  var system2: ActorSystemImpl = _
  var system3: ActorSystemImpl = _
  var system4: ActorSystemImpl = _
  var system5: ActorSystemImpl = _
  var system6: ActorSystemImpl = _

  try {
    "Three different clusters (A, B and C)" must {

      // ======= NODE 1 ========
      system1 = ActorSystem("system1", ConfigFactory
        .parseString("""
          akka {
            remote.netty.port = 5551
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      node1 = Cluster(system1)

      // ======= NODE 2 ========
      system2 = ActorSystem("system2", ConfigFactory
        .parseString("""
          akka {
            remote.netty.port = 5552
            cluster.node-to-join = "akka://system1@localhost:5551"
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      node2 = Cluster(system2)

      // ======= NODE 3 ========
      system3 = ActorSystem("system3", ConfigFactory
        .parseString("""
          akka {
            remote.netty.port = 5553
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      node3 = Cluster(system3)

      // ======= NODE 4 ========
      system4 = ActorSystem("system4", ConfigFactory
        .parseString("""
          akka {
            remote.netty.port = 5554
            cluster.node-to-join = "akka://system3@localhost:5553"
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      node4 = Cluster(system4)

      // ======= NODE 5 ========
      system5 = ActorSystem("system5", ConfigFactory
        .parseString("""
          akka {
            remote.netty.port = 5555
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      node5 = Cluster(system5)

      // ======= NODE 6 ========
      system6 = ActorSystem("system6", ConfigFactory
        .parseString("""
          akka {
            remote.netty.port = 5556
            cluster.node-to-join = "akka://system5@localhost:5555"
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      node6 = Cluster(system6)

      "be able to 'elect' a single leader after joining (A -> B)" taggedAs LongRunningTest in {

        println("Give the system time to converge...")
        awaitConvergence(node1 :: node2 :: node3 :: node4 :: node5 :: node6 :: Nil)

        // check leader
        node1.isLeader must be(true)
        node2.isLeader must be(false)
        node3.isLeader must be(true)
        node4.isLeader must be(false)
        node5.isLeader must be(true)
        node6.isLeader must be(false)

        // join
        node4.join(node1.remoteAddress)
        //node1.scheduleNodeJoin(node4.remoteAddress)

        println("Give the system time to converge...")
        Thread.sleep(10.seconds.dilated.toMillis)
        awaitConvergence(node1 :: node2 :: node3 :: node4 :: node5 :: node6 :: Nil)

        // check leader
        node1.isLeader must be(true)
        node2.isLeader must be(false)
        node3.isLeader must be(false)
        node4.isLeader must be(false)
        node5.isLeader must be(true)
        node6.isLeader must be(false)
      }

      "be able to 'elect' a single leader after joining (C -> A + B)" taggedAs LongRunningTest in {
        // join
        node4.join(node5.remoteAddress)
        //node5.scheduleNodeJoin(node4.remoteAddress)

        println("Give the system time to converge...")
        Thread.sleep(10.seconds.dilated.toMillis)
        awaitConvergence(node1 :: node2 :: node3 :: node4 :: node5 :: node6 :: Nil)

        // check leader
        node1.isLeader must be(true)
        node2.isLeader must be(false)
        node3.isLeader must be(false)
        node4.isLeader must be(false)
        node5.isLeader must be(false)
        node6.isLeader must be(false)
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

    if (node4 ne null) node4.shutdown()
    if (system4 ne null) system4.shutdown()

    if (node5 ne null) node5.shutdown()
    if (system5 ne null) system5.shutdown()

    if (node6 ne null) node6.shutdown()
    if (system6 ne null) system6.shutdown()
  }
}
