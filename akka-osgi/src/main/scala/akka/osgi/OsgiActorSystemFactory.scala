package akka.osgi

import impl.BundleDelegatingClassLoader
import org.osgi.framework.BundleContext
import akka.actor.ActorSystem
import com.typesafe.config.{ ConfigFactory, Config }
import java.util.{ Dictionary, Properties }

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

  def createActorSystem(name: Option[String]): ActorSystem = {
    val system = ActorSystem(actorSystemName(name), actorSystemConfig(context), classloader)
    registerService(system)
    system
  }

  def registerService(system: ActorSystem) {
    val properties = new Properties()
    properties.put("name", system.name)
    context.registerService(classOf[ActorSystem].getName, system, properties.asInstanceOf[Dictionary[String, Any]])
  }

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
