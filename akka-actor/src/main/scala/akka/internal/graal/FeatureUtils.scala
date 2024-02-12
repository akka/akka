/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.internal.graal

import akka.annotation.InternalApi
import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeReflection

/**
 * INTERNAL API
 */
@InternalApi
trait FeatureUtils {

  protected def log(text: String): Unit =
    System.out.println("[" + getClass.getSimpleName + "]: " + text)

  protected def registerClassForReflectiveConstruction(access: Feature.FeatureAccess, className: String): Unit = {
    val clazz = access.findClassByName(className)
    if (clazz ne null) {
      // allow class lookup
      RuntimeReflection.register(clazz)
      RuntimeReflection.registerClassLookup(clazz.getName)
      // allow reflective constructor call
      RuntimeReflection.registerAllDeclaredConstructors(clazz)
    } else {
      throw new IllegalArgumentException(s"Class not found [$className]")
    }
  }

  protected def registerSubclassesForReflectiveConstruction(
      access: Feature.BeforeAnalysisAccess,
      descriptiveName: String,
      clazz: Class[_]): Unit = {
    val accessClass = access.findClassByName(clazz.getName)
    access.registerSubtypeReachabilityHandler(
      (_, subtype) =>
        if (subtype != null && !subtype.isInterface) {
          // TODO investigate whether we should cache the one's we've already added or not?
          log(
            "Automatically registering " + descriptiveName + " class for reflective instantiation: " + subtype.getName)
          try {
            RuntimeReflection.register(subtype)
            // FIXME we could potentially filter further based on supported signatures
            RuntimeReflection.register(subtype.getDeclaredConstructors: _*)
          } catch {
            case ex: Throwable =>
              log("Failed registering " + subtype.getName)
              ex.printStackTrace()
              throw ex
          }
        },
      accessClass)
  }

  protected def registerForReflectiveFieldAccess(
      access: Feature.FeatureAccess,
      className: String,
      fieldNames: Seq[String]): Unit = {
    val clazz = access.findClassByName(className)
    if (clazz ne null) {
      RuntimeReflection.register(clazz)
      RuntimeReflection.registerClassLookup(className)
      fieldNames.foreach { fieldName =>
        RuntimeReflection.register(clazz.getDeclaredField(fieldName))
      }
    } else {
      throw new IllegalArgumentException(s"Class not found [$className]")
    }
  }
}
