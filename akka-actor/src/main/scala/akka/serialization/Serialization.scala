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

  def serialize(o: AnyRef): Either[Exception, Array[Byte]] = serializerFor(o.getClass) match {
    case Left(ex)          ⇒ Left(ex)
    case Right(serializer) ⇒ Right(serializer.toBinary(o))
  }

  def deserialize(
    bytes: Array[Byte],
    clazz: Class[_],
    classLoader: Option[ClassLoader]): Either[Exception, AnyRef] =
    serializerFor(clazz) match {
      case Left(ex)          ⇒ Left(ex)
      case Right(serializer) ⇒ Right(serializer.fromBinary(bytes, Some(clazz), classLoader))
    }

  def serializerFor(clazz: Class[_]): Either[Exception, Serializer] = {
    serializerMap.get(clazz.getName) match {
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

  private def defaultSerializer = serializers.get("default") match {
    case Some(ser: String) ⇒
      getClassFor(ser) match {
        case Right(serializer) ⇒ Some(serializer.newInstance.asInstanceOf[Serializer])
        case Left(exception)   ⇒ None
      }
    case None ⇒ None
  }

  private def getSerializerInstanceForBestMatchClass(cl: Class[_]) = bindings match {
    case Some(mappings) ⇒ mappings find {
      case (clazzName, ser) ⇒
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
    case None ⇒ Left(NoSerializerFoundException("No mapping serializer found for " + cl))
  }

  //TODO: Add type and docs
  val serializers = config.getSection("akka.actor.serializers").map(_.map).getOrElse(Map("default" -> "akka.serialization.JavaSerializer"))

  //TODO: Add type and docs
  val bindings = config.getSection("akka.actor.serialization-bindings")
    .map(_.map)
    .map(m ⇒ Map() ++ m.map { case (k, v: List[String]) ⇒ Map() ++ v.map((_, k)) }.flatten)

  //TODO: Add type and docs
  val serializerMap = bindings.map(m ⇒ m.map { case (k, v: String) ⇒ (k, serializers(v)) }).getOrElse(Map())
}
