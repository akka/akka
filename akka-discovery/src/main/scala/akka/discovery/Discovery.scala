/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.discovery

import java.util.concurrent.ConcurrentHashMap
import java.util.function.{ Function ⇒ JFunction }

import akka.actor._

import scala.util.{ Failure, Success }

final class Discovery(implicit system: ExtendedActorSystem) extends Extension {

  private val implementations = new ConcurrentHashMap[String, ServiceDiscovery]
  private val factory = new JFunction[String, ServiceDiscovery] {
    override def apply(method: String): ServiceDiscovery = createServiceDiscovery(method)
  }

  private lazy val _defaultImplMethod =
    system.settings.config.getString("akka.discovery.method") match {
      case "<method>" ⇒
        throw new IllegalArgumentException(
          "No default service discovery implementation configured in " +
            "`akka.discovery.method`. Make sure to configure this setting to your preferred implementation such as " +
            "'akka-dns' in your application.conf (from the akka-discovery-dns module).")
      case method ⇒ method
    }

  private lazy val _simpleImpl = loadServiceDiscovery(_defaultImplMethod)

  /**
   * Default [[ServiceDiscovery]] as configured in `akka.discovery.method`.
   */
  @throws[IllegalArgumentException]
  def discovery: ServiceDiscovery = _simpleImpl

  /**
   * Create a [[ServiceDiscovery]] from configuration property.
   * The given `method` parameter is used to find configuration property
   * "akka.discovery.[method].class" or "[method].class". `method` can also
   * be a fully class name.
   *
   * The `SimpleServiceDiscovery` instance for a given `method` will be created
   * once and subsequent requests for the same `method` will return the same instance.
   */
  def loadServiceDiscovery(method: String): ServiceDiscovery = {
    implementations.computeIfAbsent(method, factory)
  }

  private def createServiceDiscovery(method: String): ServiceDiscovery = {
    val config = system.settings.config
    val dynamic = system.dynamicAccess

    def classNameFromConfig(path: String): String =
      if (config.hasPath(path)) config.getString(path)
      else "<nope>"

    def create(clazzName: String) = {
      dynamic
        .createInstanceFor[ServiceDiscovery](clazzName, (classOf[ExtendedActorSystem] → system) :: Nil)
        .recoverWith {
          case _: ClassNotFoundException | _: NoSuchMethodException ⇒
            dynamic.createInstanceFor[ServiceDiscovery](clazzName, (classOf[ActorSystem] → system) :: Nil)
        }
        .recoverWith {
          case _: ClassNotFoundException | _: NoSuchMethodException ⇒
            dynamic.createInstanceFor[ServiceDiscovery](clazzName, Nil)
        }
    }

    val configName = "akka.discovery." + method + ".class"
    val instanceTry = create(classNameFromConfig(configName)).recoverWith {
      case _: ClassNotFoundException | _: NoSuchMethodException ⇒
        create(classNameFromConfig(method + ".class"))
    }.recoverWith {
      case _: ClassNotFoundException | _: NoSuchMethodException ⇒
        create(method) // so perhaps, it is a classname?
    }

    instanceTry match {
      case Failure(e @ (_: ClassNotFoundException | _: NoSuchMethodException)) ⇒
        throw new IllegalArgumentException(
          s"Illegal [$configName] value or incompatible class! " +
            "The implementation class MUST extend akka.discovery.SimpleServiceDiscovery and take an " +
            "ExtendedActorSystem as constructor argument.", e)
      case Failure(e)        ⇒ throw e
      case Success(instance) ⇒ instance
    }

  }

}

object Discovery extends ExtensionId[Discovery] with ExtensionIdProvider {
  override def apply(system: ActorSystem): Discovery = super.apply(system)

  override def lookup: Discovery.type = Discovery

  override def get(system: ActorSystem): Discovery = super.get(system)

  override def createExtension(system: ExtendedActorSystem): Discovery = new Discovery()(system)

}
