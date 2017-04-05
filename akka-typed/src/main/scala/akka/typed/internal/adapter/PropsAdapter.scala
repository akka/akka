/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed
package internal
package adapter

import akka.typed.Behavior
import akka.typed.EmptyDeploymentConfig
import akka.typed.DeploymentConfig
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[akka] object PropsAdapter {
  def apply[T](behavior: () ⇒ Behavior[T], deploy: DeploymentConfig = EmptyDeploymentConfig): akka.actor.Props = {
    // FIXME use DeploymentConfig, e.g. dispatcher
    akka.actor.Props(new ActorAdapter(behavior()))
  }

}
