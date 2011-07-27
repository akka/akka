/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.serialization

import akka.dispatch.MessageInvocation
import akka.remote.protocol.RemoteProtocol.{ ActorType ⇒ ActorTypeProtocol, _ }

import ActorTypeProtocol._
import akka.config.Supervision._
import akka.actor.{ uuidFrom, newUuid }
import akka.actor._

import scala.collection.immutable.Stack

import com.google.protobuf.ByteString
import akka.util.ReflectiveAccess
import java.net.InetSocketAddress
import akka.remote.{ RemoteClientSettings, MessageSerializer }

/**
 * Type class definition for Actor Serialization
 */
trait FromBinary[T <: Actor] {
  def fromBinary(bytes: Array[Byte], act: T): T
}

trait ToBinary[T <: Actor] {
  def toBinary(t: T): Array[Byte]
}

// client needs to implement Format[] for the respective actor
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
trait StatelessActorFormat[T <: Actor] extends Format[T] with scala.Serializable {
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
 *     val serializer = Serializer.Java
 * }
 * }
 * </pre>
 */
trait SerializerBasedActorFormat[T <: Actor] extends Format[T] with scala.Serializable {
  val serializer: Serializer

  def fromBinary(bytes: Array[Byte], act: T) = serializer.fromBinary(bytes, Some(act.self.actorClass)).asInstanceOf[T]

  def toBinary(ac: T) = serializer.toBinary(ac)
}

/**
 * Module for local actor serialization.
 */
object ActorSerialization {
  def fromBinary[T <: Actor](bytes: Array[Byte], homeAddress: InetSocketAddress)(implicit format: Format[T]): ActorRef =
    fromBinaryToLocalActorRef(bytes, Some(homeAddress), format)

  def fromBinary[T <: Actor](bytes: Array[Byte])(implicit format: Format[T]): ActorRef =
    fromBinaryToLocalActorRef(bytes, None, format)

  def toBinary[T <: Actor](a: ActorRef, serializeMailBox: Boolean = true)(implicit format: Format[T]): Array[Byte] =
    toSerializedActorRefProtocol(a, format, serializeMailBox).toByteArray

  // wrapper for implicits to be used by Java
  def fromBinaryJ[T <: Actor](bytes: Array[Byte], format: Format[T]): ActorRef =
    fromBinary(bytes)(format)

  // wrapper for implicits to be used by Java
  def toBinaryJ[T <: Actor](a: ActorRef, format: Format[T], srlMailBox: Boolean = true): Array[Byte] =
    toBinary(a, srlMailBox)(format)

  private[akka] def toAddressProtocol(actorRef: ActorRef) = {
    val address = actorRef.homeAddress.getOrElse(Actor.remote.address)
    AddressProtocol.newBuilder
      .setHostname(address.getAddress.getHostAddress)
      .setPort(address.getPort)
      .build
  }

  private[akka] def toSerializedActorRefProtocol[T <: Actor](
    actorRef: ActorRef, format: Format[T], serializeMailBox: Boolean = true): SerializedActorRefProtocol = {
    val lifeCycleProtocol: Option[LifeCycleProtocol] = {
      actorRef.lifeCycle match {
        case Permanent          ⇒ Some(LifeCycleProtocol.newBuilder.setLifeCycle(LifeCycleType.PERMANENT).build)
        case Temporary          ⇒ Some(LifeCycleProtocol.newBuilder.setLifeCycle(LifeCycleType.TEMPORARY).build)
        case UndefinedLifeCycle ⇒ None //No need to send the undefined lifecycle over the wire  //builder.setLifeCycle(LifeCycleType.UNDEFINED)
      }
    }

    val builder = SerializedActorRefProtocol.newBuilder
      .setUuid(UuidProtocol.newBuilder.setHigh(actorRef.uuid.getTime).setLow(actorRef.uuid.getClockSeqAndNode).build)
      .setId(actorRef.id)
      .setActorClassname(actorRef.actorClass.getName)
      .setOriginalAddress(toAddressProtocol(actorRef))
      .setTimeout(actorRef.timeout)

    if (serializeMailBox == true) {
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
            actorRef.id,
            actorRef.actorClassName,
            actorRef.timeout,
            Right(m.message),
            false,
            actorRef.getSender,
            None,
            ActorType.ScalaActor,
            RemoteClientSettings.SECURE_COOKIE).build)

