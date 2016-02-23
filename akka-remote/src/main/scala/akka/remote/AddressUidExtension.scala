/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import java.util.concurrent.ThreadLocalRandom
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider

/**
 * Extension that holds a uid that is assigned as a random `Int`.
 * The uid is intended to be used together with an [[akka.actor.Address]]
 * to be able to distinguish restarted actor system using the same host
 * and port.
 */
object AddressUidExtension extends ExtensionId[AddressUidExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): AddressUidExtension = super.get(system)

  override def lookup = AddressUidExtension

  override def createExtension(system: ExtendedActorSystem): AddressUidExtension = new AddressUidExtension(system)
}

class AddressUidExtension(val system: ExtendedActorSystem) extends Extension {
  val addressUid: Int = ThreadLocalRandom.current.nextInt()
}