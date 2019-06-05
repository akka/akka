/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import akka.actor.Actor
import akka.actor.ActorSystemImpl
import akka.actor.AddressFromURIString
import akka.actor.InternalActorRef
import akka.actor.Props
import akka.routing.FromConfig
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object RemoteFeaturesSpec {

  val remote: Config = ConfigFactory.parseString("""
    akka.actor.provider = remote
    akka.remote.artery.canonical.port = 0
    akka.log-dead-letters-during-shutdown = off
    """)

  // no cluster, with remote watcher & deployer enabled
  val unsafe: Config = ConfigFactory.parseString("akka.remote.use-unsafe-remote-features-without-cluster = on").withFallback(remote)

  val remoteSystem = "remoteSystem"
  val remoteHost = "hostcat"
  val remoteAddressStr = s"akka://$remoteSystem@$remoteHost:2552"
  val remoteAddress = AddressFromURIString(remoteAddressStr)

  val groupRouterConfig: Config =
    ConfigFactory.parseString(s"""
      akka.actor.deployment {
        /service2 {
           remote = "$remoteAddressStr"
           router = round-robin-group
           routees.paths = [
             "$remoteAddressStr/user/service2/w1",
             "$remoteAddressStr/user/service2/w2",
             "$remoteAddressStr/user/service2/w3"
           ]
        }
      }
      """)

  val poolRouterConfig: Config =
    ConfigFactory.parseString(s"""
      akka.actor.deployment {
        /service {
          remote = "$remoteAddressStr"
          router = round-robin-pool
          nr-of-instances = 3
        }
      }
      """)

  case object Ping
  case object RouteePing
  case object Pong
  case object RouteePong
  class Parent(routerType: String) extends Actor {

    val router = {
      val p = Props(new Worker)
      val props = FromConfig.props(p)
      if (routerType == "group") (1 to 3).map(n => context.actorOf(p, "w" + n))
      context.actorOf(props, name = "router")
    }

    override def receive: Receive = {
      case Ping       => sender() ! Pong
      case RouteePing => router.forward(RouteePing)
    }
  }
  class Worker extends Actor {
    override def receive: Receive = {
      case RouteePing => sender() ! RouteePong
    }
  }
}

/** See the remote watcher specs for watcher additional tests. */
abstract class RemoteFeaturesSpec(c: Config) extends AkkaSpec(c) with ImplicitSender {

  protected val provider = RARP(system).provider

  protected val sys = system.asInstanceOf[ActorSystemImpl]
}

class GroupRouterDisableRemoteFeaturesWithoutClusterSpec
    extends RemoteFeaturesSpec(RemoteFeaturesSpec.groupRouterConfig.withFallback(RemoteFeaturesSpec.remote)) {

  "Remote deploy without Cluster" must {

    "have expected actoRef, deployer and group router behavior" in {
      import RemoteFeaturesSpec._

      val deployment = provider.deployer.lookup(List("service2"))
      deployment.forall(_.scope == RemoteScope(remoteAddress)) shouldBe true

      val parent = system.actorOf(Props(new Parent("group")), "service2")
      parent ! Ping
      expectMsg(Pong)

      (1 to 3).foreach { n =>
        parent ! RouteePing
        expectMsg(RouteePong)
        lastSender.path.name.startsWith("w" + n)
      }

      provider.actorOf(
        sys,
        Props(new Parent("group")),
        parent.asInstanceOf[InternalActorRef],
        parent.path,
        systemService = true,
        deploy = None, //TODO Some(remoteDeploy),
        lookupDeploy = true,
        async = false) ! RouteePing
      expectMsg(RouteePong)
    }
  }
}

class PoolRouterDisableRemoteFeaturesWithoutClusterSpec
    extends RemoteFeaturesSpec(RemoteFeaturesSpec.poolRouterConfig.withFallback(RemoteFeaturesSpec.remote)) {
  "Remote deploy without Cluster" must {

    "have expected actoRef, deployer and pool router behavior" in {}
  }
}
class GroupRouterEnableRemoteFeaturesWithoutClusterSpec
    extends RemoteFeaturesSpec(RemoteFeaturesSpec.groupRouterConfig.withFallback(RemoteFeaturesSpec.unsafe)) {
  "Remote deploy without Cluster" must {

    "have expected actoRef, deployer and group router behavior" in {
      import RemoteFeaturesSpec._

      // TODO using deploy never receives Pong
      val deployment = provider.deployer.lookup(List("service2"))
      deployment.forall(_.scope == RemoteScope(remoteAddress)) shouldBe true

      val parent = system.actorOf(Props(new Parent("group")), "service2")
      parent ! Ping
      expectMsg(Pong)

      (1 to 3).foreach { n =>
        parent ! RouteePing
        expectMsg(RouteePong)
        lastSender.path.name.startsWith("w" + n)
      }

      provider.actorOf(
        sys,
        Props(new Parent("group")),
        parent.asInstanceOf[InternalActorRef],
        parent.path,
        systemService = true,
        deploy = None, //TODO Some(remoteDeploy),
        lookupDeploy = true,
        async = false) ! RouteePing
      expectMsg(RouteePong)
    }
  }
}
class PoolRouterEnableRemoteFeaturesWithoutClusterSpec
    extends RemoteFeaturesSpec(RemoteFeaturesSpec.poolRouterConfig.withFallback(RemoteFeaturesSpec.unsafe)) {

  "Remote deploy without Cluster" must {

    "have expected actoRef, deployer and pool router behavior" in {}
  }
}
