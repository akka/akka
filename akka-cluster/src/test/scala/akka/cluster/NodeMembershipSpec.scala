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

  var node0: Node = _
  var node1: Node = _
  var node2: Node = _

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
              remote.netty {
                hostname = localhost
                port=5550
              }
            }""")
          .withFallback(system.settings.config))
          .asInstanceOf[ActorSystemImpl]
        val remote0 = system0.provider.asInstanceOf[RemoteActorRefProvider]
        node0 = new Node(system0)

        // ======= NODE 1 ========
        system1 = ActorSystem("system1", ConfigFactory
          .parseString("""
            akka {
              actor.provider = "akka.remote.RemoteActorRefProvider"
              remote.netty {
                hostname = localhost
                port=5551
              }
              cluster.node-to-join = "akka://system0@localhost:5550"
            }""")
          .withFallback(system.settings.config))
          .asInstanceOf[ActorSystemImpl]
        val remote1 = system1.provider.asInstanceOf[RemoteActorRefProvider]
        node1 = new Node(system1)

        Thread.sleep(10.seconds.dilated.toMillis)

        // check cluster convergence
        node0.convergence must be('defined)
        node1.convergence must be('defined)

        val members0 = node0.latestGossip.members.toArray
        members0.size must be(2)
        members0(0).address.port.get must be(5550)
        members0(0).status must be(MemberStatus.Joining)
        members0(1).address.port.get must be(5551)
        members0(1).status must be(MemberStatus.Joining)

        val members1 = node1.latestGossip.members.toArray
        members1.size must be(2)
        members1(0).address.port.get must be(5550)
        members1(0).status must be(MemberStatus.Joining)
        members1(1).address.port.get must be(5551)
        members1(1).status must be(MemberStatus.Joining)
      }

      "(when three systems) start gossiping to each other so that both systems gets the same gossip info" taggedAs LongRunningTest in {

        // ======= NODE 2 ========
        system2 = ActorSystem("system2", ConfigFactory
          .parseString("""
            akka {
              actor.provider = "akka.remote.RemoteActorRefProvider"
              remote.netty {
                hostname = localhost
                port=5552
              }
              cluster.node-to-join = "akka://system0@localhost:5550"
            }""")
          .withFallback(system.settings.config))
          .asInstanceOf[ActorSystemImpl]
        val remote2 = system2.provider.asInstanceOf[RemoteActorRefProvider]
        node2 = new Node(system2)

        Thread.sleep(10.seconds.dilated.toMillis)

        // check cluster convergence
        node0.convergence must be('defined)
        node1.convergence must be('defined)
        node2.convergence must be('defined)

        val members0 = node0.latestGossip.members.toArray
        val version = node0.latestGossip.version
        members0.size must be(3)
        members0(0).address.port.get must be(5550)
        members0(0).status must be(MemberStatus.Joining)
        members0(1).address.port.get must be(5551)
        members0(1).status must be(MemberStatus.Joining)
        members0(2).address.port.get must be(5552)
        members0(2).status must be(MemberStatus.Joining)

        val members1 = node1.latestGossip.members.toArray
        members1.size must be(3)
        members1(0).address.port.get must be(5550)
        members1(0).status must be(MemberStatus.Joining)
        members1(1).address.port.get must be(5551)
        members1(1).status must be(MemberStatus.Joining)
        members1(2).address.port.get must be(5552)
        members1(2).status must be(MemberStatus.Joining)

        val members2 = node2.latestGossip.members.toArray
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
    if (node0 ne null) node0.shutdown()
    if (system0 ne null) system0.shutdown()

    if (node1 ne null) node1.shutdown()
    if (system1 ne null) system1.shutdown()

    if (node2 ne null) node2.shutdown()
    if (system2 ne null) system2.shutdown()
  }
}
