/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.osgi

import language.existentials

import java.net.URL
import java.util.Enumeration
import org.osgi.framework.{ BundleContext, Bundle }
import scala.util.Try
import scala.io.Source
import org.osgi.framework.wiring.{ BundleRevision, BundleWire, BundleWiring }
import scala.collection.JavaConverters._

/*
 * Companion object to create bundle delegating ClassLoader instances
 */
object BundleDelegatingClassLoader {

  /*
   * Create a bundle delegating ClassLoader for the bundle context's bundle
   */
  def apply(context: BundleContext): BundleDelegatingClassLoader = new BundleDelegatingClassLoader(context.getBundle)

  def apply(context: BundleContext, fallBackCLassLoader: Option[ClassLoader]): BundleDelegatingClassLoader =
    fallBackCLassLoader.map(new BundleDelegatingClassLoader(context.getBundle, _)).
      getOrElse(new BundleDelegatingClassLoader(context.getBundle()))
}

/*
 * A bundle delegating ClassLoader implementation - this will try to load classes and resources from the bundle
 * and the bundles transitive dependencies. If there's a ClassLoader specified, that will be used as a fallback.
 */
class BundleDelegatingClassLoader(bundle: Bundle, fallBackClassLoader: ClassLoader = null) extends ClassLoader(fallBackClassLoader) {

  private val bundles = findTransitiveBundles(bundle)

  override def findClass(name: String): Class[_] = {
    val classOption = bundles.foldLeft(None: Option[Class[_]]) {
      (co, bundle) ⇒ co.orElse { Try { bundle.loadClass(name) }.toOption }
    }
    classOption.getOrElse(throw new ClassNotFoundException(name))
  }

  override def findResource(name: String): URL = {
    val resourceOption = bundles.foldLeft(None: Option[URL]) {
      (ro, bundle) ⇒ ro.orElse { Option(bundle.getResource(name)) }
    }
    resourceOption getOrElse getParent.getResource(name)
  }

  override def findResources(name: String): Enumeration[URL] = {
    val resources = bundles.flatMap {
      bundle ⇒ Option(bundle.getResources(name)).map { _.asScala.toList }.getOrElse(Nil)
    }
    java.util.Collections.enumeration(resources.asJava)
  }

  private def findTransitiveBundles(bundle: Bundle): Set[Bundle] = {
    @annotation.tailrec def process(processed: Set[Bundle], remaining: Set[Bundle]): Set[Bundle] = {
      if (remaining.isEmpty) {
        processed
      } else {
        val (b, rest) = (remaining.head, remaining.tail)
        if (processed contains b) {
          process(processed, rest)
        } else {
          val wiring = b.adapt(classOf[BundleWiring])
          val requiredWires: List[BundleWire] =
            wiring.getRequiredWires(BundleRevision.PACKAGE_NAMESPACE).asScala.toList
          val direct: Set[Bundle] = requiredWires.flatMap {
            wire ⇒ Option(wire.getProviderWiring) map { _.getBundle }
          }.toSet
          process(processed + b, rest ++ (direct -- processed))
        }
      }
    }
    val bundles = process(Set.empty, Set(bundle))
    println(s">>> ${bundle.getSymbolicName} found ${bundles.size} bundles\n${bundles.map(_.getSymbolicName)}")
    bundles
  }
}

