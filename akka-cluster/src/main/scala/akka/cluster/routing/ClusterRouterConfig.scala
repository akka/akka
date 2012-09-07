/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.routing

import java.lang.IllegalStateException
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.SortedSet
import com.typesafe.config.ConfigFactory
import akka.ConfigurationException
import akka.actor.Actor
import akka.actor.ActorContext
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
import akka.routing.RemoteRouterConfig
import akka.actor.RootActorPath

/**
 * [[akka.routing.RouterConfig]] implementation for deployment on cluster nodes.
 * Delegates other duties to the local [[akka.routing.RouterConfig]],
 * which makes it possible to mix this with the built-in routers such as
 * [[akka.routing.RoundRobinRouter]] or custom routers.
 */
case class ClusterRouterConfig(local: RouterConfig, settings: ClusterRouterSettings) extends RouterConfig {

  override def createRouteeProvider(context: ActorContext, routeeProps: Props) =
    new ClusterRouteeProvider(context, routeeProps, resizer, settings)

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
    case ClusterRouterConfig(_: RemoteRouterConfig, _) ⇒ throw new IllegalStateException(
      "ClusterRouterConfig is not allowed to wrap a RemoteRouterConfig")
    case ClusterRouterConfig(_: ClusterRouterConfig, _) ⇒ throw new IllegalStateException(
      "ClusterRouterConfig is not allowed to wrap a ClusterRouterConfig")
    case ClusterRouterConfig(local, _) ⇒ copy(local = this.local.withFallback(local))
    case _                             ⇒ copy(local = this.local.withFallback(other))
  }
}

case class ClusterRouterSettings(
  totalInstances: Int,
  maxInstancesPerNode: Int,
  deployOnOwnNode: Boolean,
  routeesPath: String) {

  def isRouteesPathDefined: Boolean = ((routeesPath ne null) && routeesPath != "")
}

/**
 * INTERNAL API
 *
 * Factory and registry for routees of the router.
 * Deploys new routees on the cluster nodes.
 */
private[akka] class ClusterRouteeProvider(
  _context: ActorContext,
  _routeeProps: Props,
  _resizer: Option[Resizer],
  settings: ClusterRouterSettings)
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

    for (i ← 1 to settings.totalInstances; target ← selectDeploymentTarget) {
      val ref =
        if (settings.isRouteesPathDefined) {
          context.actorFor(target.toString + settings.routeesPath)
        } else {
          val name = "c" + childNameCounter.incrementAndGet
          val deploy = Deploy("", ConfigFactory.empty(), routeeProps.routerConfig, RemoteScope(target))
          impl.provider.actorOf(impl, routeeProps, context.self.asInstanceOf[InternalActorRef], context.self.path / name,
            systemService = false, Some(deploy), lookupDeploy = false, async = false)
        }
      // must register each one, since registered routees are used in selectDeploymentTarget
      registerRoutees(Some(ref))
    }
  }

  private[routing] def createRoutees(): Unit = createRoutees(settings.totalInstances)

  private def selectDeploymentTarget: Option[Address] = {
    val currentRoutees = routees
    val currentNodes = availbleNodes
    if (currentRoutees.size >= settings.totalInstances) {
      None
    } else if (currentNodes.isEmpty && settings.deployOnOwnNode) {
      // use my own node, cluster information not updated yet
      Some(cluster.selfAddress)
    } else {
      val numberOfRouteesPerNode: Map[Address, Int] =
        Map.empty[Address, Int] ++ currentNodes.toSeq.map(_ -> 0) ++
          currentRoutees.groupBy(fullAddress).map {
            case (address, refs) ⇒ address -> refs.size
          }

      val (address, count) = numberOfRouteesPerNode.minBy(_._2)
      if (count < settings.maxInstancesPerNode) Some(address) else None
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
  private[routing] var availbleNodes: SortedSet[Address] = cluster.readView.members.collect {
    case m if isAvailble(m) ⇒ m.address
  }

  private[routing] def isAvailble(m: Member): Boolean = {
    m.status == MemberStatus.Up && (settings.deployOnOwnNode || m.address != cluster.selfAddress)
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
      routeeProvider.availbleNodes = s.members.collect { case m if routeeProvider.isAvailble(m) ⇒ m.address }
      routeeProvider.createRoutees()

    case m: MemberEvent if routeeProvider.isAvailble(m.member) ⇒
      routeeProvider.availbleNodes += m.member.address
      // createRoutees will create routees based on
      // totalInstances and maxInstancesPerNode
      routeeProvider.createRoutees()

    case other: MemberEvent ⇒
      // other events means that it is no longer interesting, such as
      // MemberJoined, MemberLeft, MemberExited, MemberUnreachable, MemberRemoved
      val address = other.member.address
      routeeProvider.availbleNodes -= address

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
 *   new RoundRobinRouter(0), 10, 2, true), "myrouter");
 * ]]]
 *
 * Corresponding for Scala API is found in [[akka.cluster.routing.ClusterRouterProps]].
 *
 */
object ClusterRouterPropsDecorator {
  def decorate(props: Props, router: RouterConfig, totalInstances: Int, maxInstancesPerNode: Int,
               deployOnOwnNode: Boolean): Props = {
    props.withClusterRouter(router, totalInstances, maxInstancesPerNode, deployOnOwnNode)
  }

  def decorate(props: Props, router: RouterConfig, totalInstances: Int, maxInstancesPerNode: Int,
               deployOnOwnNode: Boolean, routeesPath: String): Props = {
    props.withClusterRouter(router, totalInstances, maxInstancesPerNode, deployOnOwnNode, routeesPath)
  }
}

