package akka.serialization

/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

import akka.util.ReflectiveAccess._
import akka.config.Config
import akka.config.Config._
import akka.actor.{ ActorRef, Actor }

object Serialization {
  case class NoSerializerFoundException(m: String) extends Exception(m)

  def serialize(o: AnyRef): Either[Exception, Array[Byte]] =
    getSerializer(o.getClass).fold((ex) ⇒ Left(ex), (ser) ⇒ Right(ser.toBinary(o)))

  def deserialize(
    bytes: Array[Byte],
    clazz: Class[_],
    classLoader: Option[ClassLoader]): Either[Exception, AnyRef] =
    getSerializer(clazz)
      .fold((ex) ⇒ Left(ex),
        (ser) ⇒ Right(ser.fromBinary(bytes, Some(clazz), classLoader)))

  def getSerializer(clazz: Class[_]): Either[Exception, Serializer] = {
    Config.serializerMap.get(clazz.getName) match {
      case Some(serializerName: String) ⇒
        getClassFor(serializerName) match {
          case Right(serializer) ⇒ Right(serializer.newInstance.asInstanceOf[Serializer])
          case Left(exception)   ⇒ Left(exception)
        }
      case _ ⇒
        getDefaultSerializer match {
          case Some(s: Serializer) ⇒ Right(s)
          case None                ⇒ Left(NoSerializerFoundException("No default serializer found for " + clazz))
        }
    }
  }

  private def getDefaultSerializer = {
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
