/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.metrics

import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.SupervisorStrategy
import akka.event.LoggingAdapter
import akka.event.Logging
import com.typesafe.config.Config
import scala.collection.immutable
import akka.actor.Props
import akka.actor.Deploy
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.ActorSystem
import akka.actor.ActorRef

/**
 * Cluster metrics extension.
 *
 * Cluster metrics is primarily for load-balancing of nodes. It controls metrics sampling
 * at a regular frequency, prepares highly variable data for further analysis by other entities,
 * and publishes the latest cluster metrics data around the node ring and local eventStream
 * to assist in determining the need to redirect traffic to the least-loaded nodes.
 *
 * Metrics sampling is delegated to the [[MetricsCollector]].
 *
 * Smoothing of the data for each monitored process is delegated to the
 * [[EWMA]] for exponential weighted moving average.
 */
class ClusterMetricsExtension(system: ExtendedActorSystem) extends Extension {

  /**
   * Metrics extension configuration.
   */
  val settings = ClusterMetricsSettings(system.settings.config)
  import settings._

  /**
   * INTERNAL API
   *
   * Supervision strategy.
   */
  private[metrics] val strategy = system.dynamicAccess.createInstanceFor[SupervisorStrategy](
    SupervisorStrategyProvider, immutable.Seq(classOf[Config] → SupervisorStrategyConfiguration))
    .getOrElse {
      val log: LoggingAdapter = Logging(system, getClass.getName)
      log.error(s"Configured strategy provider ${SupervisorStrategyProvider} failed to load, using default ${classOf[ClusterMetricsStrategy].getName}.")
      new ClusterMetricsStrategy(SupervisorStrategyConfiguration)
    }

  /**
   * Supervisor actor.
   * Accepts subtypes of [[CollectionControlMessage]]s to manage metrics collection at runtime.
   */
  val supervisor = system.systemActorOf(
    Props(classOf[ClusterMetricsSupervisor]).withDispatcher(MetricsDispatcher).withDeploy(Deploy.local),
    SupervisorName)

  /**
   * Subscribe user metrics listener actor unto [[ClusterMetricsEvent]]
   * events published by extension on the system event bus.
   */
  def subscribe(metricsListener: ActorRef): Unit = {
    system.eventStream.subscribe(metricsListener, classOf[ClusterMetricsEvent])
  }

  /**
   * Unsubscribe user metrics listener actor from [[ClusterMetricsEvent]]
   * events published by extension on the system event bus.
   */
  def unsubscribe(metricsListenter: ActorRef): Unit = {
    system.eventStream.unsubscribe(metricsListenter, classOf[ClusterMetricsEvent])
  }

}

/**
 * Cluster metrics extension provider.
 */
object ClusterMetricsExtension extends ExtensionId[ClusterMetricsExtension] with ExtensionIdProvider {
  override def lookup = ClusterMetricsExtension
  override def get(system: ActorSystem): ClusterMetricsExtension = super.get(system)
  override def createExtension(system: ExtendedActorSystem): ClusterMetricsExtension = new ClusterMetricsExtension(system)
}
