/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.routing

import java.lang.IllegalStateException
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable
import com.typesafe.config.ConfigFactory
import akka.ConfigurationException
import akka.actor.ActorContext
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.Deploy
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
import akka.remote.routing.RemoteRouterConfig
import akka.actor.RootActorPath
import akka.actor.ActorCell
import akka.actor.RelativeActorPath
import scala.annotation.tailrec

/**
 * [[akka.routing.RouterConfig]] implementation for deployment on cluster nodes.
 * Delegates other duties to the local [[akka.routing.RouterConfig]],
 * which makes it possible to mix this with the built-in routers such as
 * [[akka.routing.RoundRobinRouter]] or custom routers.
 */
@SerialVersionUID(1L)
final case class ClusterRouterConfig(local: RouterConfig, settings: ClusterRouterSettings) extends RouterConfig {

  override def createRouteeProvider(context: ActorContext, routeeProps: Props) =
    new ClusterRouteeProvider(context, routeeProps, resizer, settings)

  override def createRoute(routeeProvider: RouteeProvider): Route = {
    val localRoute = local.createRoute(routeeProvider)

    // Intercept ClusterDomainEvent and route them to the ClusterRouterActor
    ({
      case (sender, message: ClusterDomainEvent) ⇒ List(Destination(sender, routeeProvider.context.self))
    }: Route) orElse localRoute
  }

  override def createActor(): Router = new ClusterRouterActor

  override def supervisorStrategy: SupervisorStrategy = local.supervisorStrategy

  override def routerDispatcher: String = local.routerDispatcher

  override def resizer: Option[Resizer] = local.resizer

  override def stopRouterWhenAllRouteesRemoved: Boolean = false

  override def withFallback(other: RouterConfig): RouterConfig = other match {
    case ClusterRouterConfig(_: RemoteRouterConfig, _) ⇒ throw new IllegalStateException(
      "ClusterRouterConfig is not allowed to wrap a RemoteRouterConfig")
    case ClusterRouterConfig(_: ClusterRouterConfig, _) ⇒ throw new IllegalStateException(
      "ClusterRouterConfig is not allowed to wrap a ClusterRouterConfig")
    case ClusterRouterConfig(local, _) ⇒ copy(local = this.local.withFallback(local))
    case _                             ⇒ copy(local = this.local.withFallback(other))
  }
}

object ClusterRouterSettings {
  /**
   * Settings for create and deploy of the routees
   */
  def apply(totalInstances: Int, maxInstancesPerNode: Int, allowLocalRoutees: Boolean, useRole: Option[String]): ClusterRouterSettings =
    new ClusterRouterSettings(totalInstances, maxInstancesPerNode, routeesPath = "", allowLocalRoutees, useRole)

  /**
   * Settings for remote deployment of the routees, allowed to use routees on own node
   */
  def apply(totalInstances: Int, maxInstancesPerNode: Int, useRole: Option[String]): ClusterRouterSettings =
    apply(totalInstances, maxInstancesPerNode, allowLocalRoutees = true, useRole)

  /**
   * Settings for lookup of the routees
   */
  def apply(totalInstances: Int, routeesPath: String, allowLocalRoutees: Boolean, useRole: Option[String]): ClusterRouterSettings =
    new ClusterRouterSettings(totalInstances, maxInstancesPerNode = 1, routeesPath, allowLocalRoutees, useRole)

  /**
   * Settings for lookup of the routees, allowed to use routees on own node
   */
  def apply(totalInstances: Int, routeesPath: String, useRole: Option[String]): ClusterRouterSettings =
    apply(totalInstances, routeesPath, allowLocalRoutees = true, useRole)

  def useRoleOption(role: String): Option[String] = role match {
    case null | "" ⇒ None
    case _         ⇒ Some(role)
  }
}

/**
 * `totalInstances` of cluster router must be > 0
 * `maxInstancesPerNode` of cluster router must be > 0
 * `maxInstancesPerNode` of cluster router must be 1 when routeesPath is defined
 */
