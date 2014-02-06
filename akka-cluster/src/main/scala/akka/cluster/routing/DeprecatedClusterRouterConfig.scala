/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.routing

import scala.collection.immutable
import akka.routing.RouterConfig
import akka.routing.Router
import akka.actor.Props
import akka.actor.ActorContext
import akka.routing.Routee
import java.util.concurrent.atomic.AtomicInteger
import akka.actor.Address
import akka.actor.ActorCell
import akka.actor.Deploy
import com.typesafe.config.ConfigFactory
import akka.routing.ActorRefRoutee
import akka.remote.RemoteScope
import akka.actor.Actor
import akka.actor.SupervisorStrategy
import akka.routing.Resizer
import akka.routing.RouterConfig
import akka.routing.Pool
import akka.routing.Group
import akka.remote.routing.RemoteRouterConfig
import akka.routing.RouterActor
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.actor.ActorRef
import akka.cluster.Member
import scala.annotation.tailrec
import akka.actor.RootActorPath
import akka.cluster.MemberStatus
import akka.routing.ActorSelectionRoutee
import akka.actor.ActorInitializationException
import akka.routing.RouterPoolActor
import akka.actor.ActorSystem
import akka.actor.ActorSystem
import akka.routing.RoutingLogic
import akka.actor.RelativeActorPath
import com.typesafe.config.Config
import akka.routing.DeprecatedRouterConfig

@deprecated("Use ClusterRouterPoolSettings or ClusterRouterGroupSettings", "2.3")
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
@deprecated("Use ClusterRouterPoolSettings or ClusterRouterGroupSettings", "2.3")
case class ClusterRouterSettings private[akka] (
  totalInstances: Int,
  maxInstancesPerNode: Int,
  routeesPath: String,
  allowLocalRoutees: Boolean,
  useRole: Option[String]) extends ClusterRouterSettingsBase {

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

  routeesPath match {
    case RelativeActorPath(elements) ⇒ // good
    case _ ⇒
      throw new IllegalArgumentException("routeesPath [%s] is not a valid relative actor path" format routeesPath)
  }

  def isRouteesPathDefined: Boolean = (routeesPath ne null) && routeesPath != ""

}

@deprecated("Use ClusterRouterPool or ClusterRouterGroup", "2.3")
@SerialVersionUID(1L)
final case class ClusterRouterConfig(local: DeprecatedRouterConfig, settings: ClusterRouterSettings) extends DeprecatedRouterConfig with ClusterRouterConfigBase {

  require(local.resizer.isEmpty, "Resizer can't be used together with cluster router")

  @transient private val childNameCounter = new AtomicInteger

  /**
   * INTERNAL API
   */
  override private[akka] def newRoutee(routeeProps: Props, context: ActorContext): Routee = {
    val name = "c" + childNameCounter.incrementAndGet
    val ref = context.asInstanceOf[ActorCell].attachChild(routeeProps, name, systemService = false)
    ActorRefRoutee(ref)
  }

  override def nrOfInstances: Int = if (settings.allowLocalRoutees) settings.maxInstancesPerNode else 0

  override def paths: immutable.Iterable[String] =
    if (settings.allowLocalRoutees && settings.routeesPath.nonEmpty) List(settings.routeesPath) else Nil

  override def resizer: Option[Resizer] = local.resizer

  /**
   * INTERNAL API
   */
  override private[akka] def createRouterActor(): RouterActor =
    if (settings.routeesPath.isEmpty)
      new ClusterRouterPoolActor(local.supervisorStrategy, ClusterRouterPoolSettings(settings.totalInstances,
        settings.maxInstancesPerNode, settings.allowLocalRoutees, settings.useRole))
    else
      new ClusterRouterGroupActor(ClusterRouterGroupSettings(settings.totalInstances, List(settings.routeesPath),
        settings.allowLocalRoutees, settings.useRole))

  override def supervisorStrategy: SupervisorStrategy = local.supervisorStrategy

  override def withFallback(other: RouterConfig): RouterConfig = other match {
    case ClusterRouterConfig(_: ClusterRouterConfig, _) ⇒ throw new IllegalStateException(
      "ClusterRouterConfig is not allowed to wrap a ClusterRouterConfig")
    case ClusterRouterConfig(local, _) ⇒
      copy(local = this.local.withFallback(local).asInstanceOf[DeprecatedRouterConfig])
    case _ ⇒
      copy(local = this.local.withFallback(other).asInstanceOf[DeprecatedRouterConfig])
  }

}

