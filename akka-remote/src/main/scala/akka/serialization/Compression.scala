/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

object Compression {

  object LZF {
    import voldemort.store.compress.lzf._
    def compress(bytes: Array[Byte]): Array[Byte] = LZFEncoder encode bytes
    def uncompress(bytes: Array[Byte]): Array[Byte] = LZFDecoder decode bytes
  }
}