      requestProtocols.foreach(rp ⇒ builder.addMessages(rp))
    }

    actorRef.receiveTimeout.foreach(builder.setReceiveTimeout(_))
    builder.setActorInstance(ByteString.copyFrom(format.toBinary(actorRef.actor.asInstanceOf[T])))
    lifeCycleProtocol.foreach(builder.setLifeCycle(_))
    actorRef.supervisor.foreach(s ⇒ builder.setSupervisor(RemoteActorSerialization.toRemoteActorRefProtocol(s)))
    if (!actorRef.hotswap.isEmpty) builder.setHotswapStack(ByteString.copyFrom(Serializer.Java.toBinary(actorRef.hotswap)))
    builder.build
  }

  private def fromBinaryToLocalActorRef[T <: Actor](
    bytes: Array[Byte],
    homeAddress: Option[InetSocketAddress],
    format: Format[T]): ActorRef = {
    val builder = SerializedActorRefProtocol.newBuilder.mergeFrom(bytes)
    homeAddress.foreach { addr ⇒
      val addressProtocol = AddressProtocol.newBuilder.setHostname(addr.getAddress.getHostAddress).setPort(addr.getPort).build
      builder.setOriginalAddress(addressProtocol)
    }
    fromProtobufToLocalActorRef(builder.build, format, None)
  }

  private[akka] def fromProtobufToLocalActorRef[T <: Actor](
    protocol: SerializedActorRefProtocol, format: Format[T], loader: Option[ClassLoader]): ActorRef = {

    val serializer =
      if (format.isInstanceOf[SerializerBasedActorFormat[_]])
        Some(format.asInstanceOf[SerializerBasedActorFormat[_]].serializer)
      else None

    val lifeCycle =
      if (protocol.hasLifeCycle) {
        protocol.getLifeCycle.getLifeCycle match {
          case LifeCycleType.PERMANENT ⇒ Permanent
          case LifeCycleType.TEMPORARY ⇒ Temporary
          case unknown                 ⇒ throw new IllegalActorStateException("LifeCycle type is not valid: " + unknown)
        }
      } else UndefinedLifeCycle

    val supervisor =
      if (protocol.hasSupervisor) Some(RemoteActorSerialization.fromProtobufToRemoteActorRef(protocol.getSupervisor, loader))
      else None

    val hotswap =
      if (serializer.isDefined && protocol.hasHotswapStack) serializer.get
        .fromBinary(protocol.getHotswapStack.toByteArray, Some(classOf[Stack[PartialFunction[Any, Unit]]]))
        .asInstanceOf[Stack[PartialFunction[Any, Unit]]]
      else Stack[PartialFunction[Any, Unit]]()

    val classLoader = loader.getOrElse(getClass.getClassLoader)

    val factory = () ⇒ {
      val actorClass = classLoader.loadClass(protocol.getActorClassname)
      if (format.isInstanceOf[SerializerBasedActorFormat[_]])
        format.asInstanceOf[SerializerBasedActorFormat[_]].serializer.fromBinary(
          protocol.getActorInstance.toByteArray, Some(actorClass)).asInstanceOf[Actor]
      else actorClass.newInstance.asInstanceOf[Actor]
    }

    val homeAddress = {
      val address = protocol.getOriginalAddress
      Some(new InetSocketAddress(address.getHostname, address.getPort))
    }

    val ar = new LocalActorRef(
      uuidFrom(protocol.getUuid.getHigh, protocol.getUuid.getLow),
      protocol.getId,
      if (protocol.hasTimeout) protocol.getTimeout else Actor.TIMEOUT,
      if (protocol.hasReceiveTimeout) Some(protocol.getReceiveTimeout) else None,
      lifeCycle,
      supervisor,
      hotswap,
      factory,
      homeAddress)

    val messages = protocol.getMessagesList.toArray.toList.asInstanceOf[List[RemoteMessageProtocol]]
    messages.foreach(message ⇒ ar ! MessageSerializer.deserialize(message.getMessage, loader))

    if (format.isInstanceOf[SerializerBasedActorFormat[_]] == false)
      format.fromBinary(protocol.getActorInstance.toByteArray, ar.actor.asInstanceOf[T])
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
    val ref = RemoteActorRef(
      protocol.getClassOrServiceName,
      protocol.getActorClassname,
      protocol.getHomeAddress.getHostname,
      protocol.getHomeAddress.getPort,
      protocol.getTimeout,
      loader)
    ref
  }

  /**
   * Serializes the ActorRef instance into a Protocol Buffers (protobuf) Message.
   */
  def toRemoteActorRefProtocol(actor: ActorRef): RemoteActorRefProtocol = actor match {
    case r: RemoteActorRef ⇒
      RemoteActorRefProtocol.newBuilder
        .setClassOrServiceName(r.id)
        .setActorClassname(r.actorClassName)
        .setHomeAddress(ActorSerialization.toAddressProtocol(r))
        .setTimeout(r.timeout)
        .build
    case ar: LocalActorRef ⇒
      Actor.remote.registerByUuid(ar)

      RemoteActorRefProtocol.newBuilder
        .setClassOrServiceName("uuid:" + ar.uuid.toString)
        .setActorClassname(ar.actorClassName)
        .setHomeAddress(ActorSerialization.toAddressProtocol(ar))
        .setTimeout(ar.timeout)
        .build
  }

  def createRemoteMessageProtocolBuilder(
    actorRef: Option[ActorRef],
    replyUuid: Either[Uuid, UuidProtocol],
    actorId: String,
    actorClassName: String,
    timeout: Long,
    message: Either[Throwable, Any],
    isOneWay: Boolean,
    senderOption: Option[ActorRef],
    typedActorInfo: Option[Tuple2[String, String]],
    actorType: ActorType,
    secureCookie: Option[String]): RemoteMessageProtocol.Builder = {

    val uuidProtocol = replyUuid match {
      case Left(uid)       ⇒ UuidProtocol.newBuilder.setHigh(uid.getTime).setLow(uid.getClockSeqAndNode).build
      case Right(protocol) ⇒ protocol
    }

    val actorInfoBuilder = ActorInfoProtocol.newBuilder
      .setUuid(uuidProtocol)
      .setId(actorId)
      .setTarget(actorClassName)
      .setTimeout(timeout)

    typedActorInfo.foreach { typedActor ⇒
      actorInfoBuilder.setTypedActorInfo(
        TypedActorInfoProtocol.newBuilder
          .setInterface(typedActor._1)
          .setMethod(typedActor._2)
          .build)
    }

    actorType match {
      case ActorType.ScalaActor ⇒ actorInfoBuilder.setActorType(SCALA_ACTOR)
      case ActorType.TypedActor ⇒ actorInfoBuilder.setActorType(TYPED_ACTOR)
    }
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

    secureCookie.foreach(messageBuilder.setCookie(_))

    actorRef.foreach { ref ⇒
      ref.registerSupervisorAsRemoteActor.foreach { id ⇒
        messageBuilder.setSupervisorUuid(
          UuidProtocol.newBuilder
            .setHigh(id.getTime)
            .setLow(id.getClockSeqAndNode)
            .build)
      }
    }

    if (senderOption.isDefined)
      messageBuilder.setSender(toRemoteActorRefProtocol(senderOption.get))

    messageBuilder
  }
}

