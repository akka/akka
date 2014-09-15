/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider

/**
 * Extension provides access to bound addresses.
 */
object BoundAddressesExtension extends ExtensionId[BoundAddressesExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): BoundAddressesExtension = super.get(system)

  override def lookup = BoundAddressesExtension

  override def createExtension(system: ExtendedActorSystem): BoundAddressesExtension =
    new BoundAddressesExtension(system)
}

class BoundAddressesExtension(val system: ExtendedActorSystem) extends Extension {
  /**
   * Returns a mapping from a protocol to a set of bound addresses.
   */
  def boundAddresses: Map[String, Set[Address]] = system.provider
    .asInstanceOf[RemoteActorRefProvider].transport
    .asInstanceOf[Remoting].boundAddresses
}
