/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.function.{ Function ⇒ JFunction }

import akka.actor.{ ExtendedActorSystem, InvalidActorNameException }
import akka.annotation.InternalApi
import akka.cluster.singleton.{ ClusterSingletonProxy, ClusterSingletonManager ⇒ OldSingletonManager }
import akka.actor.typed.Behavior.UntypedPropsBehavior
import akka.cluster.typed.{ Cluster, ClusterSingleton, ClusterSingletonImpl, ClusterSingletonSettings }
import akka.actor.typed.internal.adapter.ActorSystemAdapter
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Props }

/**
 * INTERNAL API:
 */
@InternalApi
private[akka] final class AdaptedClusterSingletonImpl(system: ActorSystem[_]) extends ClusterSingleton {
  require(system.isInstanceOf[ActorSystemAdapter[_]], "only adapted actor systems can be used for the typed cluster singleton")
  import ClusterSingletonImpl._
  import akka.actor.typed.scaladsl.adapter._

  private lazy val cluster = Cluster(system)
  private val untypedSystem = system.toUntyped.asInstanceOf[ExtendedActorSystem]

  private val proxies = new ConcurrentHashMap[String, ActorRef[_]]()

  override def spawn[A](
    behavior:           Behavior[A],
    singletonName:      String,
    props:              Props,
    settings:           ClusterSingletonSettings,
    terminationMessage: A) = {

    if (settings.shouldRunManager(cluster)) {
      val managerName = managerNameFor(singletonName)
      // start singleton on this node
      val untypedProps = behavior match {
        case u: UntypedPropsBehavior[_] ⇒ u.untypedProps(props) // PersistentBehavior
        case _                          ⇒ PropsAdapter(behavior, props)
      }
      try {
        untypedSystem.systemActorOf(
          OldSingletonManager.props(untypedProps, terminationMessage, settings.toManagerSettings(singletonName)),
          managerName)
      } catch {
        case ex: InvalidActorNameException if ex.getMessage.endsWith("is not unique!") ⇒
        // This is fine. We just wanted to make sure it is running and it already is
      }
    }

    val proxyCreator = new JFunction[String, ActorRef[_]] {
      def apply(singletonName: String): ActorRef[_] = {
        val proxyName = s"singletonProxy$singletonName"
        untypedSystem.systemActorOf(
          ClusterSingletonProxy.props(s"/system/${managerNameFor(singletonName)}", settings.toProxySettings(singletonName)),
          proxyName)
      }
    }

    proxies.computeIfAbsent(singletonName, proxyCreator).asInstanceOf[ActorRef[A]]
  }
}
