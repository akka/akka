/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.aeron

import akka.actor.{ ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import io.aeron.driver.MediaDriver

// TODO should we expose the media driver directly or just a subset of properties e.g.
//  the directory
// TODO shut down and delete directory via CoordinatedShutdown?
class MediaDriverExtensionImpl(system: ExtendedActorSystem) extends Extension {

  // TODO settings
  private val mediaDriver: MediaDriver = MediaDriver.launchEmbedded()

  val aeronDirectory: String = mediaDriver.aeronDirectoryName()

}

object MediaDriverExtension extends ExtensionId[MediaDriverExtensionImpl]
  with ExtensionIdProvider {
  /**
   * Is used by Akka to instantiate the Extension identified by this ExtensionId,
   * internal use only.
   */
  override def createExtension(system: ExtendedActorSystem): MediaDriverExtensionImpl = {
    new MediaDriverExtensionImpl(system)
  }

  /**
   * Returns the canonical ExtensionId for this Extension
   */
  override def lookup(): ExtensionId[_ <: Extension] = MediaDriverExtension
}

