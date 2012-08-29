/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.routing

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
import akka.routing.Resizer
import akka.routing.Route
import akka.routing.RouteeProvider
import akka.routing.Router
import akka.routing.RouterConfig
import java.lang.IllegalStateException
import akka.cluster.ClusterScope

/**
 * [[akka.routing.RouterConfig]] implementation for deployment on cluster nodes.
 * Delegates other duties to the local [[akka.routing.RouterConfig]],
 * which makes it possible to mix this with the built-in routers such as
 * [[akka.routing.RoundRobinRouter]] or custom routers.
 */
case class ClusterRouterConfig(local: RouterConfig, totalInstances: Int, maxInstancesPerNode: Int) extends RouterConfig {

  override def createRouteeProvider(context: ActorContext, routeeProps: Props) =
    new ClusterRouteeProvider(context, routeeProps, resizer, totalInstances, maxInstancesPerNode)

  override def createRoute(routeeProvider: RouteeProvider): Route = local.createRoute(routeeProvider)

  override def createActor(): Router = local.createActor()

  override def supervisorStrategy: SupervisorStrategy = local.supervisorStrategy

  override def routerDispatcher: String = local.routerDispatcher

  override def resizer: Option[Resizer] = local.resizer

  override def withFallback(other: RouterConfig): RouterConfig = other match {
    case ClusterRouterConfig(local, _, _) ⇒ copy(local = this.local.withFallback(local))
    case _                                ⇒ copy(local = this.local.withFallback(other))
  }
}

/**
 * Factory and registry for routees of the router.
 * Deploys new routees on the cluster nodes.
 */
class ClusterRouteeProvider(
  _context: ActorContext,
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

  override def createRoutees(nrOfInstances: Int): Unit = {
    val impl = context.system.asInstanceOf[ActorSystemImpl] //TODO ticket #1559

    for (i ← 1 to nrOfInstances; target ← selectDeploymentTarget) {
      val name = "c" + childNameCounter.incrementAndGet
      val deploy = Deploy("", ConfigFactory.empty(), routeeProps.routerConfig, RemoteScope(target))
      var ref = impl.provider.actorOf(impl, routeeProps, context.self.asInstanceOf[InternalActorRef], context.self.path / name,
        systemService = false, Some(deploy), lookupDeploy = false, async = false)
      // must register each one, since registered routees are used in selectDeploymentTarget
      registerRoutees(Some(ref))
    }
  }

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

  /**
   * Fills in self address for local ActorRef
   */
  private def fullAddress(actorRef: ActorRef): Address = actorRef.path.address match {
    case Address(_, _, None, None) ⇒ cluster.selfAddress
    case a                         ⇒ a
  }

  private def cluster: Cluster = Cluster(context.system)

  import Member.addressOrdering
  @volatile
  private var upNodes: SortedSet[Address] = cluster.readView.members.collect {
    case m if m.status == MemberStatus.Up ⇒ m.address
  }

  // create actor that subscribes to the cluster eventBus
  private val eventBusListener: ActorRef = context.actorOf(Props(new Actor {
    override def preStart(): Unit = cluster.subscribe(self, classOf[ClusterDomainEvent])
    override def postStop(): Unit = cluster.unsubscribe(self)

    def receive = {
      case s: CurrentClusterState ⇒
        upNodes = s.members.collect { case m if m.status == MemberStatus.Up ⇒ m.address }

      case MemberUp(m) ⇒
        upNodes += m.address
        // createRoutees will not create more than createRoutees and maxInstancesPerNode
        createRoutees(totalInstances)

      case other: MemberEvent ⇒
        // other events means that it is no longer interesting, such as
        // MemberJoined, MemberLeft, MemberExited, MemberUnreachable, MemberRemoved
        upNodes -= other.member.address

        // createRoutees will not create more than createRoutees and maxInstancesPerNode
        createRoutees(totalInstances) // Here we

    }

  }), name = "cluster-listener")

}

