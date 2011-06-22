/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.serialization

import akka.util.ReflectiveAccess._
import akka.config.Config
import akka.config.Config._
import akka.actor.{ ActorRef, Actor }
import akka.AkkaException

/**
 * Serialization module. Contains methods for serialization and deserialization as well as
 * locating a Serializer for a particular class as defined in the mapping in the 'akka.conf' file.
 */
object Serialization {
  case class NoSerializerFoundException(m: String) extends AkkaException(m)

  def serialize(o: AnyRef): Either[Exception, Array[Byte]] =
    serializerFor(o.getClass).fold((ex) ⇒ Left(ex), (ser) ⇒ Right(ser.toBinary(o)))

  def deserialize(
    bytes: Array[Byte],
    clazz: Class[_],
    classLoader: Option[ClassLoader]): Either[Exception, AnyRef] =
    serializerFor(clazz)
      .fold((ex) ⇒ Left(ex),
        (ser) ⇒ Right(ser.fromBinary(bytes, Some(clazz), classLoader)))

  def serializerFor(clazz: Class[_]): Either[Exception, Serializer] = {
    Config.serializerMap.get(clazz.getName) match {
      case Some(serializerName: String) ⇒
        getClassFor(serializerName) match {
          case Right(serializer) ⇒ Right(serializer.newInstance.asInstanceOf[Serializer])
          case Left(exception)   ⇒ Left(exception)
        }
      case _ ⇒
        defaultSerializer match {
          case Some(s: Serializer) ⇒ Right(s)
          case None                ⇒ Left(NoSerializerFoundException("No default serializer found for " + clazz))
        }
    }
  }

  private def defaultSerializer = {
    Config.serializers.get("default") match {
      case Some(ser: String) ⇒
        getClassFor(ser) match {
          case Right(srializer) ⇒ Some(srializer.newInstance.asInstanceOf[Serializer])
          case Left(exception)  ⇒ None
        }
      case None ⇒ None
    }
  }

  private def getSerializerInstanceForBestMatchClass(
    configMap: collection.mutable.Map[String, String],
    cl: Class[_]) = {
    configMap
      .find {
        case (clazzName, ser) ⇒
          getClassFor(clazzName) match {
            case Right(clazz) ⇒ clazz.isAssignableFrom(cl)
            case _            ⇒ false
          }
      }
      .map {
        case (_, ser) ⇒
          getClassFor(ser) match {
            case Right(s) ⇒ Right(s.newInstance.asInstanceOf[Serializer])
            case _        ⇒ Left(new Exception("Error instantiating " + ser))
          }
      }.getOrElse(Left(NoSerializerFoundException("No mapping serializer found for " + cl)))
  }
}
