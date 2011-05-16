/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.serialization

import akka.actor.Actor

import java.io.{ObjectOutputStream, ByteArrayOutputStream, ObjectInputStream, ByteArrayInputStream}

/**
 * Type class definition for Actor Serialization
 */
trait FromBinary[T <: Actor] {
  def fromBinary(bytes: Array[Byte], act: T): T
}

trait ToBinary[T <: Actor] {
  def toBinary(t: T): Array[Byte]
}

// client needs to implement Format[] for the respective actor
trait Format[T <: Actor] extends FromBinary[T] with ToBinary[T]

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Serializer extends scala.Serializable {
  @volatile var classLoader: Option[ClassLoader] = None
  def deepClone(obj: AnyRef): AnyRef = fromBinary(toBinary(obj), Some(obj.getClass))

  def toBinary(obj: AnyRef): Array[Byte]
  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef
}

/**
 * A default implementation for a stateless actor
 *
 * Create a Format object with the client actor as the implementation of the type class
 *
 * <pre>
 * object BinaryFormatMyStatelessActor  {
 *   implicit object MyStatelessActorFormat extends StatelessActorFormat[MyStatelessActor]
 * }
 * </pre>
 */
trait StatelessActorFormat[T <: Actor] extends Format[T] with scala.Serializable {
  def fromBinary(bytes: Array[Byte], act: T) = act

  def toBinary(ac: T) = Array.empty[Byte]
}

/**
 * A default implementation of the type class for a Format that specifies a serializer
 *
 * Create a Format object with the client actor as the implementation of the type class and
 * a serializer object
 *
 * <pre>
 * object BinaryFormatMyJavaSerializableActor  {
 *   implicit object MyJavaSerializableActorFormat extends SerializerBasedActorFormat[MyJavaSerializableActor]  {
 *     val serializer = Serializers.Java
 * }
 * }
 * </pre>
 */
trait SerializerBasedActorFormat[T <: Actor] extends Format[T] with scala.Serializable {
  val serializer: Serializer

  def fromBinary(bytes: Array[Byte], act: T) = serializer.fromBinary(bytes, Some(act.getClass)).asInstanceOf[T]

  def toBinary(ac: T) = serializer.toBinary(ac)
}
