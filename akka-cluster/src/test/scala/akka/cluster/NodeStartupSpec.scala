/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import java.net.InetSocketAddress

import akka.testkit._
import akka.dispatch._
import akka.actor._
import akka.remote._

import com.typesafe.config._

class NodeStartupSpec extends AkkaSpec("""
  akka {
    loglevel = "DEBUG"
  }
  """) with ImplicitSender {

  var gossiper0: Gossiper = _
  var gossiper1: Gossiper = _
  var node0: ActorSystemImpl = _
  var node1: ActorSystemImpl = _

  try {
    node0 = ActorSystem("NodeStartupSpec", ConfigFactory
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

    "A first cluster node with a 'node-to-join' config set to empty string" must {
      "be in 'Joining' phase when started up" in {
        val members = gossiper0.latestGossip.members
        val joiningMember = members find (_.address.port.get == 5550)
        joiningMember must be('defined)
        joiningMember.get.status must be(MemberStatus.Joining)
      }

      "be a singleton cluster when started up" in {
        gossiper0.isSingletonCluster must be(true)
      }
    }

    node1 = ActorSystem("NodeStartupSpec", ConfigFactory
      .parseString("""
        akka {
          actor.provider = "akka.remote.RemoteActorRefProvider"
          remote.netty {
            hostname = localhost
            port=5551
          }
          cluster.node-to-join = "akka://NodeStartupSpec@localhost:5550"
        }""")
      .withFallback(system.settings.config))
      .asInstanceOf[ActorSystemImpl]
    val remote1 = node1.provider.asInstanceOf[RemoteActorRefProvider]
    gossiper1 = Gossiper(node1, remote1)

    "A second cluster node with a 'node-to-join' config defined" must {
      "join the other node cluster as 'Joining' when sending a Join command" in {
        Thread.sleep(1000) // give enough time for node1 to JOIN node0
        val members = gossiper0.latestGossip.members
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
    gossiper0.shutdown()
    node0.shutdown()
    gossiper1.shutdown()
    node1.shutdown()
  }
}
