/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
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
import akka.remote.RemoteAddressExtractor

/**
 * [[akka.routing.RouterConfig]] implementation for remote deployment on defined
 * target nodes. Delegates other duties to the local [[akka.routing.RouterConfig]],
 * which makes it possible to mix this with the built-in routers such as
 * [[akka.routing.RoundRobinRouter]] or custom routers.
 */
class RemoteRouterConfig(local: RouterConfig, nodes: Iterable[String]) extends RouterConfig {

  override protected[akka] def createRouteeProvider(ref: RoutedActorRef, context: ActorContext) =
    new RemoteRouteeProvider(nodes, ref, context, resizer)

  override def createRoute(routeeProps: Props, routeeProvider: RouteeProvider): Route = {
    local.createRoute(routeeProps, routeeProvider)
  }

  override def createActor(): Router = local.createActor()

  override def resizer: Option[Resizer] = local.resizer

}

/**
 * Factory and registry for routees of the router.
 * Deploys new routees on the specified `nodes`, round-robin.
 *
 * Routee paths may not be combined with remote target nodes.
 */
class RemoteRouteeProvider(nodes: Iterable[String], _ref: RoutedActorRef, _context: ActorContext, _resizer: Option[Resizer])
  extends RouteeProvider(_ref, _context, _resizer) {

  // need this iterator as instance variable since Resizer may call createRoutees several times
  private val nodeAddressIter = {
    val nodeAddresses = nodes map {
      case RemoteAddressExtractor(a) ⇒ a
      case x                         ⇒ throw new ConfigurationException("unparseable remote node " + x)
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
          val deploy = Deploy("", ConfigFactory.empty(), None, props.routerConfig, RemoteScope(nodeAddressIter.next))
          impl.provider.actorOf(impl, props, context.self.asInstanceOf[InternalActorRef], context.self.path / name, false, Some(deploy))
        })

      case (_, xs, _) ⇒ throw new ConfigurationException("Remote target.nodes can not be combined with routees for [%s]"
        format context.self.path.toString)
    }
}

