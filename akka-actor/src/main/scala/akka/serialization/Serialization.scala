/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.serialization

import com.typesafe.config.Config
import akka.actor._
import akka.event.Logging
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable.ArrayBuffer
import java.io.NotSerializableException
import scala.util.{ Try, DynamicVariable, Failure }
import scala.collection.immutable
import scala.util.control.NonFatal
import scala.util.Success

object Serialization {

  /**
   * Tuple that represents mapping from Class to Serializer
   */
  type ClassSerializer = (Class[_], Serializer)

  /**
   * This holds a reference to the current transport serialization information used for
   * serializing local actor refs.
   * INTERNAL API
   */
  private[akka] val currentTransportInformation = new DynamicVariable[Information](null)

  class Settings(val config: Config) {
    val Serializers: Map[String, String] = configToMap("akka.actor.serializers")
    val SerializationBindings: Map[String, String] = configToMap("akka.actor.serialization-bindings")

    private final def configToMap(path: String): Map[String, String] = {
      import scala.collection.JavaConverters._
      config.getConfig(path).root.unwrapped.asScala.toMap map { case (k, v) ⇒ (k → v.toString) }
    }
  }

  /**
   * Serialization information needed for serializing local actor refs.
   * INTERNAL API
   */
  private[akka] final case class Information(address: Address, system: ActorSystem)

  /**
   * The serialized path of an actorRef, based on the current transport serialization information.
   * If there is no external address available for the requested address then the systems default
   * address will be used.
   */
  def serializedActorPath(actorRef: ActorRef): String = {
    val path = actorRef.path
    val originalSystem: ExtendedActorSystem = actorRef match {
      case a: ActorRefWithCell ⇒ a.underlying.system.asInstanceOf[ExtendedActorSystem]
      case _                   ⇒ null
    }
    Serialization.currentTransportInformation.value match {
      case null ⇒ originalSystem match {
        case null ⇒ path.toSerializationFormat
        case system ⇒
          try path.toSerializationFormatWithAddress(system.provider.getDefaultAddress)
          catch { case NonFatal(_) ⇒ path.toSerializationFormat }
      }
      case Information(address, system) ⇒
        if (originalSystem == null || originalSystem == system)
          path.toSerializationFormatWithAddress(address)
        else {
          val provider = originalSystem.provider
          path.toSerializationFormatWithAddress(provider.getExternalAddressFor(address).getOrElse(provider.getDefaultAddress))
        }
    }
  }
}

/**
 * Serialization module. Contains methods for serialization and deserialization as well as
 * locating a Serializer for a particular class as defined in the mapping in the configuration.
 */
class Serialization(val system: ExtendedActorSystem) extends Extension {
  import Serialization._

  val settings = new Settings(system.settings.config)
  val log = Logging(system, getClass.getName)

  /**
   * Serializes the given AnyRef/java.lang.Object according to the Serialization configuration
   * to either an Array of Bytes or an Exception if one was thrown.
   */
  def serialize(o: AnyRef): Try[Array[Byte]] = Try(findSerializerFor(o).toBinary(o))

  /**
   * Deserializes the given array of bytes using the specified serializer id,
   * using the optional type hint to the Serializer.
   * Returns either the resulting object or an Exception if one was thrown.
   */
  def deserialize[T](bytes: Array[Byte], serializerId: Int, clazz: Option[Class[_ <: T]]): Try[T] =
    Try {
      val serializer = try serializerByIdentity(serializerId) catch {
        case _: NoSuchElementException ⇒ throw new NotSerializableException(
          s"Cannot find serializer with id [$serializerId]. The most probable reason is that the configuration entry " +
            "akka.actor.serializers is not in synch between the two systems.")
      }
      serializer.fromBinary(bytes, clazz).asInstanceOf[T]
    }

  /**
   * Deserializes the given array of bytes using the specified serializer id,
   * using the optional type hint to the Serializer.
   * Returns either the resulting object or an Exception if one was thrown.
   */
  def deserialize(bytes: Array[Byte], serializerId: Int, manifest: String): Try[AnyRef] =
    Try {
      val serializer = try serializerByIdentity(serializerId) catch {
        case _: NoSuchElementException ⇒ throw new NotSerializableException(
          s"Cannot find serializer with id [$serializerId]. The most probable reason is that the configuration entry " +
            "akka.actor.serializers is not in synch between the two systems.")
      }
      serializer match {
        case s2: SerializerWithStringManifest ⇒ s2.fromBinary(bytes, manifest)
        case s1 ⇒
          if (manifest == "")
            s1.fromBinary(bytes, None)
          else {
            system.dynamicAccess.getClassFor[AnyRef](manifest) match {
              case Success(classManifest) ⇒
                s1.fromBinary(bytes, Some(classManifest))
              case Failure(e) ⇒
                throw new NotSerializableException(
                  s"Cannot find manifest class [$manifest] for serializer with id [$serializerId].")
            }
          }
      }
    }

  /**
   * Deserializes the given array of bytes using the specified type to look up what Serializer should be used.
   * Returns either the resulting object or an Exception if one was thrown.
   */
  def deserialize[T](bytes: Array[Byte], clazz: Class[T]): Try[T] =
    Try(serializerFor(clazz).fromBinary(bytes, Some(clazz)).asInstanceOf[T])

