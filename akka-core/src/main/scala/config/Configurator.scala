/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.config

import ScalaConfig.{RestartStrategy, Component}

private[akka] trait TypedActorConfiguratorBase {
  def getExternalDependency[T](clazz: Class[T]): T

  def configure(restartStrategy: RestartStrategy, components: List[Component]): TypedActorConfiguratorBase

  def inject: TypedActorConfiguratorBase

  def supervise: TypedActorConfiguratorBase

  def reset

  def stop
}
