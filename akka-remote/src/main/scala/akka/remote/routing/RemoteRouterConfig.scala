/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.routing

import akka.routing.{ Route, Router, RouterConfig, RouteeProvider, Resizer }
import com.typesafe.config.ConfigFactory
import akka.actor.ActorContext
import akka.actor.Deploy
import akka.actor.Props
import akka.actor.SupervisorStrategy
import akka.actor.Address
import akka.actor.ActorCell
import akka.ConfigurationException
import akka.remote.RemoteScope
import akka.japi.Util.immutableSeq
import scala.collection.immutable
import java.util.concurrent.atomic.AtomicInteger
import java.lang.IllegalStateException

/**
 * [[akka.routing.RouterConfig]] implementation for remote deployment on defined
 * target nodes. Delegates other duties to the local [[akka.routing.RouterConfig]],
 * which makes it possible to mix this with the built-in routers such as
 * [[akka.routing.RoundRobinRouter]] or custom routers.
 */
@SerialVersionUID(1L)
final case class RemoteRouterConfig(local: RouterConfig, nodes: Iterable[Address]) extends RouterConfig {

  def this(local: RouterConfig, nodes: java.lang.Iterable[Address]) = this(local, immutableSeq(nodes))
  def this(local: RouterConfig, nodes: Array[Address]) = this(local, nodes: Iterable[Address])

  override def createRouteeProvider(context: ActorContext, routeeProps: Props) =
    new RemoteRouteeProvider(nodes, context, routeeProps, resizer)

  override def createRoute(routeeProvider: RouteeProvider): Route = {
    local.createRoute(routeeProvider)
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
final class RemoteRouteeProvider(nodes: Iterable[Address], _context: ActorContext, _routeeProps: Props, _resizer: Option[Resizer])
  extends RouteeProvider(_context, _routeeProps, _resizer) {

  if (nodes.isEmpty)
    throw new ConfigurationException("Must specify list of remote target.nodes for [%s]" format context.self.path.toString)

  // need this iterator as instance variable since Resizer may call createRoutees several times
  private val nodeAddressIter: Iterator[Address] = Stream.continually(nodes).flatten.iterator
  // need this counter as instance variable since Resizer may call createRoutees several times
  private val childNameCounter = new AtomicInteger

  override def registerRouteesFor(paths: immutable.Iterable[String]): Unit =
    throw new ConfigurationException("Remote target.nodes can not be combined with routees for [%s]"
      format context.self.path.toString)

  override def createRoutees(nrOfInstances: Int): Unit = {
    val refs = immutable.IndexedSeq.fill(nrOfInstances) {
      val name = "c" + childNameCounter.incrementAndGet
      val deploy = Deploy(config = ConfigFactory.empty(), routerConfig = routeeProps.routerConfig,
        scope = RemoteScope(nodeAddressIter.next))

      // attachChild means that the provider will treat this call as if possibly done out of the wrong
      // context and use RepointableActorRef instead of LocalActorRef. Seems like a slightly sub-optimal
      // choice in a corner case (and hence not worth fixing).
      context.asInstanceOf[ActorCell].attachChild(routeeProps.withDeploy(deploy), name, systemService = false)
    }
    registerRoutees(refs)
  }
}

