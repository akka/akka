/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.config

import Supervision._

import java.util.{ List ⇒ JList }
import java.util.{ ArrayList }

import com.google.inject._

/**
 * Configurator for the TypedActors. Used to do declarative configuration of supervision.
 * It also does dependency injection with and into TypedActors using dependency injection
 * frameworks such as Google Guice or Spring.
 * <p/>
 * If you don't want declarative configuration then you should use the <code>TypedActor</code>
 * factory methods.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TypedActorConfigurator {
  import scala.collection.JavaConversions._
  // TODO: make pluggable once we have f.e a SpringConfigurator
  private val INSTANCE = new TypedActorGuiceConfigurator

  /**
   * Returns the a list with all typed actors that has been put under supervision for the class specified.
   *
   * @param clazz the class for the typed actor
   * @return a list with all the typed actors for the class
   */
  def getInstances[T](clazz: Class[T]): JList[T] =
    INSTANCE.getInstance(clazz).foldLeft(new ArrayList[T]) { (l, i) ⇒ l add i; l }

  /**
   * Returns the first item in a list of all typed actors that has been put under supervision for the class specified.
   *
   * @param clazz the class for the typed actor
   * @return the typed actor for the class
   */
  def getInstance[T](clazz: Class[T]): T = INSTANCE.getInstance(clazz).head

  def configure(faultHandlingStrategy: FaultHandlingStrategy, components: Array[SuperviseTypedActor]): TypedActorConfigurator = {
    INSTANCE.configure(
      faultHandlingStrategy,
      components.toList.asInstanceOf[scala.List[SuperviseTypedActor]])
    this
  }

  def inject: TypedActorConfigurator = {
    INSTANCE.inject
    this
  }

  def supervise: TypedActorConfigurator = {
    INSTANCE.supervise
    this
  }

  def addExternalGuiceModule(module: Module): TypedActorConfigurator = {
    INSTANCE.addExternalGuiceModule(module)
    this
  }

  def getComponentInterfaces: JList[Class[_]] = {
    val al = new ArrayList[Class[_]]
    for (c ← INSTANCE.getComponentInterfaces) al.add(c)
    al
  }

  def getExternalDependency[T](clazz: Class[T]): T = INSTANCE.getExternalDependency(clazz)

  // TODO: should this be exposed?
  def getGuiceModules: JList[Module] = INSTANCE.getGuiceModules

  def reset = INSTANCE.reset

  def stop = INSTANCE.stop
}
