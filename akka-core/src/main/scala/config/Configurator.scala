/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.config

import ScalaConfig.{RestartStrategy, Component}

/**
 * Manages the active abject or actor that has been put under supervision for the class specified.
 */
private[akka] trait Configurator {
  /**
   * Returns the active abject or actor that has been put under supervision for the class specified.
   *
   * @param clazz the class for the active object
   * @return the active object for the class
   */
  def getInstance[T](clazz: Class[T]): List[T]  

  def getComponentInterfaces: List[Class[_]]

  def isDefined(clazz: Class[_]): Boolean
}

private[akka] trait ActiveObjectConfiguratorBase extends Configurator {
  def getExternalDependency[T](clazz: Class[T]): T

  def configure(restartStrategy: RestartStrategy, components: List[Component]): ActiveObjectConfiguratorBase

  def inject: ActiveObjectConfiguratorBase

  def supervise: ActiveObjectConfiguratorBase

  def reset

  def stop
}
