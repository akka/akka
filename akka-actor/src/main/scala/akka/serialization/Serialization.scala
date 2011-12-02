/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import akka.AkkaException
import akka.util.ReflectiveAccess
import scala.util.DynamicVariable
import com.typesafe.config.Config
import akka.config.ConfigurationException
import akka.actor.{ Extension, ActorSystem, ActorSystemImpl }

case class NoSerializerFoundException(m: String) extends AkkaException(m)

object Serialization {

  // TODO ensure that these are always set (i.e. withValue()) when doing deserialization
  val currentSystem = new DynamicVariable[ActorSystemImpl](null)

  class Settings(val config: Config) {

    import scala.collection.JavaConverters._
    import config._

    val Serializers: Map[String, String] = {
      toStringMap(getConfig("akka.actor.serializers"))
    }

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

    private def toStringMap(mapConfig: Config): Map[String, String] =
      mapConfig.root.unwrapped.asScala.toMap.map { case (k, v) ⇒ (k, v.toString) }
  }
}

/**
 * Serialization module. Contains methods for serialization and deserialization as well as
 * locating a Serializer for a particular class as defined in the mapping in the 'akka.conf' file.
 */
class Serialization(val system: ActorSystemImpl) extends Extension {
  import Serialization._

  val settings = new Settings(system.settings.config)

  //TODO document me
  def serialize(o: AnyRef): Either[Exception, Array[Byte]] =
    try { Right(findSerializerFor(o).toBinary(o)) } catch { case e: Exception ⇒ Left(e) }

  //TODO document me
  def deserialize(
    bytes: Array[Byte],
    clazz: Class[_],
    classLoader: Option[ClassLoader]): Either[Exception, AnyRef] =
    try {
      currentSystem.withValue(system) {
        Right(serializerFor(clazz).fromBinary(bytes, Some(clazz), classLoader))
      }
    } catch { case e: Exception ⇒ Left(e) }

  def findSerializerFor(o: AnyRef): Serializer = o match {
    case null  ⇒ NullSerializer
    case other ⇒ serializerFor(other.getClass)
  }

  //TODO document me
  def serializerFor(clazz: Class[_]): Serializer = //TODO fall back on BestMatchClass THEN default AND memoize the lookups
    serializerMap.get(clazz.getName).getOrElse(serializers("default"))

  /**
   * Tries to load the specified Serializer by the FQN
   */
  def serializerOf(serializerFQN: String): Either[Exception, Serializer] =
    ReflectiveAccess.createInstance(serializerFQN, ReflectiveAccess.noParams, ReflectiveAccess.noArgs)

  private def serializerForBestMatchClass(cl: Class[_]): Either[Exception, Serializer] = {
    if (bindings.isEmpty)
      Left(NoSerializerFoundException("No mapping serializer found for " + cl))
    else {
      bindings find {
        case (clazzName, _) ⇒
          ReflectiveAccess.getClassFor(clazzName) match {
            case Right(clazz) ⇒ clazz.isAssignableFrom(cl)
            case _            ⇒ false
          }
      } map {
        case (_, ser) ⇒ serializerOf(ser)
      } getOrElse Left(NoSerializerFoundException("No mapping serializer found for " + cl))
    }
  }

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
    val configBindings = settings.SerializationBindings
    configBindings.foldLeft(Map[String, String]()) {
      case (result, (k: String, vs: Seq[_])) ⇒
        //All keys which are lists, take the Strings from them and Map them
        result ++ (vs collect { case v: String ⇒ (v, k) })
      case (result, x) ⇒
        //For any other values, just skip them
        result
    }
  }

  /**
   * serializerMap is a Map whose keys = FQN of class that is serializable and values = the FQN of the serializer to be used for that class
   */
  lazy val serializerMap: Map[String, Serializer] = bindings mapValues serializers

  /**
   * Maps from a Serializer.Identifier (Byte) to a Serializer instance (optimization)
   */
  lazy val serializerByIdentity: Map[Serializer.Identifier, Serializer] =
    Map(NullSerializer.identifier -> NullSerializer) ++ serializers map { case (_, v) ⇒ (v.identifier, v) }
}

