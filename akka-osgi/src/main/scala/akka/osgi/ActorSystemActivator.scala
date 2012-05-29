package akka.osgi

import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor.ActorSystem
import org.osgi.framework.{ BundleContext, BundleActivator }
import java.util.Properties

/**
 * Abstract {@link BundleActivator} implementation to bootstrap and configure an {@link ActorSystem} in an
 * OSGi environment.
 */
abstract class ActorSystemActivator extends BundleActivator {

  var system: ActorSystem = null

  /**
   * Implement this method to add your own actors to the ActorSystem
   *
   * @param system the ActorSystem that was created by the activator
   */
  def configure(system: ActorSystem)

  /**
   * Sets up a new ActorSystem and registers it in the OSGi Service Registry
   *
   * @param context the BundleContext
   */
  def start(context: BundleContext) {
    system = createActorSystem(context)
    configure(system)

    val properties = new Properties();
    properties.put("name", getActorSystemName(context))
    context.registerService(classOf[ActorSystem].getName, system, properties)
  }

  /**
   * Shuts down the ActorSystem when the bundle is stopped.
   *
   * @param context the BundleContext
   */
  def stop(context: BundleContext) {
    if (system != null) {
      system.shutdown()
      system.shutdown()
      system = null
    }
  }

  /**
   * Strategy method to create the ActorSystem.
   */
  def createActorSystem(context: BundleContext) =
    ActorSystem(getActorSystemName(context), getActorSystemConfig(context), getClass.getClassLoader)


  /**
   * Strategy method to create the Config for the ActorSystem, ensuring that the default/reference configuration is
   * loaded from the akka-actor bundle.
   */
  def getActorSystemConfig(context: BundleContext): Config = {
    val reference = ConfigFactory.defaultReference(classOf[ActorSystem].getClassLoader)
    ConfigFactory.load(getClass.getClassLoader).withFallback(reference)
  }

  /**
   * Strategy method to determine the ActorSystem name - override this method to define the ActorSytem name yourself.
   *
   * The default implementation will use 'bundle-<id>-ActorSystem' where <id> matches the bundle id for the containing bundle.
   *
   * @param context the BundleContext
   * @return the ActorSystem name
   */
  def getActorSystemName(context: BundleContext): String = {
    "bundle-%s-ActorSystem".format(context.getBundle().getBundleId)
  }

}
