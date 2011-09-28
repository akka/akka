/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import akka.actor.FaultHandlingStrategy
import akka.config.Supervision.SuperviseTypedActor

private[akka] trait TypedActorConfiguratorBase {
  def getExternalDependency[T](clazz: Class[T]): T

  def configure(restartStrategy: FaultHandlingStrategy, components: List[SuperviseTypedActor]): TypedActorConfiguratorBase

  def inject: TypedActorConfiguratorBase

  def supervise: TypedActorConfiguratorBase

  def reset

  def stop
}
