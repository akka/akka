package akka.serialization

/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

import java.io.{ ObjectOutputStream, ByteArrayOutputStream, ByteArrayInputStream }
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import akka.util.ClassLoaderObjectInputStream
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
 * <b>Be sure to always use the </b>[[akka.actor.DynamicAccess]]<b> for loading classes!</b> This is necessary to
 * avoid strange match errors and inequalities which arise from different class loaders loading
 * the same class.
 */
trait Serializer {

  /**
   * Completely unique value to identify this implementation of Serializer, used to optimize network traffic.
   * Values from 0 to 16 are reserved for Akka internal usage.
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
 * A Serializer represents a bimap between an object and an array of bytes representing that object.
 *
 * For serialization of data that need to evolve over time the `SerializerWithStringManifest` is recommended instead
 * of [[Serializer]] because the manifest (type hint) is a `String` instead of a `Class`. That means
 * that the class can be moved/removed and the serializer can still deserialize old data by matching
 * on the `String`. This is especially useful for Akka Persistence.
 *
 * The manifest string can also encode a version number that can be used in `fromBinary` to
 * deserialize in different ways to migrate old data to new domain objects.
 *
 * If the data was originally serialized with [[Serializer]] and in a later version of the
 * system you change to `SerializerWithStringManifest` the manifest string will be the full class name if
 * you used `includeManifest=true`, otherwise it will be the empty string.
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
 * <b>Be sure to always use the </b>[[akka.actor.DynamicAccess]]<b> for loading classes!</b> This is necessary to
 * avoid strange match errors and inequalities which arise from different class loaders loading
 * the same class.
 */
abstract class SerializerWithStringManifest extends Serializer {

  /**
   * Completely unique value to identify this implementation of Serializer, used to optimize network traffic.
   * Values from 0 to 16 are reserved for Akka internal usage.
   */
  def identifier: Int

  final override def includeManifest: Boolean = true

  /**
   * Return the manifest (type hint) that will be provided in the fromBinary method.
   * Use `""` if manifest is not needed.
   */
  def manifest(o: AnyRef): String

  /**
   * Serializes the given object into an Array of Byte
   */
  def toBinary(o: AnyRef): Array[Byte]

  /**
   * Produces an object from an array of bytes, with an optional type-hint;
   * the class should be loaded using ActorSystem.dynamicAccess.
   *
   * It's recommended to throw `java.io.NotSerializableException` in `fromBinary`
   * if the manifest is unknown. This makes it possible to introduce new message
   * types and send them to nodes that don't know about them. This is typically
   * needed when performing rolling upgrades, i.e. running a cluster with mixed
   * versions for while. `NotSerializableException` is treated as a transient
   * problem in the TCP based remoting layer. The problem will be logged
   * and message is dropped. Other exceptions will tear down the TCP connection
   * because it can be an indication of corrupt bytes from the underlying transport.
   */
  def fromBinary(bytes: Array[Byte], manifest: String): AnyRef

  final def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val manifestString = manifest match {
      case Some(c) ⇒ c.getName
      case None    ⇒ ""
    }
    fromBinary(bytes, manifestString)
  }

}

/**
 * Serializer between an object and a `ByteBuffer` representing that object.
 *
 * Implementations should typically extend [[SerializerWithStringManifest]] and
 * in addition to the `ByteBuffer` based `toBinary` and `fromBinary` methods also
 * implement the array based `toBinary` and `fromBinary` methods. The array based
 * methods will be used when `ByteBuffer` is not used, e.g. in Akka Persistence.
 *
 * Note that the array based methods can for example be implemented by delegation
 * like this:
 * {{{
 *   // you need to know the maximum size in bytes of the serialized messages
 *   val pool = new akka.io.DirectByteBufferPool(defaultBufferSize = 1024 * 1024, maxPoolEntries = 10)
 *
 *
 *  // Implement this method for compatibility with `SerializerWithStringManifest`.
 *  override def toBinary(o: AnyRef): Array[Byte] = {
 *    val buf = pool.acquire()
 *    try {
 *      toBinary(o, buf)
 *      buf.flip()
 *      val bytes = Array.ofDim[Byte](buf.remaining)
 *      buf.get(bytes)
 *      bytes
 *    } finally {
 *      pool.release(buf)
 *    }
 *  }
 *
 *  // Implement this method for compatibility with `SerializerWithStringManifest`.
 *  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
 *    fromBinary(ByteBuffer.wrap(bytes), manifest)
 *
 * }}}
 */
