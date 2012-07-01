/**
 * Copyright (C) 2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.io

//#imports
import akka.actor._
import akka.util.{ ByteString, ByteStringBuilder, ByteIterator }
//#imports

abstract class BinaryDecoding {
  //#decoding
  implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN

  val FrameDecoder = for {
    frameLenBytes ← IO.take(4)
    frameLen = frameLenBytes.iterator.getInt
    frame ← IO.take(frameLen)
  } yield {
    val in = frame.iterator

    val n = in.getInt
    val m = in.getInt

    val a = Array.newBuilder[Short]
    val b = Array.newBuilder[Long]

    for (i ← 1 to n) {
      a += in.getShort
      b += in.getInt
    }

    val data = Array.ofDim[Double](m)
    in.getDoubles(data)

    (a.result, b.result, data)
  }

  //#decoding
}

abstract class RestToSeq {
  implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN
  val bytes: ByteString
  val in = bytes.iterator

  //#rest-to-seq
  val n = in.getInt
  val m = in.getInt
  // ... in.get...
  val rest: ByteString = in.toSeq
  //#rest-to-seq
}

abstract class BinaryEncoding {
  //#encoding
  implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN

  val a: Array[Short]
  val b: Array[Long]
  val data: Array[Double]

  val frameBuilder = ByteString.newBuilder

  val n = a.length
  val m = data.length

  frameBuilder.putInt(n)
  frameBuilder.putInt(m)

  for (i ← 0 to n - 1) {
    frameBuilder.putShort(a(i))
    frameBuilder.putLong(b(i))
  }
  frameBuilder.putDoubles(data)
  val frame = frameBuilder.result()
  //#encoding

  //#sending
  val socket: IO.SocketHandle
  socket.write(ByteString.newBuilder.putInt(frame.length).result)
  socket.write(frame)
  //#sending
}
