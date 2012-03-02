/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import java.net.InetSocketAddress

import akka.testkit._
import akka.dispatch._
import akka.actor._
import akka.remote._
import akka.util.duration._

import com.typesafe.config._

class NodeStartupSpec extends AkkaSpec("""
  akka {
    loglevel = "INFO"
    actor.provider = akka.remote.RemoteActorRefProvider
    remote.netty.hostname = localhost
  }
  """) with ImplicitSender {

  var node0: Node = _
  var node1: Node = _
  var system0: ActorSystemImpl = _
  var system1: ActorSystemImpl = _

  try {
    "A first cluster node with a 'node-to-join' config set to empty string (singleton cluster)" must {
      system0 = ActorSystem("system0", ConfigFactory
        .parseString("akka.remote.netty.port=5550")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      val remote0 = system0.provider.asInstanceOf[RemoteActorRefProvider]
      node0 = Node(system0)

      "be a singleton cluster when started up" in {
        Thread.sleep(1.seconds.dilated.toMillis)
        node0.isSingletonCluster must be(true)
      }

      "be in 'Up' phase when started up" in {
        val members = node0.latestGossip.members
        val joiningMember = members find (_.address.port.get == 5550)
        joiningMember must be('defined)
        joiningMember.get.status must be(MemberStatus.Joining)
      }
    }

    "A second cluster node with a 'node-to-join' config defined" must {
      "join the other node cluster as 'Joining' when sending a Join command" in {
        system1 = ActorSystem("system1", ConfigFactory
          .parseString("""
            akka {
              remote.netty.port=5551
              cluster.node-to-join = "akka://system0@localhost:5550"
            }""")
          .withFallback(system.settings.config))
          .asInstanceOf[ActorSystemImpl]
        val remote1 = system1.provider.asInstanceOf[RemoteActorRefProvider]
        node1 = Node(system1)

        Thread.sleep(1.seconds.dilated.toMillis) // give enough time for node1 to JOIN node0
        val members = node0.latestGossip.members
        val joiningMember = members find (_.address.port.get == 5551)
        joiningMember must be('defined)
        joiningMember.get.status must be(MemberStatus.Joining)
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
  }
}
