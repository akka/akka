/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import akka.config.Supervision._
import akka.actor.{ uuidFrom, newUuid }
import akka.actor._
import DeploymentConfig._
import akka.dispatch.MessageInvocation
import akka.util.ReflectiveAccess
import akka.cluster.{ RemoteClientSettings, MessageSerializer }
import akka.cluster.protocol.RemoteProtocol
import RemoteProtocol._

import scala.collection.immutable.Stack

import java.net.InetSocketAddress

import com.google.protobuf.ByteString

import com.eaio.uuid.UUID
import akka.event.EventHandler

/**
 * Module for local actor serialization.
 */
object ActorSerialization {
  implicit val defaultSerializer = akka.serialization.JavaSerializer // Format.Default

  def fromBinary[T <: Actor](bytes: Array[Byte], homeAddress: InetSocketAddress): ActorRef =
    fromBinaryToLocalActorRef(bytes, None, Some(homeAddress))

  def fromBinary[T <: Actor](bytes: Array[Byte], uuid: UUID): ActorRef =
    fromBinaryToLocalActorRef(bytes, Some(uuid), None)

  def fromBinary[T <: Actor](bytes: Array[Byte]): ActorRef =
    fromBinaryToLocalActorRef(bytes, None, None)

  def toBinary[T <: Actor](
    a: ActorRef,
    serializeMailBox: Boolean = true,
    replicationScheme: ReplicationScheme = Transient): Array[Byte] =
    toSerializedActorRefProtocol(a, serializeMailBox, replicationScheme).toByteArray

  // wrapper for implicits to be used by Java
  def fromBinaryJ[T <: Actor](bytes: Array[Byte]): ActorRef =
    fromBinary(bytes)

  // wrapper for implicits to be used by Java
  def toBinaryJ[T <: Actor](
    a: ActorRef,
    srlMailBox: Boolean,
    replicationScheme: ReplicationScheme): Array[Byte] =
    toBinary(a, srlMailBox, replicationScheme)

