/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import akka.annotation.InternalApi
import akka.protobufv3.internal.UnsafeByteOperations
import akka.util.ByteString
import akka.protobufv3.internal.{ ByteString => ProtoByteString }
import akka.util.ByteString.ByteString1C

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object ByteStringUtils {
  def toProtoByteStringUnsafe(bytes: ByteString): ProtoByteString = {
    if (bytes.isEmpty)
      ProtoByteString.EMPTY
    else if (bytes.isInstanceOf[ByteString1C]) {
      UnsafeByteOperations.unsafeWrap(bytes.toArrayUnsafe())
    } else {
      // zero copy, reuse the same underlying byte arrays
      bytes.asByteBuffers.foldLeft(ProtoByteString.EMPTY) { (acc, byteBuffer) =>
        acc.concat(UnsafeByteOperations.unsafeWrap(byteBuffer))
      }
    }
  }

  def toProtoByteStringUnsafe(bytes: Array[Byte]): ProtoByteString = {
    if (bytes.isEmpty)
      ProtoByteString.EMPTY
    else {
      UnsafeByteOperations.unsafeWrap(bytes)
    }
  }
}
