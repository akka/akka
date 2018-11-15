/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.discovery.aggregate

import akka.actor.ExtendedActorSystem
import akka.discovery.ServiceDiscovery.Resolved
import akka.discovery.aggregate.AggregateServiceDiscovery.Mechanisms
import akka.discovery.{ Lookup, Discovery, ServiceDiscovery }
import akka.event.Logging
import akka.util.Helpers.Requiring
import com.typesafe.config.Config

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

final class AggregateSimpleServiceDiscoverySettings(config: Config) {

  val discoveryMechanisms = config
    .getStringList("discovery-mechanisms")
    .asScala
    .toList
    .requiring(_.nonEmpty, "At least one discovery mechanism should be specified")

}

object AggregateServiceDiscovery {
  type Mechanisms = List[(String, ServiceDiscovery)]
}

final class AggregateServiceDiscovery(system: ExtendedActorSystem) extends ServiceDiscovery {

  private val log = Logging(system, getClass)

  private val settings =
    new AggregateSimpleServiceDiscoverySettings(system.settings.config.getConfig("akka.discovery.aggregate"))

  private val mechanisms = {
    val serviceDiscovery = Discovery(system)
    settings.discoveryMechanisms.map(mech ⇒ (mech, serviceDiscovery.loadServiceDiscovery(mech)))
  }
  private implicit val ec = system.dispatcher

  /**
   * Each discovery mechanism is given the resolveTimeout rather than reducing it each time between mechanisms.
   */
  override def lookup(lookup: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] =
    resolve(mechanisms, lookup, resolveTimeout)

  private def resolve(sds: Mechanisms, query: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] = {
    sds match {
      case (mechanism, next) :: Nil ⇒
        log.debug("Looking up [{}] with [{}]", query, mechanism)
        next.lookup(query, resolveTimeout)
      case (mechanism, next) :: tail ⇒
        log.debug("Looking up [{}] with [{}]", query, mechanism)
        // If nothing comes back then try the next one
        next
          .lookup(query, resolveTimeout)
          .flatMap { resolved ⇒
            if (resolved.addresses.isEmpty) {
              log.debug("Mechanism [{}] returned no ResolvedTargets, trying next", query)
              resolve(tail, query, resolveTimeout)
            } else
              Future.successful(resolved)
          }
          .recoverWith {
            case NonFatal(t) ⇒
              log.error(t, "[{}] Service discovery failed. Trying next discovery mechanism", mechanism)
              resolve(tail, query, resolveTimeout)
          }
      case Nil ⇒
        // this is checked in `discoveryMechanisms`, but silence compiler warning
        throw new IllegalStateException("At least one discovery mechanism should be specified")
    }
  }
}
