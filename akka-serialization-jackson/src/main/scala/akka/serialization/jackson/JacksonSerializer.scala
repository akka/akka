/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.NotSerializableException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

import scala.annotation.tailrec
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.event.LogMarker
import akka.event.Logging
import akka.serialization.BaseSerializer
import akka.serialization.SerializationExtension
import akka.serialization.SerializerWithStringManifest
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.impl.SubTypeValidator
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.dataformat.smile.SmileFactory

/**
 * INTERNAL API
 */
@InternalApi private[akka] object JacksonSerializer {

  /**
   * Using the blacklist from Jackson databind of class names that shouldn't be allowed.
   * Not nice to depend on implementation details of Jackson, but good to use the same
   * list to automatically have the list updated when new classes are added in Jackson.
   */
  class GadgetClassBlacklist extends SubTypeValidator {

    private def defaultNoDeserClassNames: java.util.Set[String] =
      SubTypeValidator.DEFAULT_NO_DESER_CLASS_NAMES // it's has protected visibility

    private val prefixSpring: String = "org.springframework."
    private val prefixC3P0: String = "com.mchange.v2.c3p0."

    def isAllowedClassName(className: String): Boolean = {
      if (defaultNoDeserClassNames.contains(className))
        false
      else if (className.startsWith(prefixC3P0) && className.endsWith("DataSource"))
        false
      else
        true
    }

    def isAllowedClass(clazz: Class[_]): Boolean = {
      if (clazz.getName.startsWith(prefixSpring)) {
        isAllowedSpringClass(clazz)
      } else
        true
    }

    @tailrec private def isAllowedSpringClass(clazz: Class[_]): Boolean = {
      if (clazz == null || clazz.equals(classOf[java.lang.Object]))
        true
      else {
        val name = clazz.getSimpleName
        // looking for "AbstractBeanFactoryPointcutAdvisor" but no point to allow any is there?
        if ("AbstractPointcutAdvisor".equals(name)
            // ditto  for "FileSystemXmlApplicationContext": block all ApplicationContexts
            || "AbstractApplicationContext".equals(name))
          false
        else
          isAllowedSpringClass(clazz.getSuperclass)
      }
    }
  }

  val disallowedSerializationBindings: Set[Class[_]] =
    Set(classOf[java.io.Serializable], classOf[java.io.Serializable], classOf[java.lang.Comparable[_]])

}

object JacksonJsonSerializer {
  val Identifier = 31
}

/**
 * INTERNAL API: only public by configuration
 *
 * Akka serializer for Jackson with JSON.
 */
@InternalApi private[akka] final class JacksonJsonSerializer(system: ExtendedActorSystem)
    extends JacksonSerializer(
      system,
      JacksonObjectMapperProvider(system).getOrCreate(JacksonJsonSerializer.Identifier, None))

object JacksonSmileSerializer {
  val Identifier = 33
}

/**
 * INTERNAL API: only public by configuration
 *
 * Akka serializer for Jackson with Smile.
 */
@InternalApi private[akka] final class JacksonSmileSerializer(system: ExtendedActorSystem)
    extends JacksonSerializer(
      system,
      JacksonObjectMapperProvider(system).getOrCreate(JacksonSmileSerializer.Identifier, Some(new SmileFactory)))

object JacksonCborSerializer {
  val Identifier = 32
}

/**
 * INTERNAL API: only public by configuration
 *
 * Akka serializer for Jackson with CBOR.
 */
@InternalApi private[akka] final class JacksonCborSerializer(system: ExtendedActorSystem)
    extends JacksonSerializer(
      system,
      JacksonObjectMapperProvider(system).getOrCreate(JacksonCborSerializer.Identifier, Some(new CBORFactory)))

// FIXME Look into if we should support both Smile and CBOR, and what we should recommend if there is a choice.
//       Make dependencies optional/provided.

/**
 * INTERNAL API: Base class for Jackson serializers.
 *
 * Configuration in `akka.serialization.jackson` section.
 * It will load Jackson modules defined in configuration `jackson-modules`.
 *
 * It will compress the payload if the the payload is larger than the configured
 * `compress-larger-than` value.
 */
