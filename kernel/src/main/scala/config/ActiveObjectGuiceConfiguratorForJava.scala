/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.config

import akka.kernel.config.JavaConfig._
import akka.kernel.{Supervisor, ActiveObjectFactory}

import com.google.inject._
import com.google.inject.jsr250.ResourceProviderFactory

import java.util._
import org.apache.camel.impl.{JndiRegistry, DefaultCamelContext}
import org.apache.camel.{Endpoint, Routes}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectGuiceConfiguratorForJava {
  val INSTANCE = new ActiveObjectGuiceConfigurator
  
  /**
   * Returns the active abject that has been put under supervision for the class specified.
   *
   * @param clazz the class for the active object
   * @return the active object for the class
   */
  def getActiveObject[T](clazz: Class[T]): T = INSTANCE.getActiveObject(clazz)

  def configureActiveObjects(
      restartStrategy: RestartStrategy, components: Array[Component]):
      ActiveObjectGuiceConfiguratorForJava = {
    INSTANCE.configureActiveObjects(
      restartStrategy.transform,
      components.toList.asInstanceOf[scala.List[Component]].map(_.transform))
    this
  }

  def inject(): ActiveObjectGuiceConfiguratorForJava = {
    INSTANCE.inject
    this
  }

  def supervise: ActiveObjectGuiceConfiguratorForJava = {
    INSTANCE.supervise
    this
  }

  def addExternalGuiceModule(module: Module): ActiveObjectGuiceConfiguratorForJava = {
    INSTANCE.addExternalGuiceModule(module)
    this
  }

  def addRoutes(routes: Routes): ActiveObjectGuiceConfiguratorForJava  = {
    INSTANCE.addRoutes(routes)
    this
  }

  
  def getComponentInterfaces: List[Class[_]] = {
    val al = new ArrayList[Class[_]]
    for (c <- INSTANCE.getComponentInterfaces) al.add(c)
    al
  }

  def getExternalDependency[T](clazz: Class[T]): T = INSTANCE.getExternalDependency(clazz)

  def getRoutingEndpoint(uri: String): Endpoint = INSTANCE.getRoutingEndpoint(uri)

  def getRoutingEndpoints: java.util.Collection[Endpoint] = INSTANCE.getRoutingEndpoints

  def getRoutingEndpoints(uri: String): java.util.Collection[Endpoint] = INSTANCE.getRoutingEndpoints(uri)

  def getGuiceModules: List[Module] = INSTANCE.getGuiceModules

  def reset = INSTANCE.reset

  def stop = INSTANCE.stop
}
