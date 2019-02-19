package akka.lease.javadsl

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.annotation.ApiMayChange
import akka.lease.internal.LeaseAdapter
import akka.lease.scaladsl.{LeaseProvider => ScalaLeaseProvider}

@ApiMayChange
object LeaseProvider extends ExtensionId[LeaseProvider] with ExtensionIdProvider {
  override def get(system: ActorSystem): LeaseProvider = super.get(system)

  override def lookup = LeaseProvider

  override def createExtension(system: ExtendedActorSystem): LeaseProvider = new LeaseProvider(system)

  private final case class LeaseKey(leaseName: String, configPath: String, clientName: String)
}

@ApiMayChange
class LeaseProvider(system: ExtendedActorSystem) extends Extension {
  private val delegate = ScalaLeaseProvider(system)

  /**
   * The configuration define at `configPath` must have a property `lease-class` that defines
   * the fully qualified class name of the Lease implementation.
   * The class must implement [[Lease]] and have constructor with [[akka.lease.LeaseSettings]] parameter and
   * optionally ActorSystem parameter.
   *
   * @param leaseName the name of the lease resource
   * @param configPath the path of configuration for the lease
   * @param ownerName the owner that will `acquire` the lease, e.g. hostname and port of the ActorSystem
   */
  def getLease(leaseName: String, configPath: String, ownerName: String): Lease = {
    val scalaLease = delegate.getLease(leaseName, configPath, ownerName)
    new LeaseAdapter(scalaLease)
  }
}



