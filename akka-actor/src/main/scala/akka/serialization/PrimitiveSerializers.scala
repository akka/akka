/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization

import java.nio.ByteBuffer

import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi

/**
 * INTERNAL API: only public by configuration
 */
@InternalApi private[akka] final class BooleanSerializer(val system: ExtendedActorSystem)
    extends Serializer
    with ByteBufferSerializer {

  import java.lang.Boolean.{ FALSE, TRUE }

  private val FalseB = 0.toByte
  private val TrueB = 1.toByte

  override def includeManifest: Boolean = false

  override val identifier: Int = BaseSerializer.identifierFromConfig(getClass, system)

  override def toBinary(o: AnyRef, buf: ByteBuffer): Unit = {
    val flag = o match {
      case TRUE  => TrueB
      case FALSE => FalseB
    }
    buf.put(flag)
  }

  override def fromBinary(buf: ByteBuffer, manifest: String): AnyRef = {
    buf.get() match {
      case TrueB  => TRUE
      case FalseB => FALSE
    }
  }

  override def toBinary(o: AnyRef): Array[Byte] = {
    val flag = o match {
      case TRUE  => TrueB
      case FALSE => FalseB
    }
    val result = new Array[Byte](1)
    result(0) = flag
    result
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    bytes(0) match {
      case TrueB  => TRUE
      case FalseB => FALSE
    }
  }
}
