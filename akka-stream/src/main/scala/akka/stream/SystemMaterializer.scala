/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.actor.ActorSystem
import akka.actor.ClassicActorSystemProvider
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider

/**
 * The system materializer is a default materializer to use for most cases running streams, it is a single instance
 * per actor system that is tied to the lifecycle of that system.
 *
 * Not intended to be manually used in user code.
 */
object SystemMaterializer extends ExtensionId[SystemMaterializer] with ExtensionIdProvider {
  override def get(system: ActorSystem): SystemMaterializer = super.get(system)
  override def get(system: ClassicActorSystemProvider): SystemMaterializer = super.get(system)

  override def lookup = SystemMaterializer

  override def createExtension(system: ExtendedActorSystem): SystemMaterializer =
    new SystemMaterializer(system)
}

final class SystemMaterializer(system: ExtendedActorSystem) extends Extension {
  val materializer: Materializer = ActorMaterializer(ActorMaterializerSettings(system), "flow")(system)
}
