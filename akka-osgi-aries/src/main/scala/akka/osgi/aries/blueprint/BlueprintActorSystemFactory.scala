/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.osgi.aries.blueprint

import org.osgi.framework.BundleContext
import akka.osgi.OsgiActorSystemFactory
import akka.actor.ActorSystem
import com.typesafe.config.{ Config, ConfigFactory }

/**
 * A set of helper/factory classes to build a Akka system using Blueprint.  This class is only meant to be used by
 * the [[akka.osgi.aries.blueprint.NamespaceHandler]] class, you should not use this class directly.
 *
 * If you're looking for a way to set up Akka using Blueprint without the namespace handler, you should use
 * [[akka.osgi.OsgiActorSystemFactory]] instead.
 */
class BlueprintActorSystemFactory(context: BundleContext, name: String, fallbackClassLoader: Option[ClassLoader]) extends OsgiActorSystemFactory(context, fallbackClassLoader) {

  def this(context: BundleContext, name: String) = this(context, name, Some(OsgiActorSystemFactory.akkaActorClassLoader))

  var config: Option[String] = None

  lazy val system: ActorSystem = super.createActorSystem(if (name == null || name.isEmpty) None else Some(name))

  def setConfig(config: String): Unit = this.config = Some(config)

  def create(): ActorSystem = system

  def destroy(): Unit = system.shutdown()

  /**
   * Strategy method to create the Config for the ActorSystem, ensuring that the default/reference configuration is
   * loaded from the akka-actor bundle.
   */
  override def actorSystemConfig(context: BundleContext): Config =
    config match {
      case Some(value) ⇒ ConfigFactory.parseString(value).withFallback(super.actorSystemConfig(context))
      case None        ⇒ super.actorSystemConfig(context)
    }
}

