package akka.osgi.impl

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
  def createFor(context: BundleContext) = new BundleDelegatingClassLoader(context.getBundle)

}

/*
 * A bundle delegating classloader implemenation - this will try to load classes and resources from the bundle
 * specified first and if there's a classloader specified, that will be used as a fallback
 */
class BundleDelegatingClassLoader(bundle: Bundle, classLoader: Option[ClassLoader]) extends ClassLoader {

  def this(bundle: Bundle) = this(bundle, None)

  protected override def findClass(name: String): Class[_] = bundle.loadClass(name)

  protected override def findResource(name: String): URL = {
    val resource = bundle.getResource(name)
    classLoader match {
      case Some(loader) if resource == null ⇒ loader.getResource(name)
      case _                                ⇒ resource
    }
  }

  @SuppressWarnings(Array("unchecked", "rawtypes"))
  protected override def findResources(name: String): Enumeration[URL] =
    bundle.getResources(name).asInstanceOf[Enumeration[URL]]

  protected override def loadClass(name: String, resolve: Boolean): Class[_] = {
    val clazz = try {
      findClass(name)
    } catch {
      case cnfe: ClassNotFoundException ⇒ {
        classLoader match {
          case Some(loader) ⇒ loadClass(name, loader)
          case None         ⇒ rethrowClassNotFoundException(name, cnfe)
        }
      }
    }
    if (resolve) {
      resolveClass(clazz)
    }
    clazz
  }

  private def loadClass(name: String, classLoader: ClassLoader) =
    try {
      classLoader.loadClass(name)
    } catch {
      case cnfe: ClassNotFoundException ⇒ rethrowClassNotFoundException(name, cnfe)
    }

  private def rethrowClassNotFoundException(name: String, cnfe: ClassNotFoundException): Nothing =
    throw new ClassNotFoundException(name + " from bundle " + bundle.getBundleId + " (" + bundle.getSymbolicName + ")", cnfe)

  override def toString: String = String.format("BundleDelegatingClassLoader(%s)", bundle)

}

