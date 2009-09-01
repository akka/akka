/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.config

import javax.servlet.ServletContext

import scala.collection.mutable.HashSet

import kernel.util.Logging

object ConfiguratorRepository extends Logging {

  private val configuration = new HashSet[Configurator]

  // FIXME: cheating with only having one single, scope per ServletContext
  def registerConfigurator(conf: Configurator) = synchronized {
    configuration + conf
  }

  def getConfiguratorsFor(ctx: ServletContext): List[Configurator] = synchronized {
    configuration.toList
    //configurations.getOrElse(ctx, throw new IllegalArgumentException("No configuration for servlet context [" + ctx + "]"))
  }
}

class ConfiguratorRepository extends Logging {
  def registerConfigurator(conf: Configurator) = {
    ConfiguratorRepository.registerConfigurator(conf)
  }

  def getConfiguratorsFor(ctx: ServletContext): List[Configurator] = {
    ConfiguratorRepository.getConfiguratorsFor(ctx)
  }
}

