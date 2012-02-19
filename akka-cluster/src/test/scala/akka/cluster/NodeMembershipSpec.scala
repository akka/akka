/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import java.net.InetSocketAddress

import akka.testkit._
import akka.dispatch._
import akka.actor._
import akka.remote._
import akka.util.duration._

import com.typesafe.config._

class NodeMembershipSpec extends AkkaSpec("""
  akka {
    loglevel = "INFO"
  }
  """) with ImplicitSender {

  var gossiper0: Gossiper = _
  var gossiper1: Gossiper = _
  var gossiper2: Gossiper = _

  var node0: ActorSystemImpl = _
  var node1: ActorSystemImpl = _
  var node2: ActorSystemImpl = _

  try {
    "A set of connected cluster nodes" must {
      "(when two nodes) start gossiping to each other so that both nodes gets the same gossip info" taggedAs LongRunningTest in {

        // ======= NODE 0 ========
        node0 = ActorSystem("node0", ConfigFactory
          .parseString("""
            akka {
              actor.provider = "akka.remote.RemoteActorRefProvider"
              remote.netty {
                hostname = localhost
                port=5550
              }
            }""")
          .withFallback(system.settings.config))
          .asInstanceOf[ActorSystemImpl]
        val remote0 = node0.provider.asInstanceOf[RemoteActorRefProvider]
        gossiper0 = Gossiper(node0, remote0)

        // ======= NODE 1 ========
        node1 = ActorSystem("node1", ConfigFactory
          .parseString("""
            akka {
              actor.provider = "akka.remote.RemoteActorRefProvider"
              remote.netty {
                hostname = localhost
                port=5551
              }
              cluster.node-to-join = "akka://node0@localhost:5550"
            }""")
          .withFallback(system.settings.config))
          .asInstanceOf[ActorSystemImpl]
        val remote1 = node1.provider.asInstanceOf[RemoteActorRefProvider]
        gossiper1 = Gossiper(node1, remote1)

        Thread.sleep(10.seconds.dilated.toMillis)

        // check cluster convergence
        gossiper0.convergence must be('defined)
        gossiper1.convergence must be('defined)

        val members0 = gossiper0.latestGossip.members.toArray
        members0.size must be(2)
        members0(0).address.port.get must be(5550)
        members0(0).status must be(MemberStatus.Joining)
        members0(1).address.port.get must be(5551)
        members0(1).status must be(MemberStatus.Joining)

        val members1 = gossiper1.latestGossip.members.toArray
        members1.size must be(2)
        members1(0).address.port.get must be(5550)
        members1(0).status must be(MemberStatus.Joining)
        members1(1).address.port.get must be(5551)
        members1(1).status must be(MemberStatus.Joining)
      }

      "(when three nodes) start gossiping to each other so that both nodes gets the same gossip info" taggedAs LongRunningTest in {

        // ======= NODE 2 ========
        node2 = ActorSystem("node2", ConfigFactory
          .parseString("""
            akka {
              actor.provider = "akka.remote.RemoteActorRefProvider"
              remote.netty {
                hostname = localhost
                port=5552
              }
              cluster.node-to-join = "akka://node0@localhost:5550"
            }""")
          .withFallback(system.settings.config))
          .asInstanceOf[ActorSystemImpl]
        val remote2 = node2.provider.asInstanceOf[RemoteActorRefProvider]
        gossiper2 = Gossiper(node2, remote2)

        Thread.sleep(10.seconds.dilated.toMillis)

        // check cluster convergence
        gossiper0.convergence must be('defined)
        gossiper1.convergence must be('defined)
        gossiper2.convergence must be('defined)

        val members0 = gossiper0.latestGossip.members.toArray
        val version = gossiper0.latestGossip.version
        members0.size must be(3)
        members0(0).address.port.get must be(5550)
        members0(0).status must be(MemberStatus.Joining)
        members0(1).address.port.get must be(5551)
        members0(1).status must be(MemberStatus.Joining)
        members0(2).address.port.get must be(5552)
        members0(2).status must be(MemberStatus.Joining)

        val members1 = gossiper1.latestGossip.members.toArray
        members1.size must be(3)
        members1(0).address.port.get must be(5550)
        members1(0).status must be(MemberStatus.Joining)
        members1(1).address.port.get must be(5551)
        members1(1).status must be(MemberStatus.Joining)
        members1(2).address.port.get must be(5552)
        members1(2).status must be(MemberStatus.Joining)

        val members2 = gossiper2.latestGossip.members.toArray
        members2.size must be(3)
        members2(0).address.port.get must be(5550)
        members2(0).status must be(MemberStatus.Joining)
        members2(1).address.port.get must be(5551)
        members2(1).status must be(MemberStatus.Joining)
        members2(2).address.port.get must be(5552)
        members2(2).status must be(MemberStatus.Joining)
      }
    }
  } catch {
    case e: Exception ⇒
      e.printStackTrace
      fail(e.toString)
  }

  override def atTermination() {
    if (gossiper0 ne null) gossiper0.shutdown()
    if (node0 ne null) node0.shutdown()

    if (gossiper1 ne null) gossiper1.shutdown()
    if (node1 ne null) node1.shutdown()

    if (gossiper2 ne null) gossiper2.shutdown()
    if (node2 ne null) node2.shutdown()
  }
}
