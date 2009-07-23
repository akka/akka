/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.nio

import akka.serialization.Serializable.SBinary
import com.google.protobuf.{Message, ByteString}

import serialization.{Serializer, Serializable, SerializationProtocol}
import protobuf.RemoteProtocol.{RemoteRequest, RemoteReply}

object RemoteProtocolBuilder {
  def getMessage(request: RemoteRequest): AnyRef = {
    request.getProtocol match {
      case SerializationProtocol.SBINARY =>
        val renderer = Class.forName(new String(request.getMessageManifest.toByteArray)).newInstance.asInstanceOf[SBinary]
        renderer.fromBytes(request.getMessage.toByteArray)
      case SerializationProtocol.SCALA_JSON =>
        val manifest = Serializer.Java.in(request.getMessageManifest.toByteArray, None).asInstanceOf[String]
        Serializer.ScalaJSON.in(request.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationProtocol.JAVA_JSON =>
        val manifest = Serializer.Java.in(request.getMessageManifest.toByteArray, None).asInstanceOf[String]
        Serializer.JavaJSON.in(request.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationProtocol.PROTOBUF =>
        val manifest = Serializer.Java.in(request.getMessageManifest.toByteArray, None).asInstanceOf[Message]
        Serializer.Protobuf.in(request.getMessage.toByteArray, manifest)
      case SerializationProtocol.JAVA =>
        Serializer.Java.in(request.getMessage.toByteArray, None)
      case SerializationProtocol.AVRO =>
        throw new UnsupportedOperationException("Avro protocol is not yet supported")
    }
  }

  def getMessage(reply: RemoteReply): AnyRef = {
    reply.getProtocol match {
      case SerializationProtocol.SBINARY =>
        val renderer = Class.forName(new String(reply.getMessageManifest.toByteArray)).newInstance.asInstanceOf[SBinary]
        renderer.fromBytes(reply.getMessage.toByteArray)
      case SerializationProtocol.SCALA_JSON =>
        val manifest = Serializer.Java.in(reply.getMessageManifest.toByteArray, None).asInstanceOf[String]
        Serializer.ScalaJSON.in(reply.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationProtocol.JAVA_JSON =>
        val manifest = Serializer.Java.in(reply.getMessageManifest.toByteArray, None).asInstanceOf[String]
        Serializer.JavaJSON.in(reply.getMessage.toByteArray, Some(Class.forName(manifest)))
      case SerializationProtocol.PROTOBUF =>
        val manifest = Serializer.Java.in(reply.getMessageManifest.toByteArray, None).asInstanceOf[Message]
        Serializer.Protobuf.in(reply.getMessage.toByteArray, manifest)
      case SerializationProtocol.JAVA =>
        Serializer.Java.in(reply.getMessage.toByteArray, None)
      case SerializationProtocol.AVRO =>
        throw new UnsupportedOperationException("Avro protocol is not yet supported")
    }
  }

  def setMessage(message: AnyRef, builder: RemoteRequest.Builder) = {
    if (message.isInstanceOf[Serializable.SBinary]) {
      val serializable = message.asInstanceOf[Serializable.SBinary]
      builder.setProtocol(SerializationProtocol.SBINARY)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else if (message.isInstanceOf[Serializable.Protobuf]) {
      val serializable = message.asInstanceOf[Serializable.Protobuf]
      builder.setProtocol(SerializationProtocol.PROTOBUF)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(Serializer.Java.out(serializable.getSchema)))
    } else if (message.isInstanceOf[Serializable.ScalaJSON[_]]) {
      val serializable = message.asInstanceOf[Serializable.ScalaJSON[_]]
      builder.setProtocol(SerializationProtocol.SCALA_JSON)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.body.asInstanceOf[AnyRef].getClass.getName.getBytes))
    } else if (message.isInstanceOf[Serializable.JavaJSON[_]]) {
      val serializable = message.asInstanceOf[Serializable.JavaJSON[_]]
      builder.setProtocol(SerializationProtocol.JAVA_JSON)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.body.asInstanceOf[AnyRef].getClass.getName.getBytes))
    } else {
      // default, e.g. if no protocol used explicitly then use Java serialization
      builder.setProtocol(SerializationProtocol.JAVA)
      builder.setMessage(ByteString.copyFrom(Serializer.Java.out(message)))
    }
  }

  def setMessage(message: AnyRef, builder: RemoteReply.Builder) = {
    if (message.isInstanceOf[Serializable.SBinary]) {
      val serializable = message.asInstanceOf[Serializable.SBinary]
      builder.setProtocol(SerializationProtocol.SBINARY)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.getClass.getName.getBytes))
    } else if (message.isInstanceOf[Serializable.Protobuf]) {
      val serializable = message.asInstanceOf[Serializable.Protobuf]
      builder.setProtocol(SerializationProtocol.PROTOBUF)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(Serializer.Java.out(serializable.getSchema)))
    } else if (message.isInstanceOf[Serializable.ScalaJSON[_]]) {
      val serializable = message.asInstanceOf[Serializable.ScalaJSON[_]]
      builder.setProtocol(SerializationProtocol.SCALA_JSON)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.body.asInstanceOf[AnyRef].getClass.getName.getBytes))
    } else if (message.isInstanceOf[Serializable.JavaJSON[_]]) {
      val serializable = message.asInstanceOf[Serializable.JavaJSON[_]]
      builder.setProtocol(SerializationProtocol.JAVA_JSON)
      builder.setMessage(ByteString.copyFrom(serializable.toBytes))
      builder.setMessageManifest(ByteString.copyFrom(serializable.body.asInstanceOf[AnyRef].getClass.getName.getBytes))
    } else {
      // default, e.g. if no protocol used explicitly then use Java serialization
      builder.setProtocol(SerializationProtocol.JAVA)
      builder.setMessage(ByteString.copyFrom(Serializer.Java.out(message)))
    }
  }
}