//#ByteBufferSerializer
trait ByteBufferSerializer {

  /**
   * Serializes the given object into the `ByteBuffer`.
   */
  def toBinary(o: AnyRef, buf: ByteBuffer): Unit

  /**
   * Produces an object from a `ByteBuffer`, with an optional type-hint;
   * the class should be loaded using ActorSystem.dynamicAccess.
   */
  def fromBinary(buf: ByteBuffer, manifest: String): AnyRef

}
//#ByteBufferSerializer

/**
 *  Base serializer trait with serialization identifiers configuration contract,
 *  when globally unique serialization identifier is configured in the `reference.conf`.
 */
trait BaseSerializer extends Serializer {
  /**
   *  Actor system which is required by most serializer implementations.
   */
  def system: ExtendedActorSystem

  /**
   * Configuration namespace of serialization identifiers in the `reference.conf`.
   *
   * Each serializer implementation must have an entry in the following format:
   * `akka.actor.serialization-identifiers."FQCN" = ID`
   * where `FQCN` is fully qualified class name of the serializer implementation
   * and `ID` is globally unique serializer identifier number.
   */
  final val SerializationIdentifiers = "akka.actor.serialization-identifiers"

  /**
   * Globally unique serialization identifier configured in the `reference.conf`.
   *
   * See [[Serializer#identifier]].
   */
  override val identifier: Int = identifierFromConfig

  /**
   * INTERNAL API
   */
  private[akka] def identifierFromConfig: Int =
    system.settings.config.getInt(s"""${SerializationIdentifiers}."${getClass.getName}"""")
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
   *
   * or
   *
   * currentSystem.withValue(system, callable)
   */
  val currentSystem = new CurrentSystem
  final class CurrentSystem extends DynamicVariable[ExtendedActorSystem](null) {
    /**
     * Java API: invoke the callable with the current system being set to the given value for this thread.
     *
     * @param value - the current value under the call to callable.call()
     * @param callable - the operation to be performed
     * @return the result of callable.call()
     */
    def withValue[S](value: ExtendedActorSystem, callable: Callable[S]): S = super.withValue[S](value)(callable.call)
  }
}

/**
 * This Serializer uses standard Java Serialization
 */
class JavaSerializer(val system: ExtendedActorSystem) extends BaseSerializer {

  @deprecated("Use constructor with ExtendedActorSystem", "2.4")
  def this() = this(null)

  // TODO remove this when deprecated this() is removed
  override val identifier: Int =
    if (system eq null) 1
    else identifierFromConfig

  def includeManifest: Boolean = false

  def toBinary(o: AnyRef): Array[Byte] = {
    val bos = new ByteArrayOutputStream
    val out = new ObjectOutputStream(bos)
    JavaSerializer.currentSystem.withValue(system) { out.writeObject(o) }
    out.close()
    bos.toByteArray
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
    val in = new ClassLoaderObjectInputStream(system.dynamicAccess.classLoader, new ByteArrayInputStream(bytes))
    val obj = JavaSerializer.currentSystem.withValue(system) { in.readObject }
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
  def toBinary(o: AnyRef): Array[Byte] = nullAsBytes
  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = null
}

/**
 * This is a special Serializer that Serializes and deserializes byte arrays only,
 * (just returns the byte array unchanged/uncopied)
 */
class ByteArraySerializer(val system: ExtendedActorSystem) extends BaseSerializer with ByteBufferSerializer {

  @deprecated("Use constructor with ExtendedActorSystem", "2.4")
  def this() = this(null)

  // TODO remove this when deprecated this() is removed
  override val identifier: Int =
    if (system eq null) 4
    else identifierFromConfig

  def includeManifest: Boolean = false
  def toBinary(o: AnyRef): Array[Byte] = o match {
    case null           ⇒ null
    case o: Array[Byte] ⇒ o
    case other ⇒ throw new IllegalArgumentException(
      s"${getClass.getName} only serializes byte arrays, not [${other.getClass.getName}]")
  }
  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = bytes

  override def toBinary(o: AnyRef, buf: ByteBuffer): Unit =
    o match {
      case null               ⇒
      case bytes: Array[Byte] ⇒ buf.put(bytes)
      case other ⇒ throw new IllegalArgumentException(
        s"${getClass.getName} only serializes byte arrays, not [${other.getClass.getName}]")
    }

  override def fromBinary(buf: ByteBuffer, manifest: String): AnyRef = {
    val bytes = Array.ofDim[Byte](buf.remaining())
    buf.get(bytes)
    bytes
  }
}
