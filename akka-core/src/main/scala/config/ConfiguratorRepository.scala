/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.config

import scala.collection.mutable.HashSet

import se.scalablesolutions.akka.util.Logging

object ConfiguratorRepository extends Logging {

  private val configuration = new HashSet[Configurator]

  def registerConfigurator(conf: Configurator) = synchronized {
    configuration += conf
  }

  def getConfigurators: List[Configurator] = synchronized {
    configuration.toList
    //configurations.getOrElse(ctx, throw new IllegalArgumentException("No configuration for servlet context [" + ctx + "]"))
  }
}

class ConfiguratorRepository extends Logging {
  def registerConfigurator(conf: Configurator) = ConfiguratorRepository.registerConfigurator(conf)
  def getConfigurators: List[Configurator] = ConfiguratorRepository.getConfigurators
}

