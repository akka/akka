/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.actor.ActorSystem
import akka.actor.ActorSystemImpl
import akka.remote.RemoteActorRefProvider
import akka.testkit.ImplicitSender
import akka.testkit.LongRunningTest
import com.typesafe.config.ConfigFactory

class NodeMembershipSpec extends ClusterSpec with ImplicitSender {
  val portPrefix = 7

  var node0: Cluster = _
  var node1: Cluster = _
  var node2: Cluster = _

  var system0: ActorSystemImpl = _
  var system1: ActorSystemImpl = _
  var system2: ActorSystemImpl = _

  try {
    "A set of connected cluster systems" must {
      "(when two systems) start gossiping to each other so that both systems gets the same gossip info" taggedAs LongRunningTest in {

        // ======= NODE 0 ========
        system0 = ActorSystem("system0", ConfigFactory
          .parseString("""
            akka {
              actor.provider = "akka.remote.RemoteActorRefProvider"
              remote.netty.port = %d550
          }""".format(portPrefix))
          .withFallback(system.settings.config))
          .asInstanceOf[ActorSystemImpl]
        val remote0 = system0.provider.asInstanceOf[RemoteActorRefProvider]
        node0 = Cluster(system0)

        // ======= NODE 1 ========
        system1 = ActorSystem("system1", ConfigFactory
          .parseString("""
            akka {
              actor.provider = "akka.remote.RemoteActorRefProvider"
              remote.netty.port = %d551
              cluster.node-to-join = "akka://system0@localhost:%d550"
            }""".format(portPrefix, portPrefix))
          .withFallback(system.settings.config))
          .asInstanceOf[ActorSystemImpl]
        val remote1 = system1.provider.asInstanceOf[RemoteActorRefProvider]
        node1 = Cluster(system1)

        // check cluster convergence
        awaitConvergence(node0 :: node1 :: Nil)

        val members0 = node0.latestGossip.members.toArray
        members0.size must be(2)
        members0(0).address.port.get must be(550.withPortPrefix)
        members0(0).status must be(MemberStatus.Up)
        members0(1).address.port.get must be(551.withPortPrefix)
        members0(1).status must be(MemberStatus.Up)

        val members1 = node1.latestGossip.members.toArray
        members1.size must be(2)
        members1(0).address.port.get must be(550.withPortPrefix)
        members1(0).status must be(MemberStatus.Up)
        members1(1).address.port.get must be(551.withPortPrefix)
        members1(1).status must be(MemberStatus.Up)
      }

      "(when three systems) start gossiping to each other so that both systems gets the same gossip info" taggedAs LongRunningTest ignore {

        // ======= NODE 2 ========
        system2 = ActorSystem("system2", ConfigFactory
          .parseString("""
            akka {
              actor.provider = "akka.remote.RemoteActorRefProvider"
              remote.netty.port = %d552
              cluster.node-to-join = "akka://system0@localhost:%d550"
            }""".format(portPrefix, portPrefix))
          .withFallback(system.settings.config))
          .asInstanceOf[ActorSystemImpl]
        val remote2 = system2.provider.asInstanceOf[RemoteActorRefProvider]
        node2 = Cluster(system2)

        awaitConvergence(node0 :: node1 :: node2 :: Nil)

        val members0 = node0.latestGossip.members.toArray
        val version = node0.latestGossip.version
        members0.size must be(3)
        members0(0).address.port.get must be(550.withPortPrefix)
        members0(0).status must be(MemberStatus.Up)
        members0(1).address.port.get must be(551.withPortPrefix)
        members0(1).status must be(MemberStatus.Up)
        members0(2).address.port.get must be(552.withPortPrefix)
        members0(2).status must be(MemberStatus.Up)

        val members1 = node1.latestGossip.members.toArray
        members1.size must be(3)
        members1(0).address.port.get must be(550.withPortPrefix)
        members1(0).status must be(MemberStatus.Up)
        members1(1).address.port.get must be(551.withPortPrefix)
        members1(1).status must be(MemberStatus.Up)
        members1(2).address.port.get must be(552.withPortPrefix)
        members1(2).status must be(MemberStatus.Up)

        val members2 = node2.latestGossip.members.toArray
        members2.size must be(3)
        members2(0).address.port.get must be(550.withPortPrefix)
        members2(0).status must be(MemberStatus.Up)
        members2(1).address.port.get must be(551.withPortPrefix)
        members2(1).status must be(MemberStatus.Up)
        members2(2).address.port.get must be(552.withPortPrefix)
        members2(2).status must be(MemberStatus.Up)
      }
    }
  } catch {
    case e: Exception ⇒
      e.printStackTrace
      fail(e.toString)
  }

  override def atTermination() {
    if (node0 ne null) node0.shutdown()
    if (system0 ne null) system0.shutdown()

    if (node1 ne null) node1.shutdown()
    if (system1 ne null) system1.shutdown()

    if (node2 ne null) node2.shutdown()
    if (system2 ne null) system2.shutdown()
  }
}
