package akka.osgi.aries.blueprint

import org.osgi.framework.BundleContext
import akka.osgi.OsgiActorSystemFactory
import com.typesafe.config.ConfigFactory

/**
 * A set of helper/factory classes to build a Akka system using Blueprint
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

