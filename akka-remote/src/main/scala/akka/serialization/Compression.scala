/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Compression {

  /**
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object LZF {
    import voldemort.store.compress.lzf._
    def compress(bytes: Array[Byte]): Array[Byte] = LZFEncoder encode bytes
    def uncompress(bytes: Array[Byte]): Array[Byte] = LZFDecoder decode bytes
  }
}

