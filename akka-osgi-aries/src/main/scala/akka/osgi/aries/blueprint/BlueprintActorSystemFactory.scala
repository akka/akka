/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.osgi.aries.blueprint

import org.osgi.framework.BundleContext
import akka.osgi.OsgiActorSystemFactory
import com.typesafe.config.ConfigFactory

/**
 * A set of helper/factory classes to build a Akka system using Blueprint.  This class is only meant to be used by
 * the [[akka.osgi.aries.blueprint.NamespaceHandler]] class, you should not use this class directly.
 *
 * If you're looking for a way to set up Akka using Blueprint without the namespace handler, you should use
 * [[akka.osgi.OsgiActorSystemFactory]] instead.
 */
class BlueprintActorSystemFactory(context: BundleContext, name: String) extends OsgiActorSystemFactory(context) {

  var config: Option[String] = None

  lazy val system = super.createActorSystem(stringToOption(name))

  def setConfig(config: String) = { this.config = Some(config) }

  def create = system

  def destroy = system.shutdown()

  def stringToOption(original: String) = if (original == null || original.isEmpty) {
    None
  } else {
    Some(original)
  }

  /**
   * Strategy method to create the Config for the ActorSystem, ensuring that the default/reference configuration is
   * loaded from the akka-actor bundle.
   */
  override def actorSystemConfig(context: BundleContext) = {
    config match {
      case Some(value) ⇒ ConfigFactory.parseString(value).withFallback(super.actorSystemConfig(context))
      case None        ⇒ super.actorSystemConfig(context)
    }

  }
}

