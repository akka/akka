/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import com.typesafe.config.Config
import akka.actor.{ Extension, ExtendedActorSystem, Address }
import akka.event.Logging
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable.ArrayBuffer
import java.io.NotSerializableException
import scala.util.{ Try, DynamicVariable }
import scala.collection.immutable

object Serialization {

  /**
   * Tuple that represents mapping from Class to Serializer
   */
  type ClassSerializer = (Class[_], Serializer)

  /**
   * This holds a reference to the current transport address to be inserted
   * into local actor refs during serialization.
   */
  val currentTransportAddress = new DynamicVariable[Address](null)

  class Settings(val config: Config) {
    val Serializers: Map[String, String] = configToMap("akka.actor.serializers")
    val SerializationBindings: Map[String, String] = configToMap("akka.actor.serialization-bindings")

    private final def configToMap(path: String): Map[String, String] = {
      import scala.collection.JavaConverters._
      config.getConfig(path).root.unwrapped.asScala.mapValues(_.toString).toMap
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
   * using the optional type hint to the Serializer and the optional ClassLoader ot load it into.
   * Returns either the resulting object or an Exception if one was thrown.
   */
  def deserialize(bytes: Array[Byte], serializerId: Int, clazz: Option[Class[_]]): Try[AnyRef] =
    Try(serializerByIdentity(serializerId).fromBinary(bytes, clazz))

  /**
   * Deserializes the given array of bytes using the specified type to look up what Serializer should be used.
   * You can specify an optional ClassLoader to load the object into.
   * Returns either the resulting object or an Exception if one was thrown.
   */
  def deserialize(bytes: Array[Byte], clazz: Class[_]): Try[AnyRef] =
    Try(serializerFor(clazz).fromBinary(bytes, Some(clazz)))

  /**
   * Returns the Serializer configured for the given object, returns the NullSerializer if it's null.
   *
   * @throws akka.ConfigurationException if no `serialization-bindings` is configured for the
   *   class of the object
   */
  def findSerializerFor(o: AnyRef): Serializer = o match {
    case null  ⇒ NullSerializer
    case other ⇒ serializerFor(other.getClass)
  }

  /**
   * Returns the configured Serializer for the given Class. The configured Serializer
   * is used if the configured class `isAssignableFrom` from the `clazz`, i.e.
   * the configured class is a super class or implemented interface. In case of
   * ambiguity it is primarily using the most specific configured class,
   * and secondly the entry configured first.
   *
   * @throws java.io.NotSerializableException if no `serialization-bindings` is configured for the class
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
          case null ⇒ log.debug("Using serializer[{}] for message [{}]", ser.getClass.getName, clazz.getName); ser
          case some ⇒ some
        }
      case ser ⇒ ser
    }

  /**
   * Tries to load the specified Serializer by the fully-qualified name; the actual
   * loading is performed by the system’s [[akka.actor.DynamicAccess]].
   */
  def serializerOf(serializerFQN: String): Try[Serializer] =
    system.dynamicAccess.createInstanceFor[Serializer](serializerFQN, List(classOf[ExtendedActorSystem] -> system)) recoverWith {
      case _ ⇒ system.dynamicAccess.createInstanceFor[Serializer](serializerFQN, Nil)
    }

  /**
   * A Map of serializer from alias to implementation (class implementing akka.serialization.Serializer)
   * By default always contains the following mapping: "java" -> akka.serialization.JavaSerializer
   */
  private val serializers: Map[String, Serializer] =
    for ((k: String, v: String) ← settings.Serializers) yield k -> serializerOf(v).get

  /**
   *  bindings is a Seq of tuple representing the mapping from Class to Serializer.
   *  It is primarily ordered by the most specific classes first, and secondly in the configured order.
   */
  private[akka] val bindings: immutable.Seq[ClassSerializer] =
    sort(for ((k: String, v: String) ← settings.SerializationBindings if v != "none") yield (system.dynamicAccess.getClassFor[Any](k).get, serializers(v))).to[immutable.Seq]

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
    Map(NullSerializer.identifier -> NullSerializer) ++ serializers map { case (_, v) ⇒ (v.identifier, v) }
}

