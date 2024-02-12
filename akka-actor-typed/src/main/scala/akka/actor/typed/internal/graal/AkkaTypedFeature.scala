/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.graal

import akka.actor.typed.ExtensionId
import akka.internal.graal.FeatureUtils
import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeReflection

/**
 * Automatic configuration of Akka for native images.
 */
class AkkaTypedFeature extends Feature with FeatureUtils {
  // Note: Scala stdlib must not be used here

  override def beforeAnalysis(access: Feature.BeforeAnalysisAccess): Unit =
    try {
      registerExtensions(access)
    } catch {
      case ex: Throwable =>
        log("[ERROR] akka-actor-typed Graal Feature threw exception:")
        ex.printStackTrace()
    }

  private def registerExtensions(access: Feature.BeforeAnalysisAccess): Unit = {
    val extensionClass = access.findClassByName(classOf[ExtensionId[_]].getName)
    access.registerSubtypeReachabilityHandler(
      (_, subtype) =>
        if (subtype != null && !subtype.isInterface) {
          log("Automatically registering extension for reflective access: " + subtype.getName)
          RuntimeReflection.register(subtype)
          // FIXME do we need something for Java extensions?
          try {
            RuntimeReflection.register(subtype.getField("MODULE$"))
          } catch {
            case _: NoSuchFieldException => // not a scala extension
          }

        },
      extensionClass)
  }

}
