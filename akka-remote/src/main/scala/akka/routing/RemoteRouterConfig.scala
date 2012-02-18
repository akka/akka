/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import com.typesafe.config.ConfigFactory
import akka.actor.ActorContext
import akka.actor.ActorRef
import akka.actor.ActorSystemImpl
import akka.actor.Deploy
import akka.actor.InternalActorRef
import akka.actor.Props
import akka.config.ConfigurationException
import akka.remote.RemoteScope
import akka.actor.AddressExtractor
import akka.actor.SupervisorStrategy

/**
 * [[akka.routing.RouterConfig]] implementation for remote deployment on defined
 * target nodes. Delegates other duties to the local [[akka.routing.RouterConfig]],
 * which makes it possible to mix this with the built-in routers such as
 * [[akka.routing.RoundRobinRouter]] or custom routers.
 */
case class RemoteRouterConfig(local: RouterConfig, nodes: Iterable[String]) extends RouterConfig {

  override def createRouteeProvider(context: ActorContext) = new RemoteRouteeProvider(nodes, context, resizer)

  override def createRoute(routeeProps: Props, routeeProvider: RouteeProvider): Route = {
    local.createRoute(routeeProps, routeeProvider)
  }

  override def createActor(): Router = local.createActor()

  override def supervisorStrategy: SupervisorStrategy = local.supervisorStrategy

  override def routerDispatcher: String = local.routerDispatcher

  override def resizer: Option[Resizer] = local.resizer

  override def withFallback(other: RouterConfig): RouterConfig = other match {
    case RemoteRouterConfig(local, nodes) ⇒ copy(local = this.local.withFallback(local))
    case _                                ⇒ this
  }
}

/**
 * Factory and registry for routees of the router.
 * Deploys new routees on the specified `nodes`, round-robin.
 *
 * Routee paths may not be combined with remote target nodes.
 */
class RemoteRouteeProvider(nodes: Iterable[String], _context: ActorContext, _resizer: Option[Resizer])
  extends RouteeProvider(_context, _resizer) {

  // need this iterator as instance variable since Resizer may call createRoutees several times
  private val nodeAddressIter = {
    val nodeAddresses = nodes map {
      case AddressExtractor(a) ⇒ a
      case x                   ⇒ throw new ConfigurationException("unparseable remote node " + x)
    }
    Stream.continually(nodeAddresses).flatten.iterator
  }

  override def createRoutees(props: Props, nrOfInstances: Int, routees: Iterable[String]): IndexedSeq[ActorRef] =
    (nrOfInstances, routees, nodes) match {
      case (_, _, Nil) ⇒ throw new ConfigurationException("Must specify list of remote target.nodes for [%s]"
        format context.self.path.toString)

      case (n, Nil, ys) ⇒
        val impl = context.system.asInstanceOf[ActorSystemImpl] //TODO ticket #1559
        IndexedSeq.empty[ActorRef] ++ (for (i ← 1 to nrOfInstances) yield {
          val name = "c" + i
          val deploy = Deploy("", ConfigFactory.empty(), props.routerConfig, RemoteScope(nodeAddressIter.next))
          impl.provider.actorOf(impl, props, context.self.asInstanceOf[InternalActorRef], context.self.path / name, false, Some(deploy), false)
        })

      case (_, xs, _) ⇒ throw new ConfigurationException("Remote target.nodes can not be combined with routees for [%s]"
        format context.self.path.toString)
    }
}

