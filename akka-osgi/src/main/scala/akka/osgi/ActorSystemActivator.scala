/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.osgi

import akka.actor.ActorSystem
import java.util.{ Dictionary, Properties }
import org.osgi.framework.{ ServiceRegistration, BundleContext, BundleActivator }

/**
 * Abstract bundle activator implementation to bootstrap and configure an actor system in an
 * OSGi environment.  It also provides a convenience method to register the actor system in
 * the OSGi Service Registry for sharing it with other OSGi bundles.
 *
 * This convenience activator is mainly useful for setting up a single [[akka.actor.ActorSystem]] instance and sharing that
 * with other bundles in the OSGi Framework.  If you want to set up multiple systems in the same bundle context, look at
 * the [[akka.osgi.OsgiActorSystemFactory]] instead.
 */
abstract class ActorSystemActivator extends BundleActivator {

  private var system: Option[ActorSystem] = None
  private var registration: Option[ServiceRegistration] = None

  /**
   * Implement this method to add your own actors to the ActorSystem.  If you want to share the actor
   * system with other bundles, call the `registerService(BundleContext, ActorSystem)` method from within
   * this method.
   *
   * @param context the bundle context
   * @param system the ActorSystem that was created by the activator
   */
  def configure(context: BundleContext, system: ActorSystem): Unit

  /**
   * Sets up a new ActorSystem
   *
   * @param context the BundleContext
   */
  def start(context: BundleContext): Unit = {
    system = Some(OsgiActorSystemFactory(context).createActorSystem(Option(getActorSystemName(context))))
    system foreach (configure(context, _))
  }

  /**
   * Shuts down the ActorSystem when the bundle is stopped and, if necessary, unregisters a service registration.
   *
   * @param context the BundleContext
   */
  def stop(context: BundleContext): Unit = {
    registration foreach (_.unregister())
    system foreach (_.shutdown())
  }

  /**
   * Register the actor system in the OSGi service registry.  The activator itself will ensure that this service
   * is unregistered again when the bundle is being stopped.
   *
   * Only one ActorSystem can be registered at a time, so any previous registration will be unregistered prior to registering the new.
   *
   * @param context the bundle context
   * @param system the actor system
   */
  def registerService(context: BundleContext, system: ActorSystem): Unit = {
    registration.foreach(_.unregister()) //Cleanup
    val properties = new Properties()
    properties.put("name", system.name)
    registration = Some(context.registerService(classOf[ActorSystem].getName, system,
      properties.asInstanceOf[Dictionary[String, Any]]))
  }

  /**
   * By default, the [[akka.actor.ActorSystem]] name will be set to `bundle-<bundle id>-ActorSystem`.  Override this
   * method to define another name for your [[akka.actor.ActorSystem]] instance.
   *
   * @param context the bundle context
   * @return the actor system name
   */
  def getActorSystemName(context: BundleContext): String = null

}
