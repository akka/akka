/*
 * Copyright (C) 2020-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.annotation.ApiMayChange
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout
import akka.annotation.InternalApi
import akka.pattern.AskTimeoutException
import akka.util.ccompat.JavaConverters._
import akka.util.JavaDurationConverters._

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import com.typesafe.config.Config

/**
 * Internal API
 */
@InternalApi
private[akka] object ClusterShardingHealthCheckSettings {
  def apply(config: Config): ClusterShardingHealthCheckSettings =
    new ClusterShardingHealthCheckSettings(
      config.getStringList("names").asScala.toSet,
      config.getDuration("timeout").asScala)
}

@ApiMayChange
final class ClusterShardingHealthCheckSettings(val names: Set[String], val timeout: FiniteDuration)

private object ClusterShardingHealthCheck {
  val Success = Future.successful(true)
}

/**
 * INTERNAL API (ctr)
 */
@ApiMayChange
final class ClusterShardingHealthCheck private[akka] (
    system: ActorSystem,
    settings: ClusterShardingHealthCheckSettings,
    shardRegion: String => ActorRef)
    extends (() => Future[Boolean]) {

  private val log = Logging(system, classOf[ClusterShardingHealthCheck])

  def this(system: ActorSystem) =
    this(
      system,
      ClusterShardingHealthCheckSettings(system.settings.config.getConfig("akka.cluster.sharding.healthcheck")),
      name => ClusterSharding(system).shardRegion(name))

  private implicit val timeout: Timeout = settings.timeout
  private implicit val ec: ExecutionContext = system.dispatchers.internalDispatcher

  // Once the check has passed it always does
  @volatile private var registered = false

  override def apply(): Future[Boolean] = {
    if (settings.names.isEmpty || registered) {
      ClusterShardingHealthCheck.Success
    } else {
      Future
        .traverse(settings.names) { name =>
          shardRegion(name) // this can throw if shard region not registered and it'll fail the check
            .ask(ShardRegion.GetShardRegionStatus)
            .mapTo[ShardRegion.ShardRegionStatus]
        }
        .map { allResponses =>
          val allRegistered = allResponses.forall(_.registeredWithCoordinator)
          if (!allRegistered && log.isInfoEnabled) {
            log.info(
              "Not all shard regions have registered with coordinator. Still to register: [{}]",
              allResponses
                .collect {
                  case response if !response.registeredWithCoordinator => response.typeName
                }
                .mkString(","))
          }
          if (allRegistered) {
            registered = true
          }
          allRegistered
        }
        .recover {
          case _: AskTimeoutException =>
            if (log.isDebugEnabled) {
              log.debug(
                "Shard regions [{}] did not respond in time. Failing health check.",
                settings.names.mkString(","))
            }
            false
        }
    }
  }
}
