/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.osgi

import impl.BundleDelegatingClassLoader
import akka.actor.ActorSystem
import com.typesafe.config.{ ConfigFactory, Config }
import org.osgi.framework.BundleContext
import java.io.File

/**
 * Factory class to create ActorSystem implementations in an OSGi environment.  This mainly involves dealing with
 * bundle classloaders appropriately to ensure that configuration files and classes get loaded properly
 */
class OsgiActorSystemFactory(val context: BundleContext, val fallbackClassLoader: Option[ClassLoader]) {

  /*
   * Classloader that delegates to the bundle for which the factory is creating an ActorSystem
   */
  private val classloader = new BundleDelegatingClassLoader(context.getBundle, fallbackClassLoader)

  /**
   * Creates the [[akka.actor.ActorSystem]], using the name specified
   */
  def createActorSystem(name: String): ActorSystem = createActorSystem(Option(name))

  /**
   * Creates the [[akka.actor.ActorSystem]], using the name specified.
   *
   * A default name (`bundle-<bundle id>-ActorSystem`) is assigned when you pass along [[scala.None]] instead.
   */
  def createActorSystem(name: Option[String]): ActorSystem =
    ActorSystem(actorSystemName(name), actorSystemConfig(context), classloader)

  /**
   * Strategy method to create the Config for the ActorSystem, based on configuration files found in etc directory,
   * ensuring that the default/reference configuration is loaded from the akka-actor bundle.
   * The configuration is based on
   * etc/bundle-SYMBOLICNAME, etc/bundle-ID, etc/akka.conf
   * in either ".conf", ".properties", ".json" formats
   * Configuration files found in akka-actor bundle
   */
  def actorSystemConfig(context: BundleContext): Config = {
    val bundleSymbolicName = context.getBundle.getSymbolicName
    val bundleId = context.getBundle.getBundleId
    val acceptedFilePath = List(s"bundle-$bundleSymbolicName", s"bundle-$bundleId", "akka").map(x =>  s"etc/$x")
    val applicationConfiguration = acceptedFilePath.foldLeft(ConfigFactory.empty())((x, y) => x.withFallback(ConfigFactory.parseFileAnySyntax(new File(y))))
    applicationConfiguration.withFallback(ConfigFactory.load(classloader).withFallback(ConfigFactory.defaultReference(OsgiActorSystemFactory.akkaActorClassLoader)))
  }

  /**
   * Determine the name for the [[akka.actor.ActorSystem]]
   * Returns a default value of `bundle-<bundle id>-ActorSystem` is no name is being specified
   */
  def actorSystemName(name: Option[String]): String =
    name.getOrElse("bundle-%s-ActorSystem".format(context.getBundle.getBundleId))

}

object OsgiActorSystemFactory {
  /**
   * Class loader of akka-actor bundle.
   */
  def akkaActorClassLoader = classOf[ActorSystem].getClassLoader

  /*
   * Create an [[OsgiActorSystemFactory]] instance to set up Akka in an OSGi environment
   */
  def apply(context: BundleContext): OsgiActorSystemFactory = new OsgiActorSystemFactory(context, Some(akkaActorClassLoader))
}
