/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.config

import JavaConfig._

import java.util.{List => JList}
import java.util.{ArrayList}

import com.google.inject._

/**
 * Configurator for the Active Objects. Used to do declarative configuration of supervision.
 * It also does dependency injection with and into Active Objects using dependency injection
 * frameworks such as Google Guice or Spring.
 * <p/>
 * If you don't want declarative configuration then you should use the <code>ActiveObject</code>
 * factory methods.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectConfigurator {
  import scala.collection.JavaConversions._
  // TODO: make pluggable once we have f.e a SpringConfigurator
  private val INSTANCE = new ActiveObjectGuiceConfigurator

  /**
   * Returns the a list with all active objects that has been put under supervision for the class specified.
   *
   * @param clazz the class for the active object
   * @return a list with all the active objects for the class
   */
  def getInstances[T](clazz: Class[T]): JList[T] = INSTANCE.getInstance(clazz).foldLeft(new ArrayList[T]){ (l, i) => l add i ; l }

  /**
   * Returns the first item in a list of all active objects that has been put under supervision for the class specified.
   *
   * @param clazz the class for the active object
   * @return the active object for the class
   */
  def getInstance[T](clazz: Class[T]): T = INSTANCE.getInstance(clazz).head

  def configure(restartStrategy: RestartStrategy, components: Array[Component]): ActiveObjectConfigurator = {
    INSTANCE.configure(
      restartStrategy.transform,
      components.toList.asInstanceOf[scala.List[Component]].map(_.transform))
    this
  }

  def inject: ActiveObjectConfigurator = {
    INSTANCE.inject
    this
  }

  def supervise: ActiveObjectConfigurator = {
    INSTANCE.supervise
    this
  }

  def addExternalGuiceModule(module: Module): ActiveObjectConfigurator = {
    INSTANCE.addExternalGuiceModule(module)
    this
  }

  def getComponentInterfaces: JList[Class[_]] = {
    val al = new ArrayList[Class[_]]
    for (c <- INSTANCE.getComponentInterfaces) al.add(c)
    al
  }

  def getExternalDependency[T](clazz: Class[T]): T = INSTANCE.getExternalDependency(clazz)

  // TODO: should this be exposed?
  def getGuiceModules: JList[Module] = INSTANCE.getGuiceModules

  def reset = INSTANCE.reset

  def stop = INSTANCE.stop
}