/**
 * Module for local typed actor serialization.
 */
object TypedActorSerialization {

  def fromBinary[T <: Actor, U <: AnyRef](bytes: Array[Byte])(implicit format: Format[T]): U =
    fromBinaryToLocalTypedActorRef(bytes, format)

  def toBinary[T <: Actor](proxy: AnyRef)(implicit format: Format[T]): Array[Byte] = {
    toSerializedTypedActorRefProtocol(proxy, format).toByteArray
  }

  // wrapper for implicits to be used by Java
  def fromBinaryJ[T <: Actor, U <: AnyRef](bytes: Array[Byte], format: Format[T]): U =
    fromBinary(bytes)(format)

  // wrapper for implicits to be used by Java
  def toBinaryJ[T <: Actor](a: AnyRef, format: Format[T]): Array[Byte] =
    toBinary(a)(format)

  private def toSerializedTypedActorRefProtocol[T <: Actor](
    proxy: AnyRef, format: Format[T]): SerializedTypedActorRefProtocol = {

    val init = AspectInitRegistry.initFor(proxy)
    if (init eq null) throw new IllegalArgumentException("Proxy for typed actor could not be found in AspectInitRegistry.")

    SerializedTypedActorRefProtocol.newBuilder
      .setActorRef(ActorSerialization.toSerializedActorRefProtocol(init.actorRef, format))
      .setInterfaceName(init.interfaceClass.getName)
      .build
  }

