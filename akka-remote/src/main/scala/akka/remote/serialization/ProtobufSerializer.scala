/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.serialization

import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ ActorRef, ExtendedActorSystem }
import akka.remote.WireFormats.ActorRefData
import akka.serialization.{ Serialization, BaseSerializer }

import scala.annotation.tailrec

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

  override def includeManifest: Boolean = true

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    manifest match {
      case Some(clazz) ⇒
        @tailrec
        def parsingMethod(method: Method = null): Method = {
          val parsingMethodBinding = parsingMethodBindingRef.get()
          parsingMethodBinding.get(clazz) match {
            case Some(cachedParsingMethod) ⇒ cachedParsingMethod
            case None ⇒
              val unCachedParsingMethod =
                if (method eq null) clazz.getDeclaredMethod("parseFrom", ProtobufSerializer.ARRAY_OF_BYTE_ARRAY: _*)
                else method
              if (parsingMethodBindingRef.compareAndSet(parsingMethodBinding, parsingMethodBinding.updated(clazz, unCachedParsingMethod)))
                unCachedParsingMethod
              else
                parsingMethod(unCachedParsingMethod)
          }
        }
        parsingMethod().invoke(null, bytes)

      case None ⇒ throw new IllegalArgumentException("Need a protobuf message class to be able to serialize bytes using protobuf")
    }
  }

  override def toBinary(obj: AnyRef): Array[Byte] = {
    val clazz = obj.getClass
    @tailrec
    def toByteArrayMethod(method: Method = null): Method = {
      val toByteArrayMethodBinding = toByteArrayMethodBindingRef.get()
      toByteArrayMethodBinding.get(clazz) match {
        case Some(cachedtoByteArrayMethod) ⇒ cachedtoByteArrayMethod
        case None ⇒
          val unCachedtoByteArrayMethod =
            if (method eq null) clazz.getMethod("toByteArray")
            else method
          if (toByteArrayMethodBindingRef.compareAndSet(toByteArrayMethodBinding, toByteArrayMethodBinding.updated(clazz, unCachedtoByteArrayMethod)))
            unCachedtoByteArrayMethod
          else
            toByteArrayMethod(unCachedtoByteArrayMethod)
      }
    }
    toByteArrayMethod().invoke(obj).asInstanceOf[Array[Byte]]
  }
}
