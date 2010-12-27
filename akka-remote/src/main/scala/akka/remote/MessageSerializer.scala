/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.remote

import akka.serialization.{Serializer, Serializable}
import akka.remote.protocol.RemoteProtocol._
import akka.util._

import com.google.protobuf.{Message, ByteString}

object MessageSerializer extends Logging {
  private def SERIALIZER_JAVA:       Serializer.Java      = Serializer.Java
  private def SERIALIZER_JAVA_JSON:  Serializer.JavaJSON  = Serializer.JavaJSON
  private def SERIALIZER_SCALA_JSON: Serializer.ScalaJSON = Serializer.ScalaJSON
  private def SERIALIZER_SBINARY:    Serializer.SBinary   = Serializer.SBinary
  private def SERIALIZER_PROTOBUF:   Serializer.Protobuf  = Serializer.Protobuf

  def setClassLoader(cl: ClassLoader) = {
    val someCl = Some(cl)
    SERIALIZER_JAVA.classLoader       = someCl
    SERIALIZER_JAVA_JSON.classLoader  = someCl
    SERIALIZER_SCALA_JSON.classLoader = someCl
    SERIALIZER_SBINARY.classLoader    = someCl
  }

  def deserialize(messageProtocol: MessageProtocol): Any = {
    messageProtocol.getSerializationScheme match {
      case SerializationSchemeType.JAVA =>
        unbox(SERIALIZER_JAVA.fromBinary(messageProtocol.getMessage.toByteArray, None))

      case SerializationSchemeType.PROTOBUF =>
        val clazz = loadManifest(SERIALIZER_PROTOBUF.classLoader, messageProtocol)
        SERIALIZER_PROTOBUF.fromBinary(messageProtocol.getMessage.toByteArray, Some(clazz))

      case SerializationSchemeType.SBINARY =>
        val clazz = loadManifest(SERIALIZER_SBINARY.classLoader, messageProtocol)
        val renderer = clazz.newInstance.asInstanceOf[Serializable.SBinary[_ <: AnyRef]]
        renderer.fromBytes(messageProtocol.getMessage.toByteArray)

      case SerializationSchemeType.SCALA_JSON =>
        val clazz = loadManifest(SERIALIZER_SCALA_JSON.classLoader, messageProtocol)
        val renderer = clazz.newInstance.asInstanceOf[Serializable.ScalaJSON[_]]
        renderer.fromBytes(messageProtocol.getMessage.toByteArray)

      case SerializationSchemeType.JAVA_JSON =>
        val clazz = loadManifest(SERIALIZER_JAVA_JSON.classLoader, messageProtocol)
        SERIALIZER_JAVA_JSON.fromBinary(messageProtocol.getMessage.toByteArray, Some(clazz))
    }
  }

  def serialize(message: Any): MessageProtocol = {
    val builder = MessageProtocol.newBuilder
    if (message.isInstanceOf[Message]) {
      val serializable = message.asInstanceOf[Message]
      builder.setSerializationScheme(SerializationSchemeType.PROTOBUF)
      builder.setMessage(ByteString.copyFrom(serializable.toByteArray))
      builder.setMessageManifest(ByteString.copyFromUtf8(serializable.getClass.getName))
    } else if (message.isInstanceOf[Serializable.ScalaJSON[_]]) {
      builder.setSerializationScheme(SerializationSchemeType.SCALA_JSON)
      setMessageAndManifest(builder, message.asInstanceOf[Serializable.ScalaJSON[_ <: Any]])
    } else if (message.isInstanceOf[Serializable.SBinary[_]]) {
      builder.setSerializationScheme(SerializationSchemeType.SBINARY)
      setMessageAndManifest(builder, message.asInstanceOf[Serializable.SBinary[_ <: Any]])
    } else if (message.isInstanceOf[Serializable.JavaJSON]) {
      builder.setSerializationScheme(SerializationSchemeType.JAVA_JSON)
      setMessageAndManifest(builder, message.asInstanceOf[Serializable.JavaJSON])
    } else {
      // default, e.g. if no protocol used explicitly then use Java serialization
      builder.setSerializationScheme(SerializationSchemeType.JAVA)
      builder.setMessage(ByteString.copyFrom(SERIALIZER_JAVA.toBinary(box(message))))
    }
    builder.build
  }

  private def loadManifest(classLoader: Option[ClassLoader], messageProtocol: MessageProtocol): Class[_] = {
    val manifest = messageProtocol.getMessageManifest.toStringUtf8
    if (classLoader.isDefined) classLoader.get.loadClass(manifest)
    else Class.forName(manifest)
  }

  private def setMessageAndManifest(builder: MessageProtocol.Builder, serializable: Serializable) = {
    builder.setMessage(ByteString.copyFrom(serializable.toBytes))
    builder.setMessageManifest(ByteString.copyFromUtf8(serializable.getClass.getName))
  }

  private def box(value: Any): AnyRef = value match {
    case value: Boolean => new java.lang.Boolean(value)
    case value: Char    => new java.lang.Character(value)
    case value: Short   => new java.lang.Short(value)
    case value: Int     => new java.lang.Integer(value)
    case value: Long    => new java.lang.Long(value)
    case value: Float   => new java.lang.Float(value)
    case value: Double  => new java.lang.Double(value)
    case value: Byte    => new java.lang.Byte(value)
    case value          => value.asInstanceOf[AnyRef]
  }

  private def unbox(value: AnyRef): Any = value match {
    case value: java.lang.Boolean   => value.booleanValue
    case value: java.lang.Character => value.charValue
    case value: java.lang.Short     => value.shortValue
    case value: java.lang.Integer   => value.intValue
    case value: java.lang.Long      => value.longValue
    case value: java.lang.Float     => value.floatValue
    case value: java.lang.Double    => value.doubleValue
    case value: java.lang.Byte      => value.byteValue
    case value                      => value
  }
}
