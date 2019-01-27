/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.aeron

import akka.actor.{ ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.aeron.internal.TaskRunner
import akka.annotation.InternalApi

// TODO move task runner into common module to avoid dep on remoting
// TODO configuration for idle cpu level
/**
 * Internal API
 */
@InternalApi
private[akka] class TaskRunnerExtensionImpl(eas: ExtendedActorSystem) extends Extension {
  val taskRunner = new TaskRunner(eas, 5)
  taskRunner.start()
}

/**
 * Internal API
 */
@InternalApi
private[akka] object TaskRunnerExtension extends ExtensionId[TaskRunnerExtensionImpl]
  with ExtensionIdProvider {

  override def createExtension(system: ExtendedActorSystem): TaskRunnerExtensionImpl =
    new TaskRunnerExtensionImpl(system)

  override def lookup(): ExtensionId[_ <: Extension] = TaskRunnerExtension
}
