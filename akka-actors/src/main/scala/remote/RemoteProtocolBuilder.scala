/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.remote

import se.scalablesolutions.akka.serialization.Serializable.SBinary
import se.scalablesolutions.akka.serialization.{Serializer, Serializable, SerializationProtocol}
import se.scalablesolutions.akka.remote.protobuf.RemoteProtocol.{RemoteRequest, RemoteReply}

import com.google.protobuf.{Message, ByteString}

object RemoteProtocolBuilder {
  def getMessage(request: RemoteRequest): Any = {
    request.getProtocol match {
      case SerializationProtocol.SBINARY =>
        val renderer = Class.forName(new String(request.getMessageManifest.toByteArray)).newInstance.asInstanceOf[SBinary[_ <: AnyRef]]
        renderer.fromBytes(request.getMessage.toByteArray)
      case SerializationProtocol.SCALA_JSON =>
        val manifest = Serializer.Java.in(request.getMessageManifest.toByteArray, None).asInstanceOf[String]
        Serializer.ScalaJSON.in(request.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationProtocol.JAVA_JSON =>
        val manifest = Serializer.Java.in(request.getMessageManifest.toByteArray, None).asInstanceOf[String]
        Serializer.JavaJSON.in(request.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationProtocol.PROTOBUF =>
        val messageClass = Serializer.Java.in(request.getMessageManifest.toByteArray, None).asInstanceOf[Class[_]]
        Serializer.Protobuf.in(request.getMessage.toByteArray, Some(messageClass))
      case SerializationProtocol.JAVA =>
        unbox(Serializer.Java.in(request.getMessage.toByteArray, None))
      case SerializationProtocol.AVRO =>
        throw new UnsupportedOperationException("Avro protocol is not yet supported")
    }
  }

  def getMessage(reply: RemoteReply): Any = {
    reply.getProtocol match {
      case SerializationProtocol.SBINARY =>
        val renderer = Class.forName(new String(reply.getMessageManifest.toByteArray)).newInstance.asInstanceOf[SBinary[_ <: AnyRef]]
        renderer.fromBytes(reply.getMessage.toByteArray)
      case SerializationProtocol.SCALA_JSON =>
        val manifest = Serializer.Java.in(reply.getMessageManifest.toByteArray, None).asInstanceOf[String]
        Serializer.ScalaJSON.in(reply.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationProtocol.JAVA_JSON =>
        val manifest = Serializer.Java.in(reply.getMessageManifest.toByteArray, None).asInstanceOf[String]
        Serializer.JavaJSON.in(reply.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationProtocol.PROTOBUF =>
        val messageClass = Serializer.Java.in(reply.getMessageManifest.toByteArray, None).asInstanceOf[Class[_]]
        Serializer.Protobuf.in(reply.getMessage.toByteArray, Some(messageClass))
      case SerializationProtocol.JAVA =>
        unbox(Serializer.Java.in(reply.getMessage.toByteArray, None))
      case SerializationProtocol.AVRO =>
        throw new UnsupportedOperationException("Avro protocol is not yet supported")
    }
  }

  def setMessage(message: Any, builder: RemoteRequest.Builder) = {
    if (message.isInstanceOf[Serializable.SBinary[_]]) {
      val serializable = message.asInstanceOf[Serializable.SBinary[_ <: Any]]
      builder.setProtocol(SerializationProtocol.SBINARY)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else if (message.isInstanceOf[Message]) {
      val serializable = message.asInstanceOf[Message]
      builder.setProtocol(SerializationProtocol.PROTOBUF)
      builder.setMessage(ByteString.copyFrom(serializable.toByteArray))
      builder.setMessageManifest(ByteString.copyFrom(Serializer.Java.out(serializable.getClass)))
    } else if (message.isInstanceOf[Serializable.ScalaJSON]) {
      val serializable = message.asInstanceOf[Serializable.ScalaJSON]
      builder.setProtocol(SerializationProtocol.SCALA_JSON)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else if (message.isInstanceOf[Serializable.JavaJSON]) {
      val serializable = message.asInstanceOf[Serializable.JavaJSON]
      builder.setProtocol(SerializationProtocol.JAVA_JSON)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else {
      // default, e.g. if no protocol used explicitly then use Java serialization
      builder.setProtocol(SerializationProtocol.JAVA)
      builder.setMessage(ByteString.copyFrom(Serializer.Java.out(box(message))))
    }
  }

  def setMessage(message: Any, builder: RemoteReply.Builder) = {
    if (message.isInstanceOf[Serializable.SBinary[_]]) {
      val serializable = message.asInstanceOf[Serializable.SBinary[_ <: Any]]
      builder.setProtocol(SerializationProtocol.SBINARY)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else if (message.isInstanceOf[Message]) {
      val serializable = message.asInstanceOf[Message]
      builder.setProtocol(SerializationProtocol.PROTOBUF)
      builder.setMessage(ByteString.copyFrom(serializable.toByteArray))
      builder.setMessageManifest(ByteString.copyFrom(Serializer.Java.out(serializable.getClass)))
    } else if (message.isInstanceOf[Serializable.ScalaJSON]) {
      val serializable = message.asInstanceOf[Serializable.ScalaJSON]
      builder.setProtocol(SerializationProtocol.SCALA_JSON)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else if (message.isInstanceOf[Serializable.JavaJSON]) {
      val serializable = message.asInstanceOf[Serializable.JavaJSON]
      builder.setProtocol(SerializationProtocol.JAVA_JSON)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else {
      // default, e.g. if no protocol used explicitly then use Java serialization
      builder.setProtocol(SerializationProtocol.JAVA)
      builder.setMessage(ByteString.copyFrom(Serializer.Java.out(box(message))))
    }
  }

  private def box(value: Any): AnyRef = value match {
    case value: Boolean => new java.lang.Boolean(value)
    case value: Char => new java.lang.Character(value)
    case value: Short => new java.lang.Short(value)
    case value: Int => new java.lang.Integer(value)
    case value: Long => new java.lang.Long(value)
    case value: Float => new java.lang.Float(value)
    case value: Double => new java.lang.Double(value)
    case value: Byte => new java.lang.Byte(value)
    case value => value.asInstanceOf[AnyRef]
  }

  private def unbox(value: AnyRef): Any = value match {
    case value: java.lang.Boolean => value.booleanValue
    case value: java.lang.Character => value.charValue
    case value: java.lang.Short => value.shortValue
    case value: java.lang.Integer => value.intValue
    case value: java.lang.Long => value.longValue
    case value: java.lang.Float => value.floatValue
    case value: java.lang.Double => value.doubleValue
    case value: java.lang.Byte => value.byteValue
    case value => value
  }

}