@SerialVersionUID(1L)
case class ClusterRouterSettings private[akka] (
  totalInstances: Int,
  maxInstancesPerNode: Int,
  routeesPath: String,
  allowLocalRoutees: Boolean,
  useRole: Option[String]) {

  /**
   * Java API: Settings for create and deploy of the routees
   */
  def this(totalInstances: Int, maxInstancesPerNode: Int, allowLocalRoutees: Boolean, useRole: String) =
    this(totalInstances, maxInstancesPerNode, routeesPath = "", allowLocalRoutees,
      ClusterRouterSettings.useRoleOption(useRole))

  /**
   * Java API: Settings for lookup of the routees
   */
  def this(totalInstances: Int, routeesPath: String, allowLocalRoutees: Boolean, useRole: String) =
    this(totalInstances, maxInstancesPerNode = 1, routeesPath, allowLocalRoutees,
      ClusterRouterSettings.useRoleOption(useRole))

  if (totalInstances <= 0) throw new IllegalArgumentException("totalInstances of cluster router must be > 0")
  if (maxInstancesPerNode <= 0) throw new IllegalArgumentException("maxInstancesPerNode of cluster router must be > 0")
  if (isRouteesPathDefined && maxInstancesPerNode != 1)
    throw new IllegalArgumentException("maxInstancesPerNode of cluster router must be 1 when routeesPath is defined")

  val routeesPathElements: immutable.Iterable[String] = routeesPath match {
    case RelativeActorPath(elements) ⇒ elements
    case _ ⇒
      throw new IllegalArgumentException("routeesPath [%s] is not a valid relative actor path" format routeesPath)
  }

  def isRouteesPathDefined: Boolean = (routeesPath ne null) && routeesPath != ""

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

  override def registerRouteesFor(paths: immutable.Iterable[String]): Unit =
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
    @tailrec
    def doCreateRoutees(): Unit = selectDeploymentTarget match {
      case None ⇒ // done
      case Some(target) ⇒
        val ref =
          if (settings.isRouteesPathDefined) {
            context.actorFor(RootActorPath(target) / settings.routeesPathElements)
          } else {
            val name = "c" + childNameCounter.incrementAndGet
            val deploy = Deploy(config = ConfigFactory.empty(), routerConfig = routeeProps.routerConfig,
              scope = RemoteScope(target))
            context.asInstanceOf[ActorCell].attachChild(routeeProps.withDeploy(deploy), name, systemService = false)
          }
        // must register each one, since registered routees are used in selectDeploymentTarget
        registerRoutees(List(ref))

        // recursion until all created
        doCreateRoutees()
    }

    doCreateRoutees()
  }

  private[routing] def createRoutees(): Unit = createRoutees(settings.totalInstances)

  private def selectDeploymentTarget: Option[Address] = {
    val currentRoutees = routees
    val currentNodes = availableNodes
    if (currentNodes.isEmpty || currentRoutees.size >= settings.totalInstances) {
      None
    } else {
      // find the node with least routees
      val numberOfRouteesPerNode: Map[Address, Int] =
        currentRoutees.foldLeft(currentNodes.map(_ -> 0).toMap.withDefaultValue(0)) { (acc, x) ⇒
          val address = fullAddress(x)
          acc + (address -> (acc(address) + 1))
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

  private[routing] def availableNodes: immutable.SortedSet[Address] = {
    import Member.addressOrdering
    val currentNodes = nodes
    if (currentNodes.isEmpty && settings.allowLocalRoutees && satisfiesRole(cluster.selfRoles))
      //use my own node, cluster information not updated yet
      immutable.SortedSet(cluster.selfAddress)
    else
      currentNodes
  }

  @volatile
  private[routing] var nodes: immutable.SortedSet[Address] = {
    import Member.addressOrdering
    cluster.readView.members.collect {
      case m if isAvailable(m) ⇒ m.address
    }
  }

  private[routing] def isAvailable(m: Member): Boolean =
    m.status == MemberStatus.Up &&
      satisfiesRole(m.roles) &&
      (settings.allowLocalRoutees || m.address != cluster.selfAddress)

  private def satisfiesRole(memberRoles: Set[String]): Boolean = settings.useRole match {
    case None    ⇒ true
    case Some(r) ⇒ memberRoles.contains(r)
  }

}

/**
 * INTERNAL API
 * The router actor, subscribes to cluster events.
 */
private[akka] class ClusterRouterActor extends Router {

  // re-subscribe when restart
  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[MemberEvent])
    cluster.subscribe(self, classOf[UnreachableMember])
  }
  override def postStop(): Unit = cluster.unsubscribe(self)

  // lazy to not interfere with RoutedActorCell initialization
  lazy val routeeProvider: ClusterRouteeProvider = ref.routeeProvider match {
    case x: ClusterRouteeProvider ⇒ x
    case _ ⇒ throw new IllegalStateException(
      "ClusterRouteeProvider must be used together with [%s]".format(getClass))
  }

  def cluster: Cluster = routeeProvider.cluster

  def fullAddress(actorRef: ActorRef): Address = routeeProvider.fullAddress(actorRef)

  def unregisterRoutees(member: Member) = {
    val address = member.address
    routeeProvider.nodes -= address

    // unregister routees that live on that node
    val affectedRoutes = routeeProvider.routees.filter(fullAddress(_) == address)
    routeeProvider.unregisterRoutees(affectedRoutes)

    // createRoutees will not create more than createRoutees and maxInstancesPerNode
    // this is useful when totalInstances < upNodes.size
    routeeProvider.createRoutees()
  }

  override def routerReceive: Receive = {
    case s: CurrentClusterState ⇒
      import Member.addressOrdering
      routeeProvider.nodes = s.members.collect { case m if routeeProvider.isAvailable(m) ⇒ m.address }
      routeeProvider.createRoutees()

    case m: MemberEvent if routeeProvider.isAvailable(m.member) ⇒
      routeeProvider.nodes += m.member.address
      // createRoutees will create routees based on
      // totalInstances and maxInstancesPerNode
      routeeProvider.createRoutees()

    case other: MemberEvent ⇒
      // other events means that it is no longer interesting, such as
      // MemberJoined, MemberLeft, MemberExited, MemberRemoved
      unregisterRoutees(other.member)

    case UnreachableMember(m) ⇒
      unregisterRoutees(m)
  }
}
