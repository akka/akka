/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.config

import javax.servlet.ServletContext

import scala.collection.mutable.HashSet

import ScalaConfig.{RestartStrategy, Component}
import kernel.util.Logging

trait ActiveObjectConfigurator {
  /**
   * Returns the active abject that has been put under supervision for the class specified.
   *
   * @param clazz the class for the active object
   * @return the active object for the class
   */
  def getActiveObject[T](clazz: Class[T]): T

  def isActiveObjectDefined[T](clazz: Class[T]): Boolean

  def getExternalDependency[T](clazz: Class[T]): T

  def getComponentInterfaces: List[Class[_]]

  def configureActiveObjects(restartStrategy: RestartStrategy, components: List[Component]): ActiveObjectConfigurator

  def inject: ActiveObjectConfigurator

  def supervise: ActiveObjectConfigurator

  def reset

  def stop
}

object ActiveObjectConfigurator extends Logging {

  private val configuration = new HashSet[ActiveObjectConfigurator]

  // FIXME: cheating with only having one single, scope per ServletContext
  def registerConfigurator(conf: ActiveObjectConfigurator) = synchronized {
    configuration + conf
  }

  def getConfiguratorsFor(ctx: ServletContext): List[ActiveObjectConfigurator] = synchronized {
    configuration.toList
    //configurations.getOrElse(ctx, throw new IllegalArgumentException("No configuration for servlet context [" + ctx + "]"))
  }
}

class ActiveObjectConfiguratorRepository extends Logging {
  def registerConfigurator(conf: ActiveObjectConfigurator) = {
    ActiveObjectConfigurator.registerConfigurator(conf)
  }

  def getConfiguratorsFor(ctx: ServletContext): List[ActiveObjectConfigurator] = {
    ActiveObjectConfigurator.getConfiguratorsFor(ctx)
  }
}

