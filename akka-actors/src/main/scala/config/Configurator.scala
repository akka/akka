/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.config

import ScalaConfig.{RestartStrategy, Component}

trait Configurator {
  /**
   * Returns the active abject or actor that has been put under supervision for the class specified.
   *
   * @param clazz the class for the active object
   * @return the active object for the class
   */
  def getInstance[T](clazz: Class[T]): T  

  def getComponentInterfaces: List[Class[_]]

  def isDefined(clazz: Class[_]): Boolean
}

trait ActiveObjectConfigurator extends Configurator {
  def getExternalDependency[T](clazz: Class[T]): T

  def configure(restartStrategy: RestartStrategy, components: List[Component]): ActiveObjectConfigurator

  def inject: ActiveObjectConfigurator

  def supervise: ActiveObjectConfigurator

  def reset

  def stop
}
