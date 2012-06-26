package akka.osgi

import akka.actor.ActorSystem
import java.util.{ Dictionary, Properties }
import org.osgi.framework.{ ServiceRegistration, BundleContext, BundleActivator }

/**
 * Abstract {@link BundleActivator} implementation to bootstrap and configure an {@link ActorSystem} in an
 * OSGi environment.
 */
abstract class ActorSystemActivator(nameFor: (BundleContext) ⇒ Option[String]) extends BundleActivator {

  def this() = this({ context: BundleContext ⇒ None })
  def this(name: String) = this({ context: BundleContext ⇒ Some(name) })

  var system: Option[ActorSystem] = None
  var registration: Option[ServiceRegistration] = None

  /**
   * Implement this method to add your own actors to the ActorSystem
   *
   * @param context the bundle context
   * @param system the ActorSystem that was created by the activator
   */
  def configure(context: BundleContext, system: ActorSystem)

  /**
   * Sets up a new ActorSystem and registers it in the OSGi Service Registry
   *
   * @param context the BundleContext
   */
  def start(context: BundleContext) {
    system = Some(OsgiActorSystemFactory(context).createActorSystem(nameFor(context)))
    system.foreach(configure(context, _))
  }

  /**
   * Shuts down the ActorSystem when the bundle is stopped and, if necessary, unregisters a service registration
   *
   * @param context the BundleContext
   */
  def stop(context: BundleContext) {
    registration.foreach(_.unregister())
    system.foreach(_.shutdown())
  }

  /**
   * Register the actor system in the OSGi service registry
   *
   * @param context the bundle context
   * @param system the actor system
   */
  def registerService(context: BundleContext, system: ActorSystem) {
    val properties = new Properties()
    properties.put("name", system.name)
    registration = Some(context.registerService(classOf[ActorSystem].getName, system,
      properties.asInstanceOf[Dictionary[String, Any]]))
  }

}