  /**
   * Returns the Serializer configured for the given object, returns the NullSerializer if it's null.
   *
   * Throws akka.ConfigurationException if no `serialization-bindings` is configured for the
   *   class of the object.
   */
  def findSerializerFor(o: AnyRef): Serializer =
    if (o eq null) NullSerializer else serializerFor(o.getClass)

  /**
   * Returns the configured Serializer for the given Class. The configured Serializer
   * is used if the configured class `isAssignableFrom` from the `clazz`, i.e.
   * the configured class is a super class or implemented interface. In case of
   * ambiguity it is primarily using the most specific configured class,
   * and secondly the entry configured first.
   *
   * Throws java.io.NotSerializableException if no `serialization-bindings` is configured for the class.
   */
  def serializerFor(clazz: Class[_]): Serializer =
    serializerMap.get(clazz) match {
      case null ⇒ // bindings are ordered from most specific to least specific
        def unique(possibilities: immutable.Seq[(Class[_], Serializer)]): Boolean =
          possibilities.size == 1 ||
            (possibilities forall (_._1 isAssignableFrom possibilities(0)._1)) ||
            (possibilities forall (_._2 == possibilities(0)._2))

        val ser = bindings filter { _._1 isAssignableFrom clazz } match {
          case Seq() ⇒
            throw new NotSerializableException("No configured serialization-bindings for class [%s]" format clazz.getName)
          case possibilities ⇒
            if (!unique(possibilities))
              log.warning("Multiple serializers found for " + clazz + ", choosing first: " + possibilities)
            possibilities(0)._2
        }
        serializerMap.putIfAbsent(clazz, ser) match {
          case null ⇒
            if (shouldWarnAboutJavaSerializer(clazz, ser)) {
              log.warning("Using the default Java serializer for class [{}] which is not recommended because of " +
                "performance implications. Use another serializer or disable this warning using the setting " +
                "'akka.actor.warn-about-java-serializer-usage'", clazz.getName)
            }
            log.debug("Using serializer[{}] for message [{}]", ser.getClass.getName, clazz.getName)
            ser
          case some ⇒ some
        }
      case ser ⇒ ser
    }

  /**
   * Tries to load the specified Serializer by the fully-qualified name; the actual
   * loading is performed by the system’s [[akka.actor.DynamicAccess]].
   */
  def serializerOf(serializerFQN: String): Try[Serializer] =
    system.dynamicAccess.createInstanceFor[Serializer](serializerFQN, List(classOf[ExtendedActorSystem] → system)) recoverWith {
      case _: NoSuchMethodException ⇒ system.dynamicAccess.createInstanceFor[Serializer](serializerFQN, Nil)
    }

  /**
   * A Map of serializer from alias to implementation (class implementing akka.serialization.Serializer)
   * By default always contains the following mapping: "java" -> akka.serialization.JavaSerializer
   */
  private val serializers: Map[String, Serializer] =
    for ((k: String, v: String) ← settings.Serializers) yield k → serializerOf(v).get

  /**
   *  bindings is a Seq of tuple representing the mapping from Class to Serializer.
   *  It is primarily ordered by the most specific classes first, and secondly in the configured order.
   */
  private[akka] val bindings: immutable.Seq[ClassSerializer] =
    sort(for ((k: String, v: String) ← settings.SerializationBindings if v != "none" && checkGoogleProtobuf(k))
      yield (system.dynamicAccess.getClassFor[Any](k).get, serializers(v))).to[immutable.Seq]

  // com.google.protobuf serialization binding is only used if the class can be loaded,
  // i.e. com.google.protobuf dependency has been added in the application project.
  // The reason for this special case is for backwards compatibility so that we still can
  // include "com.google.protobuf.GeneratedMessage" = proto in configured serialization-bindings.
  private def checkGoogleProtobuf(className: String): Boolean =
    (!className.startsWith("com.google.protobuf") || system.dynamicAccess.getClassFor[Any](className).isSuccess)

  /**
   * Sort so that subtypes always precede their supertypes, but without
   * obeying any order between unrelated subtypes (insert sort).
   */
  private def sort(in: Iterable[ClassSerializer]): immutable.Seq[ClassSerializer] =
    ((new ArrayBuffer[ClassSerializer](in.size) /: in) { (buf, ca) ⇒
      buf.indexWhere(_._1 isAssignableFrom ca._1) match {
        case -1 ⇒ buf append ca
        case x  ⇒ buf insert (x, ca)
      }
      buf
    }).to[immutable.Seq]

  /**
   * serializerMap is a Map whose keys is the class that is serializable and values is the serializer
   * to be used for that class.
   */
  private val serializerMap: ConcurrentHashMap[Class[_], Serializer] =
    (new ConcurrentHashMap[Class[_], Serializer] /: bindings) { case (map, (c, s)) ⇒ map.put(c, s); map }

  /**
   * Maps from a Serializer Identity (Int) to a Serializer instance (optimization)
   */
  val serializerByIdentity: Map[Int, Serializer] =
    Map(NullSerializer.identifier → NullSerializer) ++ serializers map { case (_, v) ⇒ (v.identifier, v) }

  private def shouldWarnAboutJavaSerializer(serializedClass: Class[_], serializer: Serializer) =
    settings.config.getBoolean("akka.actor.warn-about-java-serializer-usage") &&
      serializer.isInstanceOf[JavaSerializer] &&
      !serializedClass.getName.startsWith("akka.")

}

