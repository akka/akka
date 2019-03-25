/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.function.{ Function => JFunction }

import akka.actor.typed.internal.{ PoisonPill, PoisonPillInterceptor }
import akka.actor.{ ExtendedActorSystem, InvalidActorNameException }
import akka.annotation.InternalApi
import akka.cluster.singleton.{ ClusterSingletonProxy, ClusterSingletonManager => OldSingletonManager }
import akka.cluster.typed.{ Cluster, ClusterSingleton, ClusterSingletonImpl, ClusterSingletonSettings }
import akka.actor.typed.internal.adapter.ActorSystemAdapter
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.cluster.ClusterSettings.DataCenter
import akka.cluster.typed

/**
 * INTERNAL API:
 */
@InternalApi
private[akka] final class AdaptedClusterSingletonImpl(system: ActorSystem[_]) extends ClusterSingleton {
  require(
    system.isInstanceOf[ActorSystemAdapter[_]],
    "only adapted actor systems can be used for the typed cluster singleton")

  import ClusterSingletonImpl._
  import akka.actor.typed.scaladsl.adapter._

  private lazy val cluster = Cluster(system)
  private val untypedSystem = system.toUntyped.asInstanceOf[ExtendedActorSystem]

  private val proxies = new ConcurrentHashMap[(String, Option[DataCenter]), ActorRef[_]]()

  override def init[M](singleton: typed.SingletonActor[M]): ActorRef[M] = {
    val settings = singleton.settings match {
      case None    => ClusterSingletonSettings(system)
      case Some(s) => s
    }
    def poisonPillInterceptor(behv: Behavior[M]): Behavior[M] = {
      singleton.stopMessage match {
        case Some(_) => behv
        case None    => Behaviors.intercept(new PoisonPillInterceptor[M])(behv)
      }
    }

    if (settings.shouldRunManager(cluster)) {
      val managerName = managerNameFor(singleton.name)
      // start singleton on this node
      val untypedProps = PropsAdapter(poisonPillInterceptor(singleton.behavior), singleton.props)
      try {
        untypedSystem.systemActorOf(
          OldSingletonManager.props(
            untypedProps,
            singleton.stopMessage.getOrElse(PoisonPill),
            settings.toManagerSettings(singleton.name)),
          managerName)
      } catch {
        case ex: InvalidActorNameException if ex.getMessage.endsWith("is not unique!") =>
        // This is fine. We just wanted to make sure it is running and it already is
      }
    }

    getProxy(singleton.name, settings)
  }

  private def getProxy[T](name: String, settings: ClusterSingletonSettings): ActorRef[T] = {
    val proxyCreator = new JFunction[(String, Option[DataCenter]), ActorRef[_]] {
      def apply(singletonNameAndDc: (String, Option[DataCenter])): ActorRef[_] = {
        println("Creating for " + singletonNameAndDc)
        val (singletonName, _) = singletonNameAndDc
        val proxyName = s"singletonProxy$singletonName-${settings.dataCenter.getOrElse("no-dc")}"
        untypedSystem.systemActorOf(
          ClusterSingletonProxy
            .props(s"/system/${managerNameFor(singletonName)}", settings.toProxySettings(singletonName)),
          proxyName)
      }
    }
    proxies.computeIfAbsent((name, settings.dataCenter), proxyCreator).asInstanceOf[ActorRef[T]]
  }
}
