package akka.osgi

import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor.ActorSystem
import org.osgi.framework.{ BundleContext, BundleActivator }
import java.util.Properties

/**
 * Abstract {@link BundleActivator} implementation to bootstrap and configure an {@link ActorSystem} in an
 * OSGi environment.
 */
abstract class ActorSystemActivator(nameFor: (BundleContext) ⇒ Option[String]) extends BundleActivator {

  def this() = this({ context: BundleContext ⇒ None })
  def this(name: String) = this({ context: BundleContext ⇒ Some(name) })

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
    system = OsgiActorSystemFactory(context).createActorSystem(nameFor(context))
    configure(system)
  }

  /**
   * Shuts down the ActorSystem when the bundle is stopped.
   *
   * @param context the BundleContext
   */
  def stop(context: BundleContext) {
    if (system != null) {
      system.shutdown()
      system = null
    }
  }

}
