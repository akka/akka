/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import akka.AkkaException
import akka.util.ReflectiveAccess
import akka.actor.{ ActorSystem, ActorSystemImpl }
import scala.util.DynamicVariable

case class NoSerializerFoundException(m: String) extends AkkaException(m)

/**
 * Serialization module. Contains methods for serialization and deserialization as well as
 * locating a Serializer for a particular class as defined in the mapping in the 'akka.conf' file.
 */
class Serialization(val system: ActorSystemImpl) {

  //TODO document me
  def serialize(o: AnyRef): Either[Exception, Array[Byte]] =
    try { Right(findSerializerFor(o).toBinary(o)) } catch { case e: Exception ⇒ Left(e) }

  //TODO document me
  def deserialize(
    bytes: Array[Byte],
    clazz: Class[_],
    classLoader: Option[ClassLoader]): Either[Exception, AnyRef] =
    try {
      Serialization.system.withValue(system) {
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

  // serializers and bindings needs to be lazy because Serialization is initialized from SerializationExtension, which is needed here

  /**
   * A Map of serializer from alias to implementation (class implementing akka.serialization.Serializer)
   * By default always contains the following mapping: "default" -> akka.serialization.JavaSerializer
   * But "default" can be overridden in config
   */
  lazy val serializers: Map[String, Serializer] = {
    val serializersConf = SerializationExtension(system).settings.Serializers
    for ((k: String, v: String) ← serializersConf)
      yield k -> serializerOf(v).fold(throw _, identity)
  }

  /**
   *  bindings is a Map whose keys = FQN of class that is serializable and values = the alias of the serializer to be used
   */
  lazy val bindings: Map[String, String] = {
    val configBindings = SerializationExtension(system).settings.SerializationBindings
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

object Serialization {
  // TODO ensure that these are always set (i.e. withValue()) when doing deserialization
  val system = new DynamicVariable[ActorSystemImpl](null)
}

