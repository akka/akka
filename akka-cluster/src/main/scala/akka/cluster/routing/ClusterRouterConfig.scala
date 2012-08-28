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

/**
 * [[akka.routing.RouterConfig]] implementation for deployment on cluster nodes.
 * Delegates other duties to the local [[akka.routing.RouterConfig]],
 * which makes it possible to mix this with the built-in routers such as
 * [[akka.routing.RoundRobinRouter]] or custom routers.
 */
case class ClusterRouterConfig(local: RouterConfig) extends RouterConfig {

  override def createRouteeProvider(context: ActorContext) = new ClusterRouteeProvider(context, resizer)

  override def createRoute(routeeProps: Props, routeeProvider: RouteeProvider): Route = {
    local.createRoute(routeeProps, routeeProvider)
  }

  override def createActor(): Router = local.createActor()

  override def supervisorStrategy: SupervisorStrategy = local.supervisorStrategy

  override def routerDispatcher: String = local.routerDispatcher

  override def resizer: Option[Resizer] = local.resizer

  override def withFallback(other: RouterConfig): RouterConfig = other match {
    case ClusterRouterConfig(local) ⇒ copy(local = this.local.withFallback(local))
    case _                          ⇒ copy(local = this.local.withFallback(other))
  }
}

/**
 * Factory and registry for routees of the router.
 * Deploys new routees on the cluster nodes.
 */
class ClusterRouteeProvider(_context: ActorContext, _resizer: Option[Resizer])
  extends RouteeProvider(_context, _resizer) {

  // need this counter as instance variable since Resizer may call createRoutees several times
  private val childNameCounter = new AtomicInteger

  override def createRoutees(props: Props, nrOfInstances: Int, _routees: Iterable[String]): IndexedSeq[ActorRef] = {
    val nodes = upNodes
    if (_routees.nonEmpty) {
      throw new ConfigurationException("Cluster deployment can not be combined with routees for [%s]"
        format context.self.path.toString)
    } else if (nodes.isEmpty) {
      IndexedSeq.empty
    } else {
      val impl = context.system.asInstanceOf[ActorSystemImpl] //TODO ticket #1559
      // FIXME We could count number of routees per node and select nodes with least routees first
      val nodesIter: Iterator[Address] = Stream.continually(nodes).flatten.iterator
      IndexedSeq.empty[ActorRef] ++ (for (i ← 1 to nrOfInstances) yield {
        val name = "c" + childNameCounter.incrementAndGet
        val deploy = Deploy("", ConfigFactory.empty(), props.routerConfig, RemoteScope(nodesIter.next))
        impl.provider.actorOf(impl, props, context.self.asInstanceOf[InternalActorRef], context.self.path / name,
          systemService = false, Some(deploy), lookupDeploy = false, async = false)
      })
    }
  }

  // FIXME experimental hack to let the cluster initialize
  // What should we do before we have full cluster information (startup phase)?
  Cluster(context.system).readView
  Thread.sleep(2000)

  import Member.addressOrdering
  @volatile
  private var upNodes: SortedSet[Address] = Cluster(context.system).readView.members.collect {
    case m if m.status == MemberStatus.Up ⇒ m.address
  }

  // create actor that subscribes to the cluster eventBus
  private val eventBusListener: ActorRef = {

    // FIXME is this allowed, are we inside or outside of the actor?
    context.actorOf(Props(new Actor {
      override def preStart(): Unit = Cluster(context.system).subscribe(self, classOf[ClusterDomainEvent])
      override def postStop(): Unit = Cluster(context.system).unsubscribe(self)

      def receive = {
        case s: CurrentClusterState ⇒
          upNodes = s.members.collect { case m if m.status == MemberStatus.Up ⇒ m.address }

        case MemberUp(m) ⇒
          upNodes += m.address
        // FIXME Here we could trigger a rebalance, by counting number of routees per node and unregister
        // routees from nodes with many routees and deploy on this new node instead

        case other: MemberEvent ⇒
          // other events means that it is no longer interesting, such as
          // MemberJoined, MemberLeft, MemberExited, MemberUnreachable, MemberRemoved
          upNodes -= other.member.address

        // FIXME Should we deploy new routees corresponding to the ones that goes away here?
        // or is that a job for a special Cluster Resizer?

      }

    }), name = "cluster-listener")
  }

}

