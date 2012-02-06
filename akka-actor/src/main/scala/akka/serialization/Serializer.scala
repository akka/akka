package akka.serialization

/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

import java.io.{ ObjectOutputStream, ByteArrayOutputStream, ObjectInputStream, ByteArrayInputStream }
import akka.util.ClassLoaderObjectInputStream

/**
 * A Serializer represents a bimap between an object and an array of bytes representing that object
 */
trait Serializer extends scala.Serializable {
  /**
   * Completely unique value to identify this implementation of Serializer, used to optimize network traffic
   * Values from 0 to 16 is reserved for Akka internal usage
   */
  def identifier: Int

  /**
   * Serializes the given object into an Array of Byte
   */
  def toBinary(o: AnyRef): Array[Byte]

  /**
   * Returns whether this serializer needs a manifest in the fromBinary method
   */
  def includeManifest: Boolean

  /**
   * Deserializes the given Array of Bytes into an AnyRef
   */
  def fromBinary(bytes: Array[Byte]): AnyRef = fromBinary(bytes, None, None)

  /**
   * Deserializes the given Array of Bytes into an AnyRef with an optional type hint
   */
  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = fromBinary(bytes, manifest, None)

  /**
   *  Produces an object from an array of bytes, with an optional type-hint and a classloader to load the class into
   */
  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]], classLoader: Option[ClassLoader]): AnyRef
}

/**
 * Java API for creating a Serializer
 */
abstract class JSerializer extends Serializer {
  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]] = None, classLoader: Option[ClassLoader] = None): AnyRef =
    fromBinary(bytes, manifest.orNull, classLoader.orNull)

  /**
   * This method should be overridden,
   * manifest and classLoader may be null.
   */
  def fromBinary(bytes: Array[Byte], manifest: Class[_], classLoader: ClassLoader): AnyRef
}

object JavaSerializer extends JavaSerializer
object NullSerializer extends NullSerializer

/**
 * This Serializer uses standard Java Serialization
 */
class JavaSerializer extends Serializer {

  def includeManifest: Boolean = false

  def identifier = 1

  def toBinary(o: AnyRef): Array[Byte] = {
    val bos = new ByteArrayOutputStream
    val out = new ObjectOutputStream(bos)
    out.writeObject(o)
    out.close()
    bos.toByteArray
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]] = None,
                 classLoader: Option[ClassLoader] = None): AnyRef = {
    val in =
      if (classLoader.isDefined) new ClassLoaderObjectInputStream(classLoader.get, new ByteArrayInputStream(bytes)) else
        new ObjectInputStream(new ByteArrayInputStream(bytes))
    val obj = in.readObject
    in.close()
    obj
  }
}

/**
 * This is a special Serializer that Serializes and deserializes nulls only
 */
class NullSerializer extends Serializer {
  val nullAsBytes = Array[Byte]()
  def includeManifest: Boolean = false
  def identifier = 0
  def toBinary(o: AnyRef) = nullAsBytes
  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]] = None, classLoader: Option[ClassLoader] = None): AnyRef = null
}
