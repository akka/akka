/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.osgi.impl

import language.existentials

import java.net.URL
import java.util.Enumeration
import org.osgi.framework.{ BundleContext, Bundle }

/*
 * Companion object to create bundle delegating classloader instances
 */
object BundleDelegatingClassLoader {

  /*
   * Create a bundle delegating classloader for the bundle context's bundle
   */
  def apply(context: BundleContext): BundleDelegatingClassLoader = new BundleDelegatingClassLoader(context.getBundle)

}

/*
 * A bundle delegating classloader implemenation - this will try to load classes and resources from the bundle
 * specified first and if there's a classloader specified, that will be used as a fallback
 */
class BundleDelegatingClassLoader(bundle: Bundle, classLoader: Option[ClassLoader]) extends ClassLoader {

  def this(bundle: Bundle) = this(bundle, None)

  protected override def findClass(name: String): Class[_] = bundle.loadClass(name)

  protected override def findResource(name: String): URL =
    bundle.getResource(name) match {
      case null if classLoader.isDefined ⇒ classLoader.get.getResource(name)
      case result                        ⇒ result
    }

  @SuppressWarnings(Array("unchecked", "rawtypes"))
  protected override def findResources(name: String): Enumeration[URL] =
    bundle.getResources(name).asInstanceOf[Enumeration[URL]]

  protected override def loadClass(name: String, resolve: Boolean): Class[_] = {
    val clazz = try {
      try findClass(name) catch { case _: ClassNotFoundException if classLoader.isDefined ⇒ classLoader.get.loadClass(name) } // First fall back to secondary loader
    } catch {
      case cnfe: ClassNotFoundException ⇒
        throw new ClassNotFoundException("%s from bundle %s (%s)".format(name, bundle.getBundleId, bundle.getSymbolicName), cnfe) // IF we have no secondary loader or that failed as well, wrap and rethrow
    }

    if (resolve)
      resolveClass(clazz)

    clazz
  }

  override val toString: String = "BundleDelegatingClassLoader(%s)".format(bundle.getBundleId)
}

