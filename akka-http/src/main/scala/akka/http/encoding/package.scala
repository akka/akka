/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.http.model.headers.HttpEncoding

package object encoding {
  trait Encoder {
    def encoding: HttpEncoding
  }
  trait Decoder {
    def encoding: HttpEncoding
  }
  object Deflate extends Encoder with Decoder {
    def encoding = ???
  }
  object Gzip extends Encoder with Decoder {
    def encoding = ???
  }
  object NoEncoding extends Encoder with Decoder {
    def encoding = ???
  }
}
