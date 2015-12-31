/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
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
import scala.util.Success
import scala.util.Failure
import scala.annotation.tailrec

/*
 * Companion object to create bundle delegating ClassLoader instances
 */
object BundleDelegatingClassLoader {

  /*
   * Create a bundle delegating ClassLoader for the bundle context's bundle
   */
  def apply(context: BundleContext): BundleDelegatingClassLoader = new BundleDelegatingClassLoader(context.getBundle, null)

  def apply(context: BundleContext, fallBackCLassLoader: Option[ClassLoader]): BundleDelegatingClassLoader =
    new BundleDelegatingClassLoader(context.getBundle, fallBackCLassLoader.orNull)
}

/*
 * A bundle delegating ClassLoader implementation - this will try to load classes and resources from the bundle
 * and the bundles transitive dependencies. If there's a ClassLoader specified, that will be used as a fallback.
 */
class BundleDelegatingClassLoader(bundle: Bundle, fallBackClassLoader: ClassLoader) extends ClassLoader(fallBackClassLoader) {

  private val bundles = findTransitiveBundles(bundle)

  override def findClass(name: String): Class[_] = {
    @tailrec def find(remaining: Seq[Bundle]): Class[_] = {
      if (remaining.isEmpty) throw new ClassNotFoundException(name)
      else Try { remaining.head.loadClass(name) } match {
        case Success(cls) ⇒ cls
        case Failure(_)   ⇒ find(remaining.tail)
      }
    }
    find(bundles)
  }

  override def findResource(name: String): URL = {
    @tailrec def find(remaining: Seq[Bundle]): URL = {
      if (remaining.isEmpty) getParent.getResource(name)
      else Option { remaining.head.getResource(name) } match {
        case Some(r) ⇒ r
        case None    ⇒ find(remaining.tail)
      }
    }
    find(bundles)
  }

  override def findResources(name: String): Enumeration[URL] = {
    val resources = bundles.flatMap {
      bundle ⇒ Option(bundle.getResources(name)).map { _.asScala.toList }.getOrElse(Nil)
    }
    java.util.Collections.enumeration(resources.asJava)
  }

  private def findTransitiveBundles(bundle: Bundle): Seq[Bundle] = {
    @tailrec def process(processed: Seq[Bundle], remaining: Seq[Bundle]): Seq[Bundle] = {
      if (remaining.isEmpty) {
        processed
      } else {
        val (b, rest) = (remaining.head, remaining.tail)
        if (processed contains b) {
          process(processed, rest)
        } else {
          val wiring = b.adapt(classOf[BundleWiring])
          val direct: Seq[Bundle] =
            if (wiring == null) Seq.empty
            else {
              val requiredWires: Seq[BundleWire] =
                wiring.getRequiredWires(BundleRevision.PACKAGE_NAMESPACE).asScala.toSeq
              requiredWires.flatMap {
                wire ⇒ Option(wire.getProviderWiring) map { _.getBundle }
              }
            }
          process(Seq(b) ++ processed, rest ++ direct.diff(processed))
        }
      }
    }
    process(Seq.empty, Seq(bundle))
  }
}

