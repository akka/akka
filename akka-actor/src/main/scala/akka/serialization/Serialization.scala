/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import akka.util.ReflectiveAccess._
import akka.config.Config
import akka.config.Config._
import akka.actor.{ ActorRef, Actor }
import akka.AkkaException


case class NoSerializerFoundException(m: String) extends AkkaException(m)

/**
 * Serialization module. Contains methods for serialization and deserialization as well as
 * locating a Serializer for a particular class as defined in the mapping in the 'akka.conf' file.
 */
object Serialization {

  def serialize(o: AnyRef): Either[Exception, Array[Byte]] = serializerFor(o.getClass) match {
    case Left(ex)          ⇒ Left(ex)
    case Right(serializer) ⇒ Right(serializer.toBinary(o))
  }

  def deserialize(
    bytes: Array[Byte],
    clazz: Class[_],
    classLoader: Option[ClassLoader]): Either[Exception, AnyRef] =
    serializerFor(clazz) match {
      case Left(e)         ⇒ Left(e)
      case Right(serializer) ⇒ Right(serializer.fromBinary(bytes, Some(clazz), classLoader))
    }

  def serializerFor(clazz: Class[_]): Either[Exception, Serializer] =
    getClassFor(serializerMap.get(clazz.getName).getOrElse(serializers("default"))) match {
      case Right(serializer) ⇒ Right(serializer.newInstance.asInstanceOf[Serializer])
      case Left(e) => Left(e)
    }

  private def getSerializerInstanceForBestMatchClass(cl: Class[_]): Either[Exception, Serializer] = {
    if (bindings.isEmpty)
      Left(NoSerializerFoundException("No mapping serializer found for " + cl))
    else {
     bindings find {
      case (clazzName, _) ⇒
        getClassFor(clazzName) match {
          case Right(clazz) ⇒ clazz.isAssignableFrom(cl)
          case _            ⇒ false
        }
      } map {
        case (_, ser) ⇒
          getClassFor(ser) match {
            case Right(s) ⇒ Right(s.newInstance.asInstanceOf[Serializer])
            case _        ⇒ Left(new Exception("Error instantiating " + ser))
          }
      } getOrElse Left(NoSerializerFoundException("No mapping serializer found for " + cl))
    }
  }

  /**
   * A Map of serializer from alias to implementation (FQN of a class implementing akka.serialization.Serializer)
   * By default always contains the following mapping: "default" -> "akka.serialization.JavaSerializer"
   * But "default" can be overridden in config
   */
  val serializers: Map[String, String] = config.getSection("akka.actor.serializers") map {
    _.map.foldLeft(Map("default" -> "akka.serialization.JavaSerializer")) {
      case (result, (k: String, v: String)) => result + (k -> v)
      case (result, _) => result
    }
  } getOrElse Map("default" -> "akka.serialization.JavaSerializer")

  /**
   *  bindings is a Map whose keys = FQN of class that is serializable and values = the alias of the serializer to be used
   */
  val bindings: Map[String, String] = config.getSection("akka.actor.serialization-bindings") map {
    _.map.foldLeft(Map[String,String]()) {
      case (result, (k: String, vs: List[_])) => result ++ (vs collect { case v: String => (v, k) }) //All keys which are lists, take the Strings from them and Map them
      case (result, _) => result //For any other values, just skip them, TODO: print out warnings?
    }
  } getOrElse Map()

  /**
   * serializerMap is a Map whose keys = FQN of class that is serializable and values = the FQN of the serializer to be used for that class
   */
  val serializerMap: Map[String, String] = bindings mapValues serializers
}
