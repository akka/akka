/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.serialization

import akka.dispatch.MessageInvocation
import akka.remote.protocol.RemoteProtocol._
import akka.remote.protocol.RemoteProtocol

import akka.config.Supervision._
import akka.actor.{ uuidFrom, newUuid }
import akka.actor._

import scala.collection.immutable.Stack

import com.google.protobuf.ByteString
import akka.util.ReflectiveAccess
import java.net.InetSocketAddress
import akka.remote.{ RemoteClientSettings, MessageSerializer }

/**
 * Module for local actor serialization.
 */
object ActorSerialization {
  implicit val defaultSerializer = Format.Default

  def fromBinary[T <: Actor](bytes: Array[Byte], homeAddress: InetSocketAddress)(implicit format: Serializer): ActorRef =
    fromBinaryToLocalActorRef(bytes, Some(homeAddress), format)

  def fromBinary[T <: Actor](bytes: Array[Byte])(implicit format: Serializer): ActorRef =
    fromBinaryToLocalActorRef(bytes, None, format)

  def toBinary[T <: Actor](a: ActorRef, serializeMailBox: Boolean = true)(implicit format: Serializer): Array[Byte] =
    toSerializedActorRefProtocol(a, format, serializeMailBox).toByteArray

  // wrapper for implicits to be used by Java
  def fromBinaryJ[T <: Actor](bytes: Array[Byte], format: Serializer): ActorRef =
    fromBinary(bytes)(format)

  // wrapper for implicits to be used by Java
  def toBinaryJ[T <: Actor](a: ActorRef, format: Serializer, srlMailBox: Boolean = true): Array[Byte] =
    toBinary(a, srlMailBox)(format)

  private[akka] def toSerializedActorRefProtocol[T <: Actor](
    actorRef: ActorRef, format: Serializer, serializeMailBox: Boolean = true): SerializedActorRefProtocol = {
    val lifeCycleProtocol: Option[LifeCycleProtocol] = {
      actorRef.lifeCycle match {
        case Permanent          ⇒ Some(LifeCycleProtocol.newBuilder.setLifeCycle(LifeCycleType.PERMANENT).build)
        case Temporary          ⇒ Some(LifeCycleProtocol.newBuilder.setLifeCycle(LifeCycleType.TEMPORARY).build)
        case UndefinedLifeCycle ⇒ None //No need to send the undefined lifecycle over the wire  //builder.setLifeCycle(LifeCycleType.UNDEFINED)
      }
    }

    val builder = SerializedActorRefProtocol.newBuilder
      .setUuid(UuidProtocol.newBuilder.setHigh(actorRef.uuid.getTime).setLow(actorRef.uuid.getClockSeqAndNode).build)
      .setAddress(actorRef.address)
      .setActorClassname(actorRef.actorInstance.get.getClass.getName)
      .setTimeout(actorRef.timeout)

    if (serializeMailBox == true) {
      if (actorRef.mailbox eq null) throw new IllegalActorStateException("Can't serialize an actor that has not been started.")
      val messages =
        actorRef.mailbox match {
          case q: java.util.Queue[MessageInvocation] ⇒
            val l = new scala.collection.mutable.ListBuffer[MessageInvocation]
            val it = q.iterator
            while (it.hasNext == true) l += it.next
            l
        }

      val requestProtocols =
        messages.map(m ⇒
          RemoteActorSerialization.createRemoteMessageProtocolBuilder(
            Some(actorRef),
            Left(actorRef.uuid),
            actorRef.address,
            actorRef.timeout,
            Right(m.message),
            false,
            actorRef.getSender))

      requestProtocols.foreach(builder.addMessages(_))
    }

    actorRef.receiveTimeout.foreach(builder.setReceiveTimeout(_))
    builder.setActorInstance(ByteString.copyFrom(format.toBinary(actorRef.actor.asInstanceOf[T])))
    lifeCycleProtocol.foreach(builder.setLifeCycle(_))
    actorRef.supervisor.foreach(s ⇒ builder.setSupervisor(RemoteActorSerialization.toRemoteActorRefProtocol(s)))
    if (!actorRef.hotswap.isEmpty) builder.setHotswapStack(ByteString.copyFrom(Serializers.Java.toBinary(actorRef.hotswap)))
    builder.build
  }

  private def fromBinaryToLocalActorRef[T <: Actor](
    bytes: Array[Byte],
    homeAddress: Option[InetSocketAddress],
    format: Serializer): ActorRef = {
    val builder = SerializedActorRefProtocol.newBuilder.mergeFrom(bytes)
    fromProtobufToLocalActorRef(builder.build, format, None)
  }

  private[akka] def fromProtobufToLocalActorRef[T <: Actor](
    protocol: SerializedActorRefProtocol, format: Serializer, loader: Option[ClassLoader]): ActorRef = {

    val lifeCycle =
      if (protocol.hasLifeCycle) {
        protocol.getLifeCycle.getLifeCycle match {
          case LifeCycleType.PERMANENT ⇒ Permanent
          case LifeCycleType.TEMPORARY ⇒ Temporary
          case unknown                 ⇒ throw new IllegalActorStateException("LifeCycle type is not valid [" + unknown + "]")
        }
      } else UndefinedLifeCycle

    val supervisor =
      if (protocol.hasSupervisor) Some(RemoteActorSerialization.fromProtobufToRemoteActorRef(protocol.getSupervisor, loader))
      else None

    val hotswap =
      try {
        format
          .fromBinary(protocol.getHotswapStack.toByteArray, Some(classOf[Stack[PartialFunction[Any, Unit]]]))
          .asInstanceOf[Stack[PartialFunction[Any, Unit]]]
      } catch {
        case e: Exception ⇒ Stack[PartialFunction[Any, Unit]]()
      }

