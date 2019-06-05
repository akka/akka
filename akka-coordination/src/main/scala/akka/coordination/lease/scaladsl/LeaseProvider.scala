/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.coordination.lease.scaladsl

import java.util.concurrent.ConcurrentHashMap
import java.util.function.{ Function => JFunction }

import scala.collection.immutable
import scala.util.{ Failure, Success, Try }
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.event.Logging
import akka.coordination.lease.LeaseSettings

object LeaseProvider extends ExtensionId[LeaseProvider] with ExtensionIdProvider {
  override def get(system: ActorSystem): LeaseProvider = super.get(system)

  override def lookup = LeaseProvider

  override def createExtension(system: ExtendedActorSystem): LeaseProvider = new LeaseProvider(system)

  private final case class LeaseKey(leaseName: String, configPath: String, clientName: String)
}

class LeaseProvider(system: ExtendedActorSystem) extends Extension {
  import LeaseProvider.LeaseKey

  private val log = Logging(system, getClass)
  private val leases = new ConcurrentHashMap[LeaseKey, Lease]()

  /**
   * The configuration define at `configPath` must have a property `lease-class` that defines
   * the fully qualified class name of the Lease implementation.
   * The class must implement [[Lease]] and have constructor with [[akka.coordination.lease.LeaseSettings]] parameter and
   * optionally ActorSystem parameter.
   *
   * @param leaseName the name of the lease resource
   * @param configPath the path of configuration for the lease
   * @param ownerName the owner that will `acquire` the lease, e.g. hostname and port of the ActorSystem
   */
  def getLease(leaseName: String, configPath: String, ownerName: String): Lease = {
    val leaseKey = LeaseKey(leaseName, configPath, ownerName)
    leases.computeIfAbsent(
      leaseKey,
      new JFunction[LeaseKey, Lease] {
        override def apply(t: LeaseKey): Lease = {
          val leaseConfig = system.settings.config
            .getConfig(configPath)
            .withFallback(system.settings.config.getConfig("akka.coordination.lease"))
          loadLease(LeaseSettings(leaseConfig, leaseName, ownerName), configPath)
        }
      })
  }

  private def loadLease(leaseSettings: LeaseSettings, configPath: String): Lease = {
    val fqcn = leaseSettings.leaseConfig.getString("lease-class")
    require(fqcn.nonEmpty, "lease-class must not be empty")
    val dynamicAccess = system.dynamicAccess
    val instance: Try[Lease] = dynamicAccess.createInstanceFor[Lease](
      fqcn,
      immutable.Seq((classOf[LeaseSettings], leaseSettings), (classOf[ExtendedActorSystem], system))) match {
      case s: Success[Lease] =>
        s
      case Failure(_: NoSuchMethodException) =>
        dynamicAccess.createInstanceFor[Lease](fqcn, immutable.Seq((classOf[LeaseSettings], leaseSettings)))
      case f: Failure[_] =>
        f
    }
    instance match {
      case Success(value) => value
      case Failure(e) =>
        log.error(
          e,
          "Invalid lease configuration for leaseName [{}], configPath [{}] lease-class [{}]. " +
          "The class must implement Lease and have constructor with LeaseSettings parameter and " +
          "optionally ActorSystem parameter.",
          leaseSettings.leaseName,
          configPath,
          fqcn)
        throw e
    }
  }

  // TODO how to clean up a lease? Not important for this use case as we'll only have one lease
}
