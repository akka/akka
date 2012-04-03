/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

@deprecated("will be removed to sanitize dependencies, use Voldemort directly", "2.0.1")
object Compression {

  object LZF {
    import voldemort.store.compress.lzf._
    def compress(bytes: Array[Byte]): Array[Byte] = LZFEncoder encode bytes
    def uncompress(bytes: Array[Byte]): Array[Byte] = LZFDecoder decode bytes
  }
}

