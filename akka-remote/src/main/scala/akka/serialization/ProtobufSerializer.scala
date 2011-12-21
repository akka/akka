/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import akka.serialization.Serializer
import com.google.protobuf.Message

class ProtobufSerializer extends Serializer {
  val ARRAY_OF_BYTE_ARRAY = Array[Class[_]](classOf[Array[Byte]])

  def identifier = 2: Byte

  def toBinary(obj: AnyRef): Array[Byte] = {
    if (!obj.isInstanceOf[Message]) throw new IllegalArgumentException(
      "Can't serialize a non-protobuf message using protobuf [" + obj + "]")
    obj.asInstanceOf[Message].toByteArray
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]], classLoader: Option[ClassLoader] = None): AnyRef = {
    if (!clazz.isDefined) throw new IllegalArgumentException(
      "Need a protobuf message class to be able to serialize bytes using protobuf")
    clazz.get.getDeclaredMethod("parseFrom", ARRAY_OF_BYTE_ARRAY: _*).invoke(null, bytes).asInstanceOf[Message]
  }
}