    val classLoader = loader.getOrElse(getClass.getClassLoader)

    val factory = () ⇒ {
      val actorClass = classLoader.loadClass(protocol.getActorClassname)
      try {
        format.fromBinary(protocol.getActorInstance.toByteArray, Some(actorClass)).asInstanceOf[Actor]
      } catch {
        case e: Exception ⇒ actorClass.newInstance.asInstanceOf[Actor]
      }
    }

    val ar = new LocalActorRef(
      uuidFrom(protocol.getUuid.getHigh, protocol.getUuid.getLow),
      protocol.getAddress,
      if (protocol.hasTimeout) protocol.getTimeout else Actor.TIMEOUT,
      if (protocol.hasReceiveTimeout) Some(protocol.getReceiveTimeout) else None,
      lifeCycle,
      supervisor,
      hotswap,
      factory)

    val messages = protocol.getMessagesList.toArray.toList.asInstanceOf[List[RemoteMessageProtocol]]
    messages.foreach(message ⇒ ar ! MessageSerializer.deserialize(message.getMessage))

    //if (format.isInstanceOf[SerializerBasedActorFormat[_]] == false)
    //  format.fromBinary(protocol.getActorInstance.toByteArray, ar.actor.asInstanceOf[T])
    //ar
    ar
  }
}

object RemoteActorSerialization {

  /**
   * Deserializes a byte array (Array[Byte]) into an RemoteActorRef instance.
   */
  def fromBinaryToRemoteActorRef(bytes: Array[Byte]): ActorRef =
    fromProtobufToRemoteActorRef(RemoteActorRefProtocol.newBuilder.mergeFrom(bytes).build, None)

  /**
   * Deserializes a byte array (Array[Byte]) into an RemoteActorRef instance.
   */
  def fromBinaryToRemoteActorRef(bytes: Array[Byte], loader: ClassLoader): ActorRef =
    fromProtobufToRemoteActorRef(RemoteActorRefProtocol.newBuilder.mergeFrom(bytes).build, Some(loader))

  /**
   * Deserializes a RemoteActorRefProtocol Protocol Buffers (protobuf) Message into an RemoteActorRef instance.
   */
  private[akka] def fromProtobufToRemoteActorRef(protocol: RemoteActorRefProtocol, loader: Option[ClassLoader]): ActorRef = {
    RemoteActorRef(
      Serializers.Java.fromBinary(protocol.getInetSocketAddress.toByteArray, Some(classOf[InetSocketAddress])).asInstanceOf[InetSocketAddress],
      protocol.getAddress,
      protocol.getTimeout,
      loader)
  }

  /**
   * Serializes the ActorRef instance into a Protocol Buffers (protobuf) Message.
   */
  def toRemoteActorRefProtocol(actor: ActorRef): RemoteActorRefProtocol = {
    val remoteAddress = actor match {
      case ar: RemoteActorRef ⇒
        ar.remoteAddress
      case ar: LocalActorRef ⇒
        Actor.remote.registerByUuid(ar)
        ReflectiveAccess.RemoteModule.configDefaultAddress
      case _ ⇒
        ReflectiveAccess.RemoteModule.configDefaultAddress
    }
    RemoteActorRefProtocol.newBuilder
      .setInetSocketAddress(ByteString.copyFrom(Serializers.Java.toBinary(remoteAddress)))
      .setAddress(actor.address)
      .setTimeout(actor.timeout)
      .build
  }

  def createRemoteMessageProtocolBuilder(
    actorRef: Option[ActorRef],
    replyUuid: Either[Uuid, UuidProtocol],
    actorAddress: String,
    timeout: Long,
    message: Either[Throwable, Any],
    isOneWay: Boolean,
    senderOption: Option[ActorRef]): RemoteMessageProtocol.Builder = {

    val uuidProtocol = replyUuid match {
      case Left(uid)       ⇒ UuidProtocol.newBuilder.setHigh(uid.getTime).setLow(uid.getClockSeqAndNode).build
      case Right(protocol) ⇒ protocol
    }

    val actorInfoBuilder = ActorInfoProtocol.newBuilder
      .setUuid(uuidProtocol)
      .setAddress(actorAddress)
      .setTimeout(timeout)

    val actorInfo = actorInfoBuilder.build
    val messageBuilder = RemoteMessageProtocol.newBuilder
      .setUuid({
        val messageUuid = newUuid
        UuidProtocol.newBuilder.setHigh(messageUuid.getTime).setLow(messageUuid.getClockSeqAndNode).build
      })
      .setActorInfo(actorInfo)
      .setOneWay(isOneWay)

    message match {
      case Right(message) ⇒
        messageBuilder.setMessage(MessageSerializer.serialize(message))
      case Left(exception) ⇒
        messageBuilder.setException(ExceptionProtocol.newBuilder
          .setClassname(exception.getClass.getName)
          .setMessage(empty(exception.getMessage))
          .build)
    }

    def empty(s: String): String = s match {
      case null ⇒ ""
      case s    ⇒ s
    }

    /* TODO invent new supervision strategy
      actorRef.foreach { ref =>
      ref.registerSupervisorAsRemoteActor.foreach { id =>
        messageBuilder.setSupervisorUuid(
          UuidProtocol.newBuilder
              .setHigh(id.getTime)
              .setLow(id.getClockSeqAndNode)
              .build)
      }
    } */

    if (senderOption.isDefined)
      messageBuilder.setSender(toRemoteActorRefProtocol(senderOption.get))

    messageBuilder
  }
}
