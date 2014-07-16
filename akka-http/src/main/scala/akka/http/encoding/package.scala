/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.http.model.headers.HttpEncoding
import akka.http.model.HttpMessage

package object encoding {
  trait Encoder {
    def encoding: HttpEncoding

    def encode[T <: HttpMessage](message: T): T#Self
    def newCompressor: Compressor
  }
  trait Decoder {
    def encoding: HttpEncoding
  }

  trait NYIEncoder extends Encoder with Decoder {
    def encoding = routing.FIXME
    def encode[T <: HttpMessage](message: T): T#Self = routing.FIXME
    def newCompressor: Compressor = routing.FIXME
  }

  object Deflate extends Encoder with Decoder with NYIEncoder
  object Gzip extends Encoder with Decoder with NYIEncoder
  object NoEncoding extends Encoder with Decoder with NYIEncoder

  trait Compressor {
    def compress(buffer: Array[Byte]): this.type

    def flush(): Array[Byte]

    def finish(): Array[Byte]
  }
}
