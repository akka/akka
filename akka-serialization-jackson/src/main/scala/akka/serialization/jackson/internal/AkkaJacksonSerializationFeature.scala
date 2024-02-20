/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson.internal

import akka.annotation.InternalApi
import akka.serialization.jackson.CborSerializable
import akka.serialization.jackson.JsonSerializable
import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeReflection

/**
 * INTERNAL API
 */
@InternalApi
final class AkkaJacksonSerializationFeature extends Feature {
  // Note: Scala stdlib APIs must be used very sparsely/carefully here to not get init-at-build errors

  private val debug = System.getProperty("akka.native-image.debug") == "true"

  override def beforeAnalysis(access: Feature.BeforeAnalysisAccess): Unit = {
    // Concrete message types (defined by users) that can be serializable must have a reflection entry and reflection
    // construction access so that Jackson can instantiate and set fields. The message classes will all
    // be used in user code, if not the message is never sent or received, so Graal will find them as reachable.
    // That makes it possible to auto-register reflection entries for all messages tagged with either of the two built in
    // marker traits.
    val jsonSerializable =
      access.findClassByName(classOf[JsonSerializable].getName)

    access.registerSubtypeReachabilityHandler(
      (_, subtype) =>
        if (subtype != null && !subtype.isInterface) {
          log("Registering JsonSerializable class: " + subtype.getName)
          RuntimeReflection.register(subtype)
          RuntimeReflection.register(subtype.getDeclaredConstructors: _*)
          RuntimeReflection.register(subtype.getDeclaredFields: _*)
        },
      jsonSerializable)

    val cborSerializable =
      access.findClassByName(classOf[CborSerializable].getName)

    access.registerSubtypeReachabilityHandler(
      (_, subtype) =>
        if (subtype != null && !subtype.isInterface) {
          log("Registering CborSerializable class: " + subtype.getName)
          RuntimeReflection.register(subtype)
          RuntimeReflection.register(subtype.getDeclaredConstructors: _*)
          RuntimeReflection.register(subtype.getDeclaredFields: _*)
        },
      cborSerializable)
  }

  private def log(msg: String): Unit = {
    if (debug)
      System.out.println("[DEBUG] [AkkaJacksonSerializationFeature] " + msg)
  }
}
