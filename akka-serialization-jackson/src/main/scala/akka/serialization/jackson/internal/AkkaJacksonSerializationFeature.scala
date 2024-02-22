/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson.internal

import akka.annotation.InternalApi
import akka.serialization.jackson.CborSerializable
import akka.serialization.jackson.JsonSerializable
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeReflection

import java.lang.reflect.Modifier
import java.util
import java.util.stream.Collectors

/**
 * INTERNAL API
 */
@InternalApi
final class AkkaJacksonSerializationFeature extends Feature {
  // Note: Scala stdlib APIs must be used very sparsely/carefully here to not get init-at-build errors
  // (Array#isEmpty and Array#size are a no no's, for example)

  private val debug = System.getProperty("akka.native-image.debug") == "true"

  private val alreadyRegisteredType = new java.util.HashSet[String]()

  override def beforeAnalysis(access: Feature.BeforeAnalysisAccess): Unit = {
    // Concrete message types (defined by users) that can be serializable must have a reflection entry and reflection
    // construction access so that Jackson can instantiate and set fields. The message classes will all
    // be used in user code, if not the message is never sent or received, so Graal will find them as reachable.
    // That makes it possible to auto-register reflection entries for all messages tagged with either of the two built in
    // marker traits.
    val jsonSerializable =
      access.findClassByName(classOf[JsonSerializable].getName)

    access.registerSubtypeReachabilityHandler({ (_, subtype) =>
      if (subtype != null) {
        registerTypeForJacksonSerialization(access, subtype)
      }
    }, jsonSerializable)

    val cborSerializable =
      access.findClassByName(classOf[CborSerializable].getName)

    access.registerSubtypeReachabilityHandler({ (_, subtype) =>
      if (subtype != null) {
        registerTypeForJacksonSerialization(access, subtype)
      }
    }, cborSerializable)

    val jsonStdSerializer = access.findClassByName(classOf[StdSerializer[_]].getName)
    access.registerSubtypeReachabilityHandler({ (_, subtype) =>
      if (subtype != null) {
        registerCustomJacksonSerializers(subtype)
      }
    }, jsonStdSerializer)

    val jsonStdDeserializer = access.findClassByName(classOf[StdDeserializer[_]].getName)
    access.registerSubtypeReachabilityHandler({ (_, subtype) =>
      if (subtype != null) {
        registerCustomJacksonSerializers(subtype)
      }
    }, jsonStdDeserializer)

  }

  private def registerTypeForJacksonSerialization(access: Feature.BeforeAnalysisAccess, clazz: Class[_]): Unit = {

    if (!alreadyRegisteredType.contains(clazz.getName) && clazz.getPackage == null || (!clazz.getPackage.getName
          .startsWith("java") && !clazz.getPackage.getName.startsWith("scala"))) {
      alreadyRegisteredType.add(clazz.getName)
      log("Registering for jackson serialization: " + clazz.getName)
      RuntimeReflection.register(clazz)
      RuntimeReflection.registerAllDeclaredFields(clazz)
      RuntimeReflection.registerAllDeclaredMethods(clazz)
      try {
        val scalaModuleField = clazz.getDeclaredField("MODULE$")
        RuntimeReflection.register(scalaModuleField)
        log("Registering Scala module for " + clazz.getName)
      } catch {
        case _: NoSuchFieldException => // no scala module, this is ok
      }

      // getters
      val scalaCaseClass = try {
        clazz.getMethod("productElementNames")
        true
      } catch {
        case _: NoSuchMethodException => false
      }
      util.Arrays
        .stream(clazz.getDeclaredMethods)
        .forEach(method =>
          if (method.getParameterTypes.length == 0 && isPossiblyGetter(scalaCaseClass, method.getName)) {
            log("Registering method " + clazz.getName + "." + method.getName)
            RuntimeReflection.register(method)
            RuntimeReflection.registerAsQueried(method)
          })

      RuntimeReflection.registerAllConstructors(clazz)
      RuntimeReflection.registerAllDeclaredConstructors(clazz)
      // we still need explicit register of each constructor for some reason
      util.Arrays.stream(clazz.getDeclaredConstructors).forEach { constructor =>
        // FIXME this could probably be more selective
        log(
          "Registering constructor " + clazz.getName + ".<init>(" + util.Arrays
            .stream(constructor.getParameters)
            .map(_.getName)
            .collect(Collectors.joining(", ")) + ")")
        RuntimeReflection.register(constructor)
        RuntimeReflection.registerAsQueried(constructor)
        RuntimeReflection.registerConstructorLookup(clazz, constructor.getParameterTypes: _*)
        // also register each constructor parameter type
        util.Arrays
          .stream(constructor.getParameterTypes)
          .forEach(parameterType => registerTypeForJacksonSerialization(access, parameterType))
      }

      if (!clazz.getName.endsWith("$")) {
        // check for companion object
        val companion = access.findClassByName(clazz.getName + "$")
        if (companion != null) {
          log("Registering companion object for " + clazz.getName)
          RuntimeReflection.register(companion)
          RuntimeReflection.registerAllMethods(companion)

          try {
            val scalaModuleField = companion.getDeclaredField("MODULE$")
            RuntimeReflection.register(scalaModuleField)
          } catch {
            case _: NoSuchFieldException => // no scala module, this is ok
          }
        }
      }

      if (classOf[scala.Enumeration].isAssignableFrom(clazz)) {
        try {
          log("Registering scala Enumeration " + clazz.getName)
          // access to $outer needed by Scala Jackson enumeration support
          util.Arrays
            .stream(clazz.getMethods)
            .forEach(method =>
              if (classOf[scala.Enumeration#Value].isAssignableFrom(method.getReturnType)) {
                log("Registering Scala Enumeration value " + clazz.getName + "." + method.getName)
                val outer = method.getReturnType.getDeclaredField(s"$$outer")
                RuntimeReflection.register(outer)
                RuntimeReflection.register(method)
              })
        } catch {
          case _: NoSuchFieldException =>
            log(s"failed to find $$outer field for Scala Enumeration")
        }
      }

    }
  }

  private def registerCustomJacksonSerializers(subtype: Class[_]): Unit = {
    if (subtype != null && !subtype.isInterface && !Modifier.isAbstract(subtype.getModifiers) && !subtype.getPackage.getName
          .startsWith("com.fasterxml.jackson") && !subtype.getPackage.getName.startsWith("akka")) {
      log("Registering custom Jackson JsonSerializer: " + subtype.getName)
      RuntimeReflection.register(subtype)
      RuntimeReflection.registerForReflectiveInstantiation(subtype)
    }
  }

  private def isPossiblyGetter(parentIsCaseClass: Boolean, methodName: String): Boolean = methodName match {
    case "toString"     => false
    case "hashCode"     => false
    case "writeReplace" => false
    // only filter if scala case class, for regular classes user may call legit fields some of these
    case "productPrefix" if parentIsCaseClass       => false
    case "productArity" if parentIsCaseClass        => false
    case "productElementNames" if parentIsCaseClass => false
    case "productIterator" if parentIsCaseClass     => false
    // scala case class copy default values
    case str if str.startsWith("copy$default") => false
    case _                                     => true
  }

  private def log(msg: String): Unit = {
    if (debug)
      System.out.println("[DEBUG] [AkkaJacksonSerializationFeature] " + msg)
  }
}