  private def fromBinaryToLocalTypedActorRef[T <: Actor, U <: AnyRef](bytes: Array[Byte], format: Format[T]): U =
    fromProtobufToLocalTypedActorRef(SerializedTypedActorRefProtocol.newBuilder.mergeFrom(bytes).build, format, None)

  private def fromProtobufToLocalTypedActorRef[T <: Actor, U <: AnyRef](
    protocol: SerializedTypedActorRefProtocol, format: Format[T], loader: Option[ClassLoader]): U = {
    val actorRef = ActorSerialization.fromProtobufToLocalActorRef(protocol.getActorRef, format, loader)
    val intfClass = toClass(loader, protocol.getInterfaceName)
    TypedActor.newInstance(intfClass, actorRef).asInstanceOf[U]
  }

  private[akka] def toClass[U <: AnyRef](loader: Option[ClassLoader], name: String): Class[U] = {
    val classLoader = loader.getOrElse(getClass.getClassLoader)
    val clazz = classLoader.loadClass(name)
    clazz.asInstanceOf[Class[U]]
  }
}

/**
 * Module for remote typed actor serialization.
 */
object RemoteTypedActorSerialization {
  /**
   * Deserializes a byte array (Array[Byte]) into an RemoteActorRef instance.
   */
  def fromBinaryToRemoteTypedActorRef[T <: AnyRef](bytes: Array[Byte]): T =
    fromProtobufToRemoteTypedActorRef(RemoteTypedActorRefProtocol.newBuilder.mergeFrom(bytes).build, None)

  /**
   * Deserializes a byte array (Array[Byte]) into a AW RemoteActorRef proxy.
   */
  def fromBinaryToRemoteTypedActorRef[T <: AnyRef](bytes: Array[Byte], loader: ClassLoader): T =
    fromProtobufToRemoteTypedActorRef(RemoteTypedActorRefProtocol.newBuilder.mergeFrom(bytes).build, Some(loader))

  /**
   * Serialize as AW RemoteActorRef proxy.
   */
  def toBinary[T <: Actor](proxy: AnyRef): Array[Byte] = {
    toRemoteTypedActorRefProtocol(proxy).toByteArray
  }

  /**
   * Deserializes a RemoteTypedActorRefProtocol Protocol Buffers (protobuf) Message into AW RemoteActorRef proxy.
   */
  private[akka] def fromProtobufToRemoteTypedActorRef[T](protocol: RemoteTypedActorRefProtocol, loader: Option[ClassLoader]): T = {
    val actorRef = RemoteActorSerialization.fromProtobufToRemoteActorRef(protocol.getActorRef, loader)
    val intfClass = TypedActorSerialization.toClass(loader, protocol.getInterfaceName)
    TypedActor.createProxyForRemoteActorRef(intfClass, actorRef).asInstanceOf[T]
  }

  /**
   * Serializes the AW TypedActor proxy into a Protocol Buffers (protobuf) Message.
   */
  def toRemoteTypedActorRefProtocol(proxy: AnyRef): RemoteTypedActorRefProtocol = {
    val init = AspectInitRegistry.initFor(proxy)
    RemoteTypedActorRefProtocol.newBuilder
      .setActorRef(RemoteActorSerialization.toRemoteActorRefProtocol(init.actorRef))
      .setInterfaceName(init.interfaceClass.getName)
      .build
  }
}
