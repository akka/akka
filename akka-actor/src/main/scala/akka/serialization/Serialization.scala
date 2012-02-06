/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import akka.AkkaException
import akka.util.ReflectiveAccess
import scala.util.DynamicVariable
import com.typesafe.config.Config
import akka.config.ConfigurationException
import akka.actor.{ Extension, ActorSystem, ExtendedActorSystem, Address }
import java.util.concurrent.ConcurrentHashMap
import akka.event.Logging

case class NoSerializerFoundException(m: String) extends AkkaException(m)

object Serialization {
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
  val currentSystem = new DynamicVariable[ActorSystem](null)

  /**
   * This holds a reference to the current transport address to be inserted
   * into local actor refs during serialization.
   */
  val currentTransportAddress = new DynamicVariable[Address](null)

  class Settings(val config: Config) {

    import scala.collection.JavaConverters._
    import config._

    val Serializers: Map[String, String] =
      getConfig("akka.actor.serializers").root.unwrapped.asScala.toMap.map { case (k, v) ⇒ (k, v.toString) }

    val SerializationBindings: Map[String, Seq[String]] = {
      val configPath = "akka.actor.serialization-bindings"
      hasPath(configPath) match {
        case false ⇒ Map()
        case true ⇒
          val serializationBindings: Map[String, Seq[String]] = getConfig(configPath).root.unwrapped.asScala.toMap.map {
            case (k: String, v: java.util.Collection[_]) ⇒ (k -> v.asScala.toSeq.asInstanceOf[Seq[String]])
            case invalid                                 ⇒ throw new ConfigurationException("Invalid serialization-bindings [%s]".format(invalid))
          }
          serializationBindings

      }
    }
  }
}

/**
 * Serialization module. Contains methods for serialization and deserialization as well as
 * locating a Serializer for a particular class as defined in the mapping in the 'akka.conf' file.
 */
class Serialization(val system: ExtendedActorSystem) extends Extension {
  import Serialization._

  val settings = new Settings(system.settings.config)
  val log = Logging(system, getClass.getName)

  /**
   * Serializes the given AnyRef/java.lang.Object according to the Serialization configuration
   * to either an Array of Bytes or an Exception if one was thrown.
   */
  def serialize(o: AnyRef): Either[Exception, Array[Byte]] =
    try { Right(findSerializerFor(o).toBinary(o)) } catch { case e: Exception ⇒ Left(e) }

  /**
   * Deserializes the given array of bytes using the specified serializer id,
   * using the optional type hint to the Serializer and the optional ClassLoader ot load it into.
   * Returns either the resulting object or an Exception if one was thrown.
   */
  def deserialize(bytes: Array[Byte],
                  serializerId: Int,
                  clazz: Option[Class[_]],
                  classLoader: ClassLoader): Either[Exception, AnyRef] =
    try {
      currentSystem.withValue(system) {
        Right(serializerByIdentity(serializerId).fromBinary(bytes, clazz, Some(classLoader)))
      }
    } catch { case e: Exception ⇒ Left(e) }

  /**
   * Deserializes the given array of bytes using the specified type to look up what Serializer should be used.
   * You can specify an optional ClassLoader to load the object into.
   * Returns either the resulting object or an Exception if one was thrown.
   */
  def deserialize(
    bytes: Array[Byte],
    clazz: Class[_],
    classLoader: Option[ClassLoader]): Either[Exception, AnyRef] =
    try {
      currentSystem.withValue(system) { Right(serializerFor(clazz).fromBinary(bytes, Some(clazz), classLoader)) }
    } catch { case e: Exception ⇒ Left(e) }

  /**
   * Returns the Serializer configured for the given object, returns the NullSerializer if it's null,
   * falls back to the Serializer named "default"
   */
  def findSerializerFor(o: AnyRef): Serializer = o match {
    case null  ⇒ NullSerializer
    case other ⇒ serializerFor(other.getClass)
  }

  /**
   * Returns the configured Serializer for the given Class, falls back to the Serializer named "default".
   * It traverses interfaces and super classes to find any configured Serializer that match
   * the class name.
   */
  def serializerFor(clazz: Class[_]): Serializer =
    if (bindings.isEmpty) {
      // quick path to default when no bindings are registered
      serializers("default")
    } else {

      def resolve(c: Class[_]): Option[Serializer] =
        serializerMap.get(c.getName) match {
          case null ⇒
            val classes = c.getInterfaces ++ Option(c.getSuperclass)
            classes.view map resolve collectFirst { case Some(x) ⇒ x }
          case x ⇒ Some(x)
        }

      serializerMap.get(clazz.getName) match {
        case null ⇒
          val ser = resolve(clazz).getOrElse(serializers("default"))
          // memorize the lookups for performance
          serializerMap.putIfAbsent(clazz.getName, ser) match {
            case null ⇒
              log.debug("Using serializer[{}] for message [{}]", ser.getClass.getName, clazz.getName)
              ser
            case some ⇒ some
          }
        case ser ⇒ ser
      }
    }

  /**
   * Tries to load the specified Serializer by the FQN
   */
  def serializerOf(serializerFQN: String): Either[Exception, Serializer] =
    ReflectiveAccess.createInstance(serializerFQN, ReflectiveAccess.noParams, ReflectiveAccess.noArgs)

  /**
   * A Map of serializer from alias to implementation (class implementing akka.serialization.Serializer)
   * By default always contains the following mapping: "default" -> akka.serialization.JavaSerializer
   * But "default" can be overridden in config
   */
  lazy val serializers: Map[String, Serializer] = {
    val serializersConf = settings.Serializers
    for ((k: String, v: String) ← serializersConf)
      yield k -> serializerOf(v).fold(throw _, identity)
  }

  /**
   *  bindings is a Map whose keys = FQN of class that is serializable and values = the alias of the serializer to be used
   */
  lazy val bindings: Map[String, String] = {
    settings.SerializationBindings.foldLeft(Map[String, String]()) {
      //All keys which are lists, take the Strings from them and Map them
      case (result, (k: String, vs: Seq[_])) ⇒ result ++ (vs collect { case v: String ⇒ (v, k) })
      //For any other values, just skip them
      case (result, _)                       ⇒ result
    }
  }

  /**
   * serializerMap is a Map whose keys = FQN of class that is serializable and values is the serializer to be used for that class
   */
  private lazy val serializerMap: ConcurrentHashMap[String, Serializer] = {
    val serializerMap = new ConcurrentHashMap[String, Serializer]
    for ((k, v) ← bindings) {
      serializerMap.put(k, serializers(v))
    }
    serializerMap
  }

  /**
   * Maps from a Serializer Identity (Int) to a Serializer instance (optimization)
   */
  lazy val serializerByIdentity: Map[Int, Serializer] =
    Map(NullSerializer.identifier -> NullSerializer) ++ serializers map { case (_, v) ⇒ (v.identifier, v) }
}

