/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import akka.actor._
import akka.actor.DeploymentConfig._
import akka.dispatch.Envelope
import akka.util.{ ReflectiveAccess, Duration }
import akka.event.EventHandler
import akka.remote._
import RemoteProtocol._
import akka.AkkaApplication

import scala.collection.immutable.Stack

import java.net.InetSocketAddress
import java.util.{ LinkedList, Collections }

import com.google.protobuf.ByteString

import com.eaio.uuid.UUID

/**
 * Module for local actor serialization.
 */
class ActorSerialization(val app: AkkaApplication, remote: RemoteSupport) {
  implicit val defaultSerializer = akka.serialization.JavaSerializer // Format.Default

  val remoteActorSerialization = new RemoteActorSerialization(app, remote)

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

  @deprecated("BROKEN, REMOVE ME", "NOW")
  private[akka] def toSerializedActorRefProtocol[T <: Actor](
    actorRef: ActorRef,
    serializeMailBox: Boolean,
    replicationScheme: ReplicationScheme): SerializedActorRefProtocol = {

    val localRef: Option[LocalActorRef] = actorRef match {
      case l: LocalActorRef ⇒ Some(l)
      case _                ⇒ None
    }

    val builder = SerializedActorRefProtocol.newBuilder
      .setUuid(UuidProtocol.newBuilder.setHigh(actorRef.uuid.getTime).setLow(actorRef.uuid.getClockSeqAndNode).build)
      .setAddress(actorRef.address)
      .setTimeout(app.AkkaConfig.ActorTimeoutMillis)

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
          case _: WriteBehind  ⇒ ReplicationStrategyType.WRITE_BEHIND
          case _: WriteThrough ⇒ ReplicationStrategyType.WRITE_THROUGH
        }
        builder.setReplicationStrategy(strategyType)
    }

    localRef foreach { l ⇒
      if (serializeMailBox) {
        l.underlying.mailbox match {
          case null ⇒ throw new IllegalActorStateException("Can't serialize an actor that has not been started.")
          case q: java.util.Queue[_] ⇒
            val l = new scala.collection.mutable.ListBuffer[Envelope]
            val it = q.iterator
            while (it.hasNext) l += it.next.asInstanceOf[Envelope]

            l map { m ⇒
              remoteActorSerialization.createRemoteMessageProtocolBuilder(
                Option(m.receiver.self),
                Left(actorRef.uuid),
                actorRef.address,
                app.AkkaConfig.ActorTimeoutMillis,
                Right(m.message),
                false,
                m.channel match {
                  case a: ActorRef ⇒ Some(a)
                  case _           ⇒ None
                })
            } foreach {
              builder.addMessages(_)
            }
        }
      }

      l.underlying.receiveTimeout.foreach(builder.setReceiveTimeout(_))
      val actorInstance = l.underlyingActorInstance
      app.serialization.serialize(actorInstance.asInstanceOf[T]) match {
        case Right(bytes)    ⇒ builder.setActorInstance(ByteString.copyFrom(bytes))
        case Left(exception) ⇒ throw new Exception("Error serializing : " + actorInstance.getClass.getName)
      }
      val stack = l.underlying.hotswap
      if (!stack.isEmpty)
        builder.setHotswapStack(ByteString.copyFrom(akka.serialization.JavaSerializer.toBinary(stack)))
    }

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

    app.eventHandler.debug(this, "Deserializing SerializedActorRefProtocol to LocalActorRef:\n%s".format(protocol))

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

    val storedHotswap =
      try {
        app.serialization.deserialize(
          protocol.getHotswapStack.toByteArray,
          classOf[Stack[PartialFunction[Any, Unit]]],
          loader) match {
            case Right(r) ⇒ r.asInstanceOf[Stack[PartialFunction[Any, Unit]]]
            case Left(ex) ⇒ throw new Exception("Cannot de-serialize hotswapstack")
          }
      } catch {
        case e: Exception ⇒ Stack[PartialFunction[Any, Unit]]()
      }

    val storedSupervisor =
      if (protocol.hasSupervisor) Some(remoteActorSerialization.fromProtobufToRemoteActorRef(protocol.getSupervisor, loader))
      else None

    val classLoader = loader.getOrElse(this.getClass.getClassLoader)
    val bytes = protocol.getActorInstance.toByteArray
    val actorClass = classLoader.loadClass(protocol.getActorClassname)
    val factory = () ⇒ {
      app.serialization.deserialize(bytes, actorClass, loader) match {
        case Right(r) ⇒ r.asInstanceOf[Actor]
        case Left(ex) ⇒ throw new Exception("Cannot de-serialize : " + actorClass)
      }
    }

    val actorUuid = overriddenUuid match {
      case Some(uuid) ⇒ uuid
      case None       ⇒ uuidFrom(protocol.getUuid.getHigh, protocol.getUuid.getLow)
    }

    val props = Props(creator = factory,
      timeout = if (protocol.hasTimeout) protocol.getTimeout else app.AkkaConfig.ActorTimeout //TODO what dispatcher should it use?
      //TODO what faultHandler should it use?
      )

    val receiveTimeout = if (protocol.hasReceiveTimeout) Some(protocol.getReceiveTimeout) else None //TODO FIXME, I'm expensive and slow

    // FIXME: what to do if storedSupervisor is empty?
    val ar = new LocalActorRef(app, props, storedSupervisor getOrElse app.guardian, protocol.getAddress, false, actorUuid, receiveTimeout, storedHotswap)

    //Deserialize messages
    {
      val iterator = protocol.getMessagesList.iterator()
      while (iterator.hasNext())
        ar ! MessageSerializer.deserialize(app, iterator.next().getMessage, Some(classLoader)) //TODO This is broken, why aren't we preserving the sender?
    }

    ar
  }
}

class RemoteActorSerialization(val app: AkkaApplication, remote: RemoteSupport) {

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
    app.eventHandler.debug(this, "Deserializing RemoteActorRefProtocol to RemoteActorRef:\n %s".format(protocol))

    val ref = RemoteActorRef(
      remote,
      JavaSerializer.fromBinary(protocol.getInetSocketAddress.toByteArray, Some(classOf[InetSocketAddress]), loader).asInstanceOf[InetSocketAddress],
      protocol.getAddress,
      loader)

    app.eventHandler.debug(this, "Newly deserialized RemoteActorRef has uuid: %s".format(ref.uuid))

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
        remote.registerByUuid(ar)
        app.defaultAddress
      case _ ⇒
        app.defaultAddress
    }

    app.eventHandler.debug(this, "Register serialized Actor [%s] as remote @ [%s]".format(actor.uuid, remoteAddress))

    RemoteActorRefProtocol.newBuilder
      .setInetSocketAddress(ByteString.copyFrom(JavaSerializer.toBinary(remoteAddress)))
      .setAddress(actor.address)
      .setTimeout(app.AkkaConfig.ActorTimeoutMillis)
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
        messageBuilder.setMessage(MessageSerializer.serialize(app, message.asInstanceOf[AnyRef]))
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
