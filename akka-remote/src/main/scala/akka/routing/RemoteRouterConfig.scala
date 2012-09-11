/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import com.typesafe.config.ConfigFactory
import akka.actor.ActorContext
import akka.actor.ActorRef
import akka.actor.Deploy
import akka.actor.InternalActorRef
import akka.actor.Props
import akka.ConfigurationException
import akka.remote.RemoteScope
import akka.actor.AddressFromURIString
import akka.actor.SupervisorStrategy
import akka.actor.Address
import scala.collection.JavaConverters._
import java.util.concurrent.atomic.AtomicInteger
import java.lang.IllegalStateException
import akka.actor.ActorCell

/**
 * [[akka.routing.RouterConfig]] implementation for remote deployment on defined
 * target nodes. Delegates other duties to the local [[akka.routing.RouterConfig]],
 * which makes it possible to mix this with the built-in routers such as
 * [[akka.routing.RoundRobinRouter]] or custom routers.
 */
case class RemoteRouterConfig(local: RouterConfig, nodes: Iterable[Address]) extends RouterConfig {

  def this(local: RouterConfig, nodes: java.lang.Iterable[Address]) = this(local, nodes.asScala)
  def this(local: RouterConfig, nodes: Array[Address]) = this(local, nodes: Iterable[Address])

  override def createRouteeProvider(context: ActorContext) = new RemoteRouteeProvider(nodes, context, resizer)

  override def createRoute(routeeProps: Props, routeeProvider: RouteeProvider): Route = {
    local.createRoute(routeeProps, routeeProvider)
  }

  override def createActor(): Router = local.createActor()

  override def supervisorStrategy: SupervisorStrategy = local.supervisorStrategy

  override def routerDispatcher: String = local.routerDispatcher

  override def resizer: Option[Resizer] = local.resizer

  override def withFallback(other: RouterConfig): RouterConfig = other match {
    case RemoteRouterConfig(local: RemoteRouterConfig, nodes) ⇒ throw new IllegalStateException(
      "RemoteRouterConfig is not allowed to wrap a RemoteRouterConfig")
    case RemoteRouterConfig(local, nodes) ⇒ copy(local = this.local.withFallback(local))
    case _                                ⇒ copy(local = this.local.withFallback(other))
  }
}

/**
 * Factory and registry for routees of the router.
 * Deploys new routees on the specified `nodes`, round-robin.
 *
 * Routee paths may not be combined with remote target nodes.
 */
class RemoteRouteeProvider(nodes: Iterable[Address], _context: ActorContext, _resizer: Option[Resizer])
  extends RouteeProvider(_context, _resizer) {

  // need this iterator as instance variable since Resizer may call createRoutees several times
  private val nodeAddressIter: Iterator[Address] = Stream.continually(nodes).flatten.iterator
  // need this counter as instance variable since Resizer may call createRoutees several times
  private val childNameCounter = new AtomicInteger

  override def createRoutees(props: Props, nrOfInstances: Int, routees: Iterable[String]): IndexedSeq[ActorRef] =
    (nrOfInstances, routees, nodes) match {
      case (_, _, Nil) ⇒ throw new ConfigurationException("Must specify list of remote target.nodes for [%s]"
        format context.self.path.toString)

      case (n, Nil, ys) ⇒
        IndexedSeq.empty[ActorRef] ++ (for (i ← 1 to nrOfInstances) yield {
          val name = "c" + childNameCounter.incrementAndGet
          val deploy = Deploy("", ConfigFactory.empty(), props.routerConfig, RemoteScope(nodeAddressIter.next))

          // attachChild means that the provider will treat this call as if possibly done out of the wrong
          // context and use RepointableActorRef instead of LocalActorRef. Seems like a slightly sub-optimal
          // choice in a corner case (and hence not worth fixing).
          context.asInstanceOf[ActorCell].attachChild(props.withDeploy(deploy), name, systemService = false)
        })

      case (_, xs, _) ⇒ throw new ConfigurationException("Remote target.nodes can not be combined with routees for [%s]"
        format context.self.path.toString)
    }
}

