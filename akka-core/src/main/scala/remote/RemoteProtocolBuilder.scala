/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.remote

import se.scalablesolutions.akka.serialization.{Serializer, Serializable}
import se.scalablesolutions.akka.remote.protocol.RemoteProtocol._

import com.google.protobuf.{Message, ByteString}

object RemoteProtocolBuilder {
  private var SERIALIZER_JAVA:       Serializer.Java      = Serializer.Java
  private var SERIALIZER_JAVA_JSON:  Serializer.JavaJSON  = Serializer.JavaJSON
  private var SERIALIZER_SCALA_JSON: Serializer.ScalaJSON = Serializer.ScalaJSON
  private var SERIALIZER_SBINARY:    Serializer.SBinary   = Serializer.SBinary
  private var SERIALIZER_PROTOBUF:   Serializer.Protobuf  = Serializer.Protobuf

  def setClassLoader(cl: ClassLoader) = {
    SERIALIZER_JAVA.classLoader       = Some(cl)
    SERIALIZER_JAVA_JSON.classLoader  = Some(cl)
    SERIALIZER_SCALA_JSON.classLoader = Some(cl)
    SERIALIZER_SBINARY.classLoader    = Some(cl)
  }

  def getMessage(request: RemoteRequestProtocol): Any = {
    request.getSerializationScheme match {
      case SerializationSchemeProtocol.JAVA =>
        unbox(SERIALIZER_JAVA.fromBinary(request.getMessage.toByteArray, None))
      case SerializationSchemeProtocol.SBINARY =>
        val classToLoad = new String(request.getMessageManifest.toByteArray)
        val clazz = if (SERIALIZER_SBINARY.classLoader.isDefined) SERIALIZER_SBINARY.classLoader.get.loadClass(classToLoad)
                    else Class.forName(classToLoad)
        val renderer = clazz.newInstance.asInstanceOf[Serializable.SBinary[_ <: AnyRef]]
        renderer.fromBytes(request.getMessage.toByteArray)
      case SerializationSchemeProtocol.SCALA_JSON =>
        val manifest = SERIALIZER_JAVA.fromBinary(request.getMessageManifest.toByteArray, None).asInstanceOf[String]
        SERIALIZER_SCALA_JSON.fromBinary(request.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationSchemeProtocol.JAVA_JSON =>
        val manifest = SERIALIZER_JAVA.fromBinary(request.getMessageManifest.toByteArray, None).asInstanceOf[String]
        SERIALIZER_JAVA_JSON.fromBinary(request.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationSchemeProtocol.PROTOBUF =>
        val messageClass = SERIALIZER_JAVA.fromBinary(request.getMessageManifest.toByteArray, None).asInstanceOf[Class[_]]
        SERIALIZER_PROTOBUF.fromBinary(request.getMessage.toByteArray, Some(messageClass))
    }
  }

  def getMessage(reply: RemoteReplyProtocol): Any = {
    reply.getSerializationScheme match {
      case SerializationSchemeProtocol.JAVA =>
        unbox(SERIALIZER_JAVA.fromBinary(reply.getMessage.toByteArray, None))
      case SerializationSchemeProtocol.SBINARY =>
        val classToLoad = new String(reply.getMessageManifest.toByteArray)
        val clazz = if (SERIALIZER_SBINARY.classLoader.isDefined) SERIALIZER_SBINARY.classLoader.get.loadClass(classToLoad)
                    else Class.forName(classToLoad)
        val renderer = clazz.newInstance.asInstanceOf[Serializable.SBinary[_ <: AnyRef]]
        renderer.fromBytes(reply.getMessage.toByteArray)
      case SerializationSchemeProtocol.SCALA_JSON =>
        val manifest = SERIALIZER_JAVA.fromBinary(reply.getMessageManifest.toByteArray, None).asInstanceOf[String]
        SERIALIZER_SCALA_JSON.fromBinary(reply.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationSchemeProtocol.JAVA_JSON =>
        val manifest = SERIALIZER_JAVA.fromBinary(reply.getMessageManifest.toByteArray, None).asInstanceOf[String]
        SERIALIZER_JAVA_JSON.fromBinary(reply.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationSchemeProtocol.PROTOBUF =>
        val messageClass = SERIALIZER_JAVA.fromBinary(reply.getMessageManifest.toByteArray, None).asInstanceOf[Class[_]]
        SERIALIZER_PROTOBUF.fromBinary(reply.getMessage.toByteArray, Some(messageClass))
    }
  }

  def setMessage(message: Any, builder: RemoteRequestProtocol.Builder) = {
    if (message.isInstanceOf[Serializable.SBinary[_]]) {
      val serializable = message.asInstanceOf[Serializable.SBinary[_ <: Any]]
      builder.setSerializationScheme(SerializationSchemeProtocol.SBINARY)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else if (message.isInstanceOf[Message]) {
      val serializable = message.asInstanceOf[Message]
      builder.setSerializationScheme(SerializationSchemeProtocol.PROTOBUF)
      builder.setMessage(ByteString.copyFrom(serializable.toByteArray))
      builder.setMessageManifest(ByteString.copyFrom(SERIALIZER_JAVA.toBinary(serializable.getClass)))
    } else if (message.isInstanceOf[Serializable.ScalaJSON]) {
      val serializable = message.asInstanceOf[Serializable.ScalaJSON]
      builder.setSerializationScheme(SerializationSchemeProtocol.SCALA_JSON)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else if (message.isInstanceOf[Serializable.JavaJSON]) {
      val serializable = message.asInstanceOf[Serializable.JavaJSON]
      builder.setSerializationScheme(SerializationSchemeProtocol.JAVA_JSON)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else {
      // default, e.g. if no protocol used explicitly then use Java serialization
      builder.setSerializationScheme(SerializationSchemeProtocol.JAVA)
      builder.setMessage(ByteString.copyFrom(SERIALIZER_JAVA.toBinary(box(message))))
    }
  }

  def setMessage(message: Any, builder: RemoteReplyProtocol.Builder) = {
    if (message.isInstanceOf[Serializable.SBinary[_]]) {
      val serializable = message.asInstanceOf[Serializable.SBinary[_ <: Any]]
      builder.setSerializationScheme(SerializationSchemeProtocol.SBINARY)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else if (message.isInstanceOf[Message]) {
      val serializable = message.asInstanceOf[Message]
      builder.setSerializationScheme(SerializationSchemeProtocol.PROTOBUF)
      builder.setMessage(ByteString.copyFrom(serializable.toByteArray))
      builder.setMessageManifest(ByteString.copyFrom(SERIALIZER_JAVA.toBinary(serializable.getClass)))
    } else if (message.isInstanceOf[Serializable.ScalaJSON]) {
      val serializable = message.asInstanceOf[Serializable.ScalaJSON]
      builder.setSerializationScheme(SerializationSchemeProtocol.SCALA_JSON)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else if (message.isInstanceOf[Serializable.JavaJSON]) {
      val serializable = message.asInstanceOf[Serializable.JavaJSON]
      builder.setSerializationScheme(SerializationSchemeProtocol.JAVA_JSON)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else {
      // default, e.g. if no protocol used explicitly then use Java serialization
      builder.setSerializationScheme(SerializationSchemeProtocol.JAVA)
      builder.setMessage(ByteString.copyFrom(SERIALIZER_JAVA.toBinary(box(message))))
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
