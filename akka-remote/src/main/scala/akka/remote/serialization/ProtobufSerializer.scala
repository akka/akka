/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.serialization

import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ ActorRef, ExtendedActorSystem }
import akka.remote.WireFormats.ActorRefData
import akka.serialization.{ BaseSerializer, Serialization }
import scala.annotation.tailrec
import scala.util.control.NonFatal

import akka.event.LogMarker
import akka.event.Logging
import akka.serialization.SerializationExtension

object ProtobufSerializer {
  private val ARRAY_OF_BYTE_ARRAY = Array[Class[_]](classOf[Array[Byte]])

  /**
   * Helper to serialize an [[akka.actor.ActorRef]] to Akka's
   * protobuf representation.
   */
  def serializeActorRef(ref: ActorRef): ActorRefData = {
    ActorRefData.newBuilder.setPath(Serialization.serializedActorPath(ref)).build
  }

  /**
   * Helper to materialize (lookup) an [[akka.actor.ActorRef]]
   * from Akka's protobuf representation in the supplied
   * [[akka.actor.ActorSystem]].
   */
  def deserializeActorRef(system: ExtendedActorSystem, refProtocol: ActorRefData): ActorRef =
    system.provider.resolveActorRef(refProtocol.getPath)
}

/**
 * This Serializer serializes `akka.protobuf.Message` and `com.google.protobuf.Message`
 * It is using reflection to find the `parseFrom` and `toByteArray` methods to avoid
 * dependency to `com.google.protobuf`.
 */
class ProtobufSerializer(val system: ExtendedActorSystem) extends BaseSerializer {

  private val parsingMethodBindingRef = new AtomicReference[Map[Class[_], Method]](Map.empty)
  private val toByteArrayMethodBindingRef = new AtomicReference[Map[Class[_], Method]](Map.empty)

  private val whitelistClassNames: Set[String] = {
    import akka.util.ccompat.JavaConverters._
    system.settings.config.getStringList("akka.serialization.protobuf.whitelist-class").asScala.toSet
  }

  // This must lazy otherwise it will deadlock the ActorSystem creation
  private lazy val serialization = SerializationExtension(system)

  private val log = Logging.withMarker(system, getClass)

  override def includeManifest: Boolean = true

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    manifest match {
      case Some(clazz) =>
        @tailrec
        def parsingMethod(method: Method = null): Method = {
          val parsingMethodBinding = parsingMethodBindingRef.get()
          parsingMethodBinding.get(clazz) match {
            case Some(cachedParsingMethod) => cachedParsingMethod
            case None =>
              checkAllowedClass(clazz)
              val unCachedParsingMethod =
                if (method eq null) clazz.getDeclaredMethod("parseFrom", ProtobufSerializer.ARRAY_OF_BYTE_ARRAY: _*)
                else method
              if (parsingMethodBindingRef.compareAndSet(
                    parsingMethodBinding,
                    parsingMethodBinding.updated(clazz, unCachedParsingMethod)))
                unCachedParsingMethod
              else
                parsingMethod(unCachedParsingMethod)
          }
        }
        parsingMethod().invoke(null, bytes)

      case None =>
        throw new IllegalArgumentException("Need a protobuf message class to be able to serialize bytes using protobuf")
    }
  }

  override def toBinary(obj: AnyRef): Array[Byte] = {
    val clazz = obj.getClass
    @tailrec
    def toByteArrayMethod(method: Method = null): Method = {
      val toByteArrayMethodBinding = toByteArrayMethodBindingRef.get()
      toByteArrayMethodBinding.get(clazz) match {
        case Some(cachedtoByteArrayMethod) => cachedtoByteArrayMethod
        case None =>
          val unCachedtoByteArrayMethod =
            if (method eq null) clazz.getMethod("toByteArray")
            else method
          if (toByteArrayMethodBindingRef.compareAndSet(
                toByteArrayMethodBinding,
                toByteArrayMethodBinding.updated(clazz, unCachedtoByteArrayMethod)))
            unCachedtoByteArrayMethod
          else
            toByteArrayMethod(unCachedtoByteArrayMethod)
      }
    }
    toByteArrayMethod().invoke(obj).asInstanceOf[Array[Byte]]
  }

  private def checkAllowedClass(clazz: Class[_]): Unit = {
    if (!isInWhitelist(clazz)) {
      val warnMsg = s"Can't deserialize object of type [${clazz.getName}] in [${getClass.getName}]. " +
        "Only classes that are whitelisted are allowed for security reasons. " +
        "Configure whitelist with akka.actor.serialization-bindings or " +
        "akka.serialization.protobuf.whitelist-class"
      log.warning(LogMarker.Security, warnMsg)
      throw new IllegalArgumentException(warnMsg)
    }
  }

  /**
   * Using the `serialization-bindings` as source for the whitelist.
   * Note that the intended usage of serialization-bindings is for lookup of
   * serializer when serializing (`toBinary`). For deserialization (`fromBinary`) the serializer-id is
   * used for selecting serializer.
   * Here we use `serialization-bindings` also when deserializing (fromBinary)
   * to check that the manifest class is of a known (registered) type.
   *
   * If an old class is removed from `serialization-bindings` when it's not used for serialization
   * but still used for deserialization (e.g. rolling update with serialization changes) it can
   * be allowed by specifying in `akka.protobuf.whitelist-class`.
   *
   * That is also possible when changing a binding from a ProtobufSerializer to another serializer (e.g. Jackson)
   * and still bind with the same class (interface).
   */
  private def isInWhitelist(clazz: Class[_]): Boolean = {
    isBoundToProtobufSerializer(clazz) || isInWhitelistClassName(clazz)
  }

  private def isBoundToProtobufSerializer(clazz: Class[_]): Boolean = {
    try {
      val boundSerializer = serialization.serializerFor(clazz)
      boundSerializer.isInstanceOf[ProtobufSerializer]
    } catch {
      case NonFatal(_) => false // not bound
    }
  }

  private def isInWhitelistClassName(clazz: Class[_]): Boolean = {
    whitelistClassNames(clazz.getName) ||
    whitelistClassNames(clazz.getSuperclass.getName) ||
    clazz.getInterfaces.exists(c => whitelistClassNames(c.getName))
  }
}