  private[akka] def toSerializedActorRefProtocol[T <: Actor](
    actorRef: ActorRef,
    serializeMailBox: Boolean,
    replicationScheme: ReplicationScheme): SerializedActorRefProtocol = {

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

    replicationScheme match {
      case _: Transient | Transient ⇒
        builder.setReplicationStorage(ReplicationStorageType.TRANSIENT)

      case Replication(storage, strategy) ⇒
        val storageType = storage match {
          case _: TransactionLog | TransactionLog ⇒ ReplicationStorageType.TRANSACTION_LOG
          case _: DataGrid | DataGrid             ⇒ ReplicationStorageType.DATA_GRID
        }
        builder.setReplicationStorage(storageType)

        val strategyType = strategy match {
          case _: WriteBehind | WriteBehind   ⇒ ReplicationStrategyType.WRITE_BEHIND
          case _: WriteThrough | WriteThrough ⇒ ReplicationStrategyType.WRITE_THROUGH
        }
        builder.setReplicationStrategy(strategyType)
    }

    if (serializeMailBox == true) {
      if (actorRef.mailbox eq null) throw new IllegalActorStateException("Can't serialize an actor that has not been started.")
      val messages =
        actorRef.mailbox match {
          case q: java.util.Queue[_] ⇒
            val l = new scala.collection.mutable.ListBuffer[MessageInvocation]
            val it = q.iterator
            while (it.hasNext) l += it.next.asInstanceOf[MessageInvocation]
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
    Serialization.serialize(actorRef.actor.asInstanceOf[T]) match {
      case Right(bytes)    ⇒ builder.setActorInstance(ByteString.copyFrom(bytes))
      case Left(exception) ⇒ throw new Exception("Error serializing : " + actorRef.actor.getClass.getName)
    }

    lifeCycleProtocol.foreach(builder.setLifeCycle(_))
    actorRef.supervisor.foreach(s ⇒ builder.setSupervisor(RemoteActorSerialization.toRemoteActorRefProtocol(s)))
    // if (!actorRef.hotswap.isEmpty) builder.setHotswapStack(ByteString.copyFrom(Serializers.Java.toBinary(actorRef.hotswap)))
    if (!actorRef.hotswap.isEmpty) builder.setHotswapStack(ByteString.copyFrom(akka.serialization.JavaSerializer.toBinary(actorRef.hotswap)))
    builder.build
  }

  private def fromBinaryToLocalActorRef[T <: Actor](
    bytes: Array[Byte],
    uuid: Option[UUID],
    homeAddress: Option[InetSocketAddress]): ActorRef = {
    val builder = SerializedActorRefProtocol.newBuilder.mergeFrom(bytes)
    fromProtobufToLocalActorRef(builder.build, uuid, None)
  }

  private[akka] def fromProtobufToLocalActorRef[T <: Actor](
    protocol: SerializedActorRefProtocol,
    overriddenUuid: Option[UUID],
    loader: Option[ClassLoader]): ActorRef = {

    EventHandler.debug(this, "Deserializing SerializedActorRefProtocol to LocalActorRef:\n%s".format(protocol))

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

    // import ReplicationStorageType._
    // import ReplicationStrategyType._
    // val replicationScheme =
    //   if (protocol.hasReplicationStorage) {
    //     protocol.getReplicationStorage match {
    //       case TRANSIENT ⇒ Transient
    //       case store ⇒
    //         val storage = store match {
    //           case TRANSACTION_LOG ⇒ TransactionLog
    //           case DATA_GRID       ⇒ DataGrid
    //         }
    //         val strategy = if (protocol.hasReplicationStrategy) {
    //           protocol.getReplicationStrategy match {
    //             case WRITE_THROUGH ⇒ WriteThrough
    //             case WRITE_BEHIND  ⇒ WriteBehind
    //           }
    //         } else throw new IllegalActorStateException(
    //           "Expected replication strategy for replication storage [" + storage + "]")
    //         Replication(storage, strategy)
    //     }
    //   } else Transient

    val hotswap =
      try {
        Serialization.deserialize(
          protocol.getHotswapStack.toByteArray,
          classOf[Stack[PartialFunction[Any, Unit]]],
          loader) match {
            case Right(r) ⇒ r.asInstanceOf[Stack[PartialFunction[Any, Unit]]]
            case Left(ex) ⇒ throw new Exception("Cannot de-serialize hotswapstack")
          }
      } catch {
        case e: Exception ⇒ Stack[PartialFunction[Any, Unit]]()
      }

    val classLoader = loader.getOrElse(this.getClass.getClassLoader)

    val factory = () ⇒ {
      val actorClass = classLoader.loadClass(protocol.getActorClassname)
      try {
        Serialization.deserialize(protocol.getActorInstance.toByteArray, actorClass, loader) match {
          case Right(r) ⇒ r.asInstanceOf[Actor]
          case Left(ex) ⇒ throw new Exception("Cannot de-serialize : " + actorClass)
        }
      } catch {
        case e: Exception ⇒ actorClass.newInstance.asInstanceOf[Actor]
      }
    }

    val actorUuid = overriddenUuid match {
      case Some(uuid) ⇒ uuid
      case None       ⇒ uuidFrom(protocol.getUuid.getHigh, protocol.getUuid.getLow)
    }

    val ar = new LocalActorRef(
      actorUuid,
      protocol.getAddress,
      if (protocol.hasTimeout) protocol.getTimeout else Actor.TIMEOUT,
      if (protocol.hasReceiveTimeout) Some(protocol.getReceiveTimeout) else None,
      lifeCycle,
      supervisor,
      hotswap,
      factory)

    val messages = protocol.getMessagesList.toArray.toList.asInstanceOf[List[RemoteMessageProtocol]]
    messages.foreach(message ⇒ ar ! MessageSerializer.deserialize(message.getMessage, Some(classLoader)))
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
    EventHandler.debug(this, "Deserializing RemoteActorRefProtocol to RemoteActorRef:\n %s".format(protocol))

    val ref = RemoteActorRef(
      JavaSerializer.fromBinary(protocol.getInetSocketAddress.toByteArray, Some(classOf[InetSocketAddress]), loader).asInstanceOf[InetSocketAddress],
      protocol.getAddress,
      protocol.getTimeout,
      loader)

    EventHandler.debug(this, "Newly deserialized RemoteActorRef has uuid: %s".format(ref.uuid))

    ref
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

    EventHandler.debug(this, "Register serialized Actor [%s] as remote @ [%s]".format(actor.uuid, remoteAddress))

    RemoteActorRefProtocol.newBuilder
      .setInetSocketAddress(ByteString.copyFrom(JavaSerializer.toBinary(remoteAddress)))
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
        messageBuilder.setMessage(MessageSerializer.serialize(message.asInstanceOf[AnyRef]))
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
