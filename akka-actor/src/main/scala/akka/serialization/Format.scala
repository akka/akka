package akka.serialization

/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

import akka.actor.Actor

/**
 * trait Serializer {
 * @volatile
 * var classLoader: Option[ClassLoader] = None
 * def deepClone(obj: AnyRef): AnyRef = fromBinary(toBinary(obj), Some(obj.getClass))
 *
 * def toBinary(obj: AnyRef): Array[Byte]
 * def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef
 * }
 */

/**
 *
 * object Format {
 * implicit object Default extends Serializer {
 * import java.io.{ ObjectOutputStream, ByteArrayOutputStream, ObjectInputStream, ByteArrayInputStream }
 * //import org.apache.commons.io.input.ClassLoaderObjectInputStream
 *
 * def toBinary(obj: AnyRef): Array[Byte] = {
 * val bos = new ByteArrayOutputStream
 * val out = new ObjectOutputStream(bos)
 * out.writeObject(obj)
 * out.close()
 * bos.toByteArray
 * }
 *
 * def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]], classLoader: Option[ClassLoader] = None): AnyRef = {
 * val in =
 * //if (classLoader.isDefined) new ClassLoaderObjectInputStream(classLoader.get, new ByteArrayInputStream(bytes)) else
 * new ObjectInputStream(new ByteArrayInputStream(bytes))
 * val obj = in.readObject
 * in.close()
 * obj
 * }
 *
 * def identifier: Byte = 111 //Pick a number and hope no one has chosen the same :-) 0 - 16 is reserved for Akka internals
 *
 * }
 *
 * val defaultSerializerName = Default.getClass.getName
 * }
 */

trait FromBinary[T <: Actor] {
  def fromBinary(bytes: Array[Byte], act: T): T
}

trait ToBinary[T <: Actor] {
  def toBinary(t: T): Array[Byte]
}

/**
 * Type class definition for Actor Serialization.
 * Client needs to implement Format[] for the respective actor.
 */
trait Format[T <: Actor] extends FromBinary[T] with ToBinary[T]

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
trait StatelessActorFormat[T <: Actor] extends Format[T] {
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
 *   }
 * }
 * </pre>
 */
trait SerializerBasedActorFormat[T <: Actor] extends Format[T] {
  val serializer: Serializer

  def fromBinary(bytes: Array[Byte], act: T) = serializer.fromBinary(bytes, Some(act.getClass)).asInstanceOf[T]

  def toBinary(ac: T) = serializer.toBinary(ac)
}
