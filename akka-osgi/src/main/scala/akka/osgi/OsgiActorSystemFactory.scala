package akka.osgi

import impl.BundleDelegatingClassLoader
import akka.actor.ActorSystem
import com.typesafe.config.{ ConfigFactory, Config }
import org.osgi.framework.BundleContext

/**
 * Factory class to create ActorSystem implementations in an OSGi environment.  This mainly involves dealing with
 * bundle classloaders appropriately to ensure that configuration files and classes get loaded properly
 */
class OsgiActorSystemFactory(val context: BundleContext) {

  /*
   * Classloader that delegates to the bundle for which the factory is creating an ActorSystem
   */
  val classloader = BundleDelegatingClassLoader.createFor(context)

  /**
   * Creates the ActorSystem and registers it in the OSGi Service Registry
   */
  def createActorSystem(name: String): ActorSystem = createActorSystem(Option(name))

  def createActorSystem(name: Option[String]): ActorSystem =
    ActorSystem(actorSystemName(name), actorSystemConfig(context), classloader)

  /**
   * Strategy method to create the Config for the ActorSystem, ensuring that the default/reference configuration is
   * loaded from the akka-actor bundle.
   */
  def actorSystemConfig(context: BundleContext): Config = {
    val reference = ConfigFactory.defaultReference(classOf[ActorSystem].getClassLoader)
    ConfigFactory.load(classloader).withFallback(reference)
  }

  /**
   * Determine a the ActorSystem name
   */
  def actorSystemName(name: Option[String]): String =
    name.getOrElse("bundle-%s-ActorSystem".format(context.getBundle().getBundleId))

}

object OsgiActorSystemFactory {

  def apply(context: BundleContext) = new OsgiActorSystemFactory(context)

}
