/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.routing

import java.lang.IllegalStateException
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.SortedSet
import com.typesafe.config.ConfigFactory

import akka.ConfigurationException
import akka.actor.ActorRef
import akka.actor.ActorSystemImpl
import akka.actor.Address
import akka.actor.Deploy
import akka.actor.InternalActorRef
import akka.actor.Props
import akka.actor.SupervisorStrategy
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.remote.RemoteScope
import akka.routing.Destination
import akka.routing.Resizer
import akka.routing.Route
import akka.routing.RouteeProvider
import akka.routing.Router
import akka.routing.RouterConfig
import akka.routing.RouterContext

/**
 * [[akka.routing.RouterConfig]] implementation for deployment on cluster nodes.
 * Delegates other duties to the local [[akka.routing.RouterConfig]],
 * which makes it possible to mix this with the built-in routers such as
 * [[akka.routing.RoundRobinRouter]] or custom routers.
 */
case class ClusterRouterConfig(local: RouterConfig, totalInstances: Int, maxInstancesPerNode: Int) extends RouterConfig {

  override def createRouteeProvider(context: RouterContext, routeeProps: Props) =
    new ClusterRouteeProvider(context, routeeProps, resizer, totalInstances, maxInstancesPerNode)

  override def createRoute(routeeProvider: RouteeProvider): Route = {
    val localRoute = local.createRoute(routeeProvider)

    // Intercept ClusterDomainEvent and route them to the ClusterRouterActor
    ({
      case (sender, message: ClusterDomainEvent) ⇒ Seq(Destination(sender, routeeProvider.context.self))
    }: Route) orElse localRoute
  }

  override def createActor(): Router = new ClusterRouterActor

  override def supervisorStrategy: SupervisorStrategy = local.supervisorStrategy

  override def routerDispatcher: String = local.routerDispatcher

  override def resizer: Option[Resizer] = local.resizer

  override def withFallback(other: RouterConfig): RouterConfig = other match {
    case ClusterRouterConfig(local, _, _) ⇒ copy(local = this.local.withFallback(local))
    case _                                ⇒ copy(local = this.local.withFallback(other))
  }
}

/**
 * INTERNAL API
 *
 * Factory and registry for routees of the router.
 * Deploys new routees on the cluster nodes.
 */
private[akka] class ClusterRouteeProvider(
  _context: RouterContext,
  _routeeProps: Props,
  _resizer: Option[Resizer],
  totalInstances: Int,
  maxInstancesPerNode: Int)
  extends RouteeProvider(_context, _routeeProps, _resizer) {

  // need this counter as instance variable since Resizer may call createRoutees several times
  private val childNameCounter = new AtomicInteger

  override def registerRouteesFor(paths: Iterable[String]): Unit =
    throw new ConfigurationException("Cluster deployment can not be combined with routees for [%s]"
      format context.self.path.toString)

  /**
   * Note that nrOfInstances is ignored for cluster routers, instead
   * the `totalInstances` parameter is used. That is the same when
   * using config to define `nr-of-instances`, but when defining the
   * router programatically or using [[akka.routing.Resizer]] they
   * might be different. `totalInstances` is the relevant parameter
   * to use for cluster routers.
   */
  override def createRoutees(nrOfInstances: Int): Unit = {
    val impl = context.system.asInstanceOf[ActorSystemImpl] //TODO ticket #1559

    for (i ← 1 to totalInstances; target ← selectDeploymentTarget) {
      val name = "c" + childNameCounter.incrementAndGet
      val deploy = Deploy("", ConfigFactory.empty(), routeeProps.routerConfig, RemoteScope(target))
      var ref = impl.provider.actorOf(impl, routeeProps, context.self.asInstanceOf[InternalActorRef], context.self.path / name,
        systemService = false, Some(deploy), lookupDeploy = false, async = false)
      // must register each one, since registered routees are used in selectDeploymentTarget
      registerRoutees(Some(ref))
    }
  }

  private[routing] def createRoutees(): Unit = createRoutees(totalInstances)

  private def selectDeploymentTarget: Option[Address] = {
    val currentRoutees = routees
    val currentNodes = upNodes
    if (currentRoutees.size >= totalInstances) {
      None
    } else if (currentNodes.isEmpty) {
      // use my own node, cluster information not updated yet
      Some(cluster.selfAddress)
    } else {
      val numberOfRouteesPerNode: Map[Address, Int] =
        Map.empty[Address, Int] ++ currentNodes.toSeq.map(_ -> 0) ++
          currentRoutees.groupBy(fullAddress).map {
            case (address, refs) ⇒ address -> refs.size
          }

      val (address, count) = numberOfRouteesPerNode.minBy(_._2)
      if (count < maxInstancesPerNode) Some(address) else None
    }
  }

  private[routing] def cluster: Cluster = Cluster(context.system)

  /**
   * Fills in self address for local ActorRef
   */
  private[routing] def fullAddress(actorRef: ActorRef): Address = actorRef.path.address match {
    case Address(_, _, None, None) ⇒ cluster.selfAddress
    case a                         ⇒ a
  }

  import Member.addressOrdering
  @volatile
  private[routing] var upNodes: SortedSet[Address] = cluster.readView.members.collect {
    case m if m.status == MemberStatus.Up ⇒ m.address
  }

}

