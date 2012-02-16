package akka.serialization

/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

import java.io.{ ObjectOutputStream, ByteArrayOutputStream, ObjectInputStream, ByteArrayInputStream }
import akka.util.ClassLoaderObjectInputStream
import akka.actor.DynamicAccess
import akka.actor.ExtendedActorSystem
import scala.util.DynamicVariable

/**
 * A Serializer represents a bimap between an object and an array of bytes representing that object.
 *
 * Serializers are loaded using reflection during [[akka.actor.ActorSystem]]
 * start-up, where two constructors are tried in order:
 *
 * <ul>
 * <li>taking exactly one argument of type [[akka.actor.ExtendedActorSystem]];
 * this should be the preferred one because all reflective loading of classes
 * during deserialization should use ExtendedActorSystem.dynamicAccess (see
 * [[akka.actor.DynamicAccess]]), and</li>
 * <li>without arguments, which is only an option if the serializer does not
 * load classes using reflection.</li>
 * </ul>
 *
 * <b>Be sure to always use the PropertyManager for loading classes!</b> This is necessary to
 * avoid strange match errors and inequalities which arise from different class loaders loading
 * the same class.
 */
trait Serializer {

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
   * Produces an object from an array of bytes, with an optional type-hint;
   * the class should be loaded using ActorSystem.dynamicAccess.
   */
  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef

  /**
   * Java API: deserialize without type hint
   */
  final def fromBinary(bytes: Array[Byte]): AnyRef = fromBinary(bytes, None)

  /**
   * Java API: deserialize with type hint
   */
  final def fromBinary(bytes: Array[Byte], clazz: Class[_]): AnyRef = fromBinary(bytes, Option(clazz))
}

/**
 * Java API for creating a Serializer: make sure to include a constructor which
 * takes exactly one argument of type [[akka.actor.ExtendedActorSystem]], because
 * that is the preferred constructor which will be invoked when reflectively instantiating
 * the JSerializer (also possible with empty constructor).
 */
abstract class JSerializer extends Serializer {
  final def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef =
    fromBinaryJava(bytes, manifest.orNull)

  /**
   * This method must be implemented, manifest may be null.
   */
  protected def fromBinaryJava(bytes: Array[Byte], manifest: Class[_]): AnyRef
}

object NullSerializer extends NullSerializer

object JavaSerializer {

  /**
   * This holds a reference to the current ActorSystem (the surrounding context)
   * during serialization and deserialization.
   *
   * If you are using Serializers yourself, outside of SerializationExtension,
   * you'll need to surround the serialization/deserialization with:
   *
   * currentSystem.withValue(system) {
   *   ...code...
   * }
   */
  val currentSystem = new DynamicVariable[ExtendedActorSystem](null)

}

/**
 * This Serializer uses standard Java Serialization
 */
class JavaSerializer(val system: ExtendedActorSystem) extends Serializer {

  def includeManifest: Boolean = false

  def identifier = 1

  def toBinary(o: AnyRef): Array[Byte] = {
    val bos = new ByteArrayOutputStream
    val out = new ObjectOutputStream(bos)
    out.writeObject(o)
    out.close()
    bos.toByteArray
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
    val in = new ClassLoaderObjectInputStream(system.dynamicAccess.classLoader, new ByteArrayInputStream(bytes))
    val obj = JavaSerializer.currentSystem.withValue(system) {
      in.readObject
    }
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
  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = null
}