@InternalApi private[akka] abstract class JacksonSerializer(
    val system: ExtendedActorSystem,
    val objectMapper: ObjectMapper)
    extends SerializerWithStringManifest
    with BaseSerializer {
  import JacksonSerializer.GadgetClassBlacklist

  // FIXME it should be possible to implement ByteBufferSerializer as well, using Jackson's
  //       ByteBufferBackedOutputStream/ByteBufferBackedInputStream

  private val log = Logging.withMarker(system, getClass)
  private val conf = system.settings.config.getConfig("akka.serialization.jackson")
  private val isDebugEnabled = conf.getBoolean("verbose-debug-logging") && log.isDebugEnabled
  private final val BufferSize = 1024 * 4
  private val compressLargerThan: Long = conf.getBytes("compress-larger-than")
  private val migrations: Map[String, JacksonMigration] = {
    import scala.collection.JavaConverters._
    conf.getConfig("migrations").root.unwrapped.asScala.toMap.map {
      case (k, v) ⇒
        val transformer = system.dynamicAccess.createInstanceFor[JacksonMigration](v.toString, Nil).get
        k -> transformer
    }
  }
  private val blacklist: GadgetClassBlacklist = new GadgetClassBlacklist

  // This must lazy otherwise it will deadlock the ActorSystem creation
  private lazy val serialization = SerializationExtension(system)

  // doesn't have to be volatile, doesn't matter if check is run more than once
  private var serializationBindingsCheckedOk = false

  override def manifest(obj: AnyRef): String = {
    checkAllowedSerializationBindings()
    val className = obj.getClass.getName
    checkAllowedClassName(className)
    checkAllowedClass(obj.getClass)
    migrations.get(className) match {
      case Some(transformer) ⇒ className + "#" + transformer.currentVersion
      case None ⇒ className
    }
  }

  override def toBinary(obj: AnyRef): Array[Byte] = {
    checkAllowedSerializationBindings()
    val startTime = if (isDebugEnabled) System.nanoTime else 0L
    val bytes = objectMapper.writeValueAsBytes(obj)
    // FIXME investigate if compression should be used for the binary formats
    val result =
      if (bytes.length > compressLargerThan) compress(bytes)
      else bytes

    if (isDebugEnabled) {
      val durationMicros = (System.nanoTime - startTime) / 1000
      if (bytes.length == result.length)
        log.debug(
          "Serialization of [{}] took [{}] µs, size [{}] bytes",
          obj.getClass.getName,
          durationMicros,
          result.length)
      else
        log.debug(
          "Serialization of [{}] took [{}] µs, compressed size [{}] bytes, uncompressed size [{}] bytes",
          obj.getClass.getName,
          durationMicros,
          result.length,
          bytes.length)
    }

    result
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    checkAllowedSerializationBindings()
    val startTime = if (isDebugEnabled) System.nanoTime else 0L
    val compressed = isGZipped(bytes)

    val (fromVersion, manifestClassName) = parseManifest(manifest)
    checkAllowedClassName(manifestClassName)

    val migration = migrations.get(manifestClassName)

    val className = migration match {
      case Some(transformer) if fromVersion < transformer.currentVersion ⇒
        transformer.transformClassName(fromVersion, manifestClassName)
      case Some(transformer) if fromVersion > transformer.currentVersion ⇒
        throw new IllegalStateException(
          s"Migration version ${transformer.currentVersion} is " +
          s"behind version $fromVersion of deserialized type [$manifestClassName]")
      case _ ⇒ manifestClassName
    }
    if (className ne manifestClassName)
      checkAllowedClassName(className)

    val clazz = system.dynamicAccess.getClassFor[AnyRef](className) match {
      case Success(c) ⇒ c
      case Failure(_) ⇒
        throw new NotSerializableException(
          s"Cannot find manifest class [$className] for serializer [${getClass.getName}].")
    }
    checkAllowedClass(clazz)

    val decompressBytes = if (compressed) decompress(bytes) else bytes

    val result = migration match {
      case Some(transformer) if fromVersion < transformer.currentVersion ⇒
        val jsonTree = objectMapper.readTree(decompressBytes)
        val newJsonTree = transformer.transform(fromVersion, jsonTree)
        objectMapper.treeToValue(newJsonTree, clazz)
      case _ ⇒
        objectMapper.readValue(decompressBytes, clazz)
    }

    if (isDebugEnabled) {
      val durationMicros = (System.nanoTime - startTime) / 1000
      if (bytes.length == decompressBytes.length)
        log.debug(
          "Deserialization of [{}] took [{}] µs, size [{}] bytes",
          clazz.getName,
          durationMicros,
          decompressBytes.length)
      else
        log.debug(
          "Deserialization of [{}] took [{}] µs, compressed size [{}] bytes, uncompressed size [{}] bytes",
          clazz.getName,
          durationMicros,
          decompressBytes.length,
          bytes.length)
    }

    result
  }

  private def checkAllowedClassName(className: String): Unit = {
    if (!blacklist.isAllowedClassName(className)) {
      val warnMsg = s"Can't serialize/deserialize object of type [$className] in [${getClass.getName}]. " +
        s"Blacklisted for security reasons."
      log.warning(LogMarker.Security, warnMsg)
      throw new IllegalArgumentException(warnMsg)
    }
  }

  private def checkAllowedClass(clazz: Class[_]): Unit = {
    if (!blacklist.isAllowedClass(clazz)) {
      val warnMsg = s"Can't serialize/deserialize object of type [${clazz.getName}] in [${getClass.getName}]. " +
        s"Blacklisted for security reasons."
      log.warning(LogMarker.Security, warnMsg)
      throw new IllegalArgumentException(warnMsg)
    } else if (!isInWhitelist(clazz)) {
      val warnMsg = s"Can't serialize/deserialize object of type [${clazz.getName}] in [${getClass.getName}]. " +
        "Only classes that are whitelisted are allowed for security reasons. " +
        "Configure whitelist with akka.actor.serialization-bindings or " +
        "akka.serialization.jackson.whitelist-packages."
      log.warning(LogMarker.Security, warnMsg)
      throw new IllegalArgumentException(warnMsg)
    }
  }

  /**
   * Using the `serialization-bindings` as source for the whitelist.
   * Note that the intended usage of serialization-bindings is for lookup of
   * serializer when serializing (`toBinary`). For deserialization (`fromBinary`) the serializer-id is
   * used for selecting serializer.
   * Here we use `serialization-bindings` also and more importantly when deserializing (fromBinary)
   * to check that the manifest class is of a known (registered) type.
   * The drawback of using `serialization-bindings` for this is that an old class can't be removed
   * from `serialization-bindings` when it's not used for serialization but still used for
   * deserialization (e.g. rolling update with serialization changes). It's also
   * not possible to change a binding from a JacksonSerializer to another serializer (e.g. protobuf)
   * and still bind with the same class (interface).
   * If this is too limiting we can add another config property as an additional way to
   * whitelist classes that are not bound to this serializer with serialization-bindings.
   */
  private def isInWhitelist(clazz: Class[_]): Boolean = {
    try {
      // The reason for using isInstanceOf rather than `eq this` is to allow change of
      // serializizer within the Jackson family, but we don't trust other serializers
      // because they might be bound to open-ended interfaces like java.io.Serializable.
      val boundSerializer = serialization.serializerFor(clazz)
      boundSerializer.isInstanceOf[JacksonSerializer] ||
      // to support rolling updates in Lagom we also trust the binding to the Lagom 1.5.x JacksonJsonSerializer,
      // which is named OldJacksonJsonSerializer in Lagom 1.6.x
      // FIXME maybe make this configurable, but I don't see any other usages than for Lagom?
      boundSerializer.getClass.getName == "com.lightbend.lagom.internal.jackson.OldJacksonJsonSerializer"
    } catch {
      case NonFatal(_) => false // not bound
    }
  }

  /**
   * Check that serialization-bindings are not configured with open-ended interfaces,
   * like java.lang.Object, bound to this serializer.
   *
   * This check is run on first access since it can't be run from constructor because SerializationExtension
   * can't be accessed from there.
   */
  private def checkAllowedSerializationBindings(): Unit = {
    if (!serializationBindingsCheckedOk) {
      def isBindingOk(clazz: Class[_]): Boolean =
        try {
          serialization.serializerFor(clazz) ne this
        } catch {
          case NonFatal(_) => true // not bound
        }

      JacksonSerializer.disallowedSerializationBindings.foreach { clazz =>
        if (!isBindingOk(clazz)) {
          val warnMsg = "For security reasons it's not allowed to bind open-ended interfaces like " +
            s"[${clazz.getName}] to [${getClass.getName}]. " +
            "Change your akka.actor.serialization-bindings configuration."
          log.warning(LogMarker.Security, warnMsg)
          throw new IllegalArgumentException(warnMsg)
        }
      }
      serializationBindingsCheckedOk = true
    }
  }

  private def parseManifest(manifest: String) = {
    val i = manifest.lastIndexOf('#')
    val fromVersion = if (i == -1) 1 else manifest.substring(i + 1).toInt
    val manifestClassName = if (i == -1) manifest else manifest.substring(0, i)
    (fromVersion, manifestClassName)
  }

  def compress(bytes: Array[Byte]): Array[Byte] = {
    val bos = new ByteArrayOutputStream(BufferSize)
    val zip = new GZIPOutputStream(bos)
    try zip.write(bytes)
    finally zip.close()
    bos.toByteArray
  }

  def decompress(bytes: Array[Byte]): Array[Byte] = {
    val in = new GZIPInputStream(new ByteArrayInputStream(bytes))
    val out = new ByteArrayOutputStream()
    // FIXME pool of recycled buffers?
    val buffer = new Array[Byte](BufferSize)

    @tailrec def readChunk(): Unit = in.read(buffer) match {
      case -1 ⇒ ()
      case n ⇒
        out.write(buffer, 0, n)
        readChunk()
    }

    try readChunk()
    finally in.close()
    out.toByteArray
  }

  def isGZipped(bytes: Array[Byte]): Boolean = {
    (bytes != null) && (bytes.length >= 2) &&
    (bytes(0) == GZIPInputStream.GZIP_MAGIC.toByte) &&
    (bytes(1) == (GZIPInputStream.GZIP_MAGIC >> 8).toByte)
  }
}