/**
 * INTERNAL API
 * The router actor, subscribes to cluster events.
 */
private[akka] class ClusterRouterActor extends Router {

  override def preStart(): Unit = cluster.subscribe(self, classOf[ClusterDomainEvent])
  override def postStop(): Unit = cluster.unsubscribe(self)

  // lazy to not interfere with RoutedActorCell initialization
  lazy val routeeProvider: ClusterRouteeProvider = ref.routeeProvider match {
    case x: ClusterRouteeProvider ⇒ x
    case _ ⇒
      throw new IllegalStateException("ClusterRouteeProvider must be used together with [%s]".format(this.getClass))
  }

  def cluster: Cluster = routeeProvider.cluster

  def fullAddress(actorRef: ActorRef): Address = routeeProvider.fullAddress(actorRef)

  override def routerReceive: Receive = {
    case s: CurrentClusterState ⇒
      import Member.addressOrdering
      routeeProvider.upNodes = s.members.collect { case m if m.status == MemberStatus.Up ⇒ m.address }

    case MemberUp(m) ⇒
      routeeProvider.upNodes += m.address
      // createRoutees will create routees based on
      // totalInstances and maxInstancesPerNode
      routeeProvider.createRoutees()

    case other: MemberEvent ⇒
      // other events means that it is no longer interesting, such as
      // MemberJoined, MemberLeft, MemberExited, MemberUnreachable, MemberRemoved
      val address = other.member.address
      routeeProvider.upNodes -= address

      // unregister routees that live on that node
      val affectedRoutes = routeeProvider.routees.filter(fullAddress(_) == address)
      routeeProvider.unregisterRoutees(affectedRoutes)

      // createRoutees will not create more than createRoutees and maxInstancesPerNode
      // this is useful when totalInstances < upNodes.size
      routeeProvider.createRoutees()

  }
}

/**
 * Sugar to define cluster aware router programatically.
 * Usage Java API:
 * [[[
 * context.actorOf(ClusterRouterPropsDecorator.decorate(new Props(MyActor.class),
 *   new RoundRobinRouter(0), 10, 2), "myrouter");
 * ]]]
 *
 * Corresponding for Scala API is found in [[akka.cluster.routing.ClusterRouterProps]].
 *
 */
object ClusterRouterPropsDecorator {
  def decorate(props: Props, router: RouterConfig, totalInstances: Int, maxInstancesPerNode: Int): Props =
    props.withClusterRouter(router, totalInstances, maxInstancesPerNode)
}

