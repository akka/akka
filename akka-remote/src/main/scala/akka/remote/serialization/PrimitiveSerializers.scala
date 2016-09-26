package akka.remote.serialization

import java.nio.ByteBuffer

import akka.actor.{ ExtendedActorSystem, Kill, PoisonPill }
import akka.serialization.{ BaseSerializer, ByteBufferSerializer }

class LongSerializer(val system: ExtendedActorSystem) extends BaseSerializer with ByteBufferSerializer {
  override def includeManifest: Boolean = false

  override def toBinary(o: AnyRef, buf: ByteBuffer): Unit = {
    buf.putLong(Long.unbox(o))
  }

  override def fromBinary(buf: ByteBuffer, manifest: String): AnyRef = {
    Long.box(buf.getLong)
  }

  override def toBinary(o: AnyRef): Array[Byte] = {
    val result = Array.ofDim[Byte](8)
    var long = Long.unbox(o)
    var i = 0
    while (long != 0) {
      result(i) = (long & 0xFF).toByte
      i += 1
      long >>>= 8
    }
    result
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    var result = 0L
    var i = 7
    while (i >= 0) {
      result <<= 8
      result |= (bytes(i).toLong & 0xFF)
      i -= 1
    }
    Long.box(result)
  }
}

class IntSerializer(val system: ExtendedActorSystem) extends BaseSerializer with ByteBufferSerializer {
  override def includeManifest: Boolean = false

  override def toBinary(o: AnyRef, buf: ByteBuffer): Unit = buf.putInt(Int.unbox(o))

  override def fromBinary(buf: ByteBuffer, manifest: String): AnyRef = Int.box(buf.getInt)

  override def toBinary(o: AnyRef): Array[Byte] = {
    val result = Array.ofDim[Byte](4)
    var int = Int.unbox(o)
    var i = 0
    while (int != 0) {
      result(i) = (int & 0xFF).toByte
      i += 1
      int >>>= 8
    }
    result
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    var result = 0
    var i = 3
    while (i >= 0) {
      result <<= 8
      result |= (bytes(i).toInt & 0xFF)
      i -= 1
    }
    Int.box(result)
  }
}

class StringSerializer(val system: ExtendedActorSystem) extends BaseSerializer with ByteBufferSerializer {
  override def includeManifest: Boolean = false

  override def toBinary(o: AnyRef, buf: ByteBuffer): Unit = buf.put(toBinary(o))

  override def fromBinary(buf: ByteBuffer, manifest: String): AnyRef = {
    val bytes = Array.ofDim[Byte](buf.remaining())
    buf.get(bytes)
    new String(bytes, "UTF-8")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o.asInstanceOf[String].getBytes("UTF-8")

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = new String(bytes, "UTF-8")

}
