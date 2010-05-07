/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.remote

import se.scalablesolutions.akka.serialization.Serializable.SBinary
import se.scalablesolutions.akka.serialization.{Serializer, Serializable, SerializationProtocol}
import se.scalablesolutions.akka.remote.protobuf.RemoteProtocol.{RemoteRequestProtocol, RemoteReplyProtocol}

import com.google.protobuf.{Message, ByteString}

object RemoteProtocolBuilder {
  private var SERIALIZER_JAVA: Serializer.Java = Serializer.Java
  private var SERIALIZER_JAVA_JSON: Serializer.JavaJSON = Serializer.JavaJSON
  private var SERIALIZER_SCALA_JSON: Serializer.ScalaJSON = Serializer.ScalaJSON
  private var SERIALIZER_SBINARY: Serializer.SBinary = Serializer.SBinary
  private var SERIALIZER_PROTOBUF: Serializer.Protobuf = Serializer.Protobuf

  def setClassLoader(cl: ClassLoader) = {
    SERIALIZER_JAVA.classLoader = Some(cl)
    SERIALIZER_JAVA_JSON.classLoader = Some(cl)
    SERIALIZER_SCALA_JSON.classLoader = Some(cl)
  }
  
  def getMessage(request: RemoteRequestProtocol): Any = {
    request.getProtocol match {
      case SerializationProtocol.JAVA =>
        unbox(SERIALIZER_JAVA.in(request.getMessage.toByteArray, None))
      case SerializationProtocol.SBINARY =>
        val renderer = Class.forName(new String(request.getMessageManifest.toByteArray)).newInstance.asInstanceOf[SBinary[_ <: AnyRef]]
        renderer.fromBytes(request.getMessage.toByteArray)
      case SerializationProtocol.SCALA_JSON =>
        val manifest = SERIALIZER_JAVA.in(request.getMessageManifest.toByteArray, None).asInstanceOf[String]
        SERIALIZER_SCALA_JSON.in(request.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationProtocol.JAVA_JSON =>
        val manifest = SERIALIZER_JAVA.in(request.getMessageManifest.toByteArray, None).asInstanceOf[String]
        SERIALIZER_JAVA_JSON.in(request.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationProtocol.PROTOBUF =>
        val messageClass = SERIALIZER_JAVA.in(request.getMessageManifest.toByteArray, None).asInstanceOf[Class[_]]
        SERIALIZER_PROTOBUF.in(request.getMessage.toByteArray, Some(messageClass))
    }
  }

  def getMessage(reply: RemoteReplyProtocol): Any = {
    reply.getProtocol match {
      case SerializationProtocol.JAVA =>
        unbox(SERIALIZER_JAVA.in(reply.getMessage.toByteArray, None))
      case SerializationProtocol.SBINARY =>
        val renderer = Class.forName(new String(reply.getMessageManifest.toByteArray)).newInstance.asInstanceOf[SBinary[_ <: AnyRef]]
        renderer.fromBytes(reply.getMessage.toByteArray)
      case SerializationProtocol.SCALA_JSON =>
        val manifest = SERIALIZER_JAVA.in(reply.getMessageManifest.toByteArray, None).asInstanceOf[String]
        SERIALIZER_SCALA_JSON.in(reply.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationProtocol.JAVA_JSON =>
        val manifest = SERIALIZER_JAVA.in(reply.getMessageManifest.toByteArray, None).asInstanceOf[String]
        SERIALIZER_JAVA_JSON.in(reply.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationProtocol.PROTOBUF =>
        val messageClass = SERIALIZER_JAVA.in(reply.getMessageManifest.toByteArray, None).asInstanceOf[Class[_]]
        SERIALIZER_PROTOBUF.in(reply.getMessage.toByteArray, Some(messageClass))
    }
  }

  def setMessage(message: Any, builder: RemoteRequestProtocol.Builder) = {
    if (message.isInstanceOf[Serializable.SBinary[_]]) {
      val serializable = message.asInstanceOf[Serializable.SBinary[_ <: Any]]
      builder.setProtocol(SerializationProtocol.SBINARY)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else if (message.isInstanceOf[Message]) {
      val serializable = message.asInstanceOf[Message]
      builder.setProtocol(SerializationProtocol.PROTOBUF)
      builder.setMessage(ByteString.copyFrom(serializable.toByteArray))
      builder.setMessageManifest(ByteString.copyFrom(SERIALIZER_JAVA.out(serializable.getClass)))
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
      builder.setMessage(ByteString.copyFrom(SERIALIZER_JAVA.out(box(message))))
    }
  }

  def setMessage(message: Any, builder: RemoteReplyProtocol.Builder) = {
    if (message.isInstanceOf[Serializable.SBinary[_]]) {
      val serializable = message.asInstanceOf[Serializable.SBinary[_ <: Any]]
      builder.setProtocol(SerializationProtocol.SBINARY)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else if (message.isInstanceOf[Message]) {
      val serializable = message.asInstanceOf[Message]
      builder.setProtocol(SerializationProtocol.PROTOBUF)
      builder.setMessage(ByteString.copyFrom(serializable.toByteArray))
      builder.setMessageManifest(ByteString.copyFrom(SERIALIZER_JAVA.out(serializable.getClass)))
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
      builder.setMessage(ByteString.copyFrom(SERIALIZER_JAVA.out(box(message))))
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
