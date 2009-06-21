/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.config

import ScalaConfig.{RestartStrategy, Component}
import javax.servlet.ServletContext
import kernel.util.Logging

object ActiveObjectConfigurator extends Logging {

  private var configuration: ActiveObjectConfigurator = _

  // FIXME: cheating with only having one single, scope per ServletContext
  def registerConfigurator(conf: ActiveObjectConfigurator) = {
    configuration = conf
  }

  def getConfiguratorFor(ctx: ServletContext): ActiveObjectConfigurator = {
    configuration
    //configurations.getOrElse(ctx, throw new IllegalArgumentException("No configuration for servlet context [" + ctx + "]"))
  }
}

trait ActiveObjectConfigurator {
  /**
   * Returns the active abject that has been put under supervision for the class specified.
   *
   * @param clazz the class for the active object
   * @return the active object for the class
   */
  def getActiveObject[T](clazz: Class[T]): T

  def getExternalDependency[T](clazz: Class[T]): T

  def getComponentInterfaces: List[Class[_]]

  def configureActiveObjects(restartStrategy: RestartStrategy, components: List[Component]): ActiveObjectConfigurator

  def inject: ActiveObjectConfigurator

  def supervise: ActiveObjectConfigurator

  def reset

  def stop
}
