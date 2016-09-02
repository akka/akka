/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

/** INTERNAL API */
private[artery] final case class DecompressionTable[T](version: Int, table: Array[T]) {
  // TODO version maybe better as Long? // OR implement roll-over
  private[this] val length = table.length

  def get(idx: Int): T = {
    if (idx >= length)
      throw new IllegalArgumentException(s"Attempted decompression of unknown id: [$idx]! " +
        s"Only $length ids allocated in table version [$version].")
    table(idx)
  }

  def invert: CompressionTable[T] =
    CompressionTable(version, Map(table.zipWithIndex: _*))

  /** Writes complete table as String (heavy operation) */
  def toDebugString =
    getClass.getName +
      s"(version: $version, " +
      (
        if (length == 0) "[empty]"
        else s"table: [${table.zipWithIndex.map({ case (t, i) ⇒ s"$i -> $t" }).mkString(",")}") + "])"
}

/** INTERNAL API */
private[artery] object DecompressionTable {
  private[this] val _empty = DecompressionTable(0, Array.empty)
  def empty[T] = _empty.asInstanceOf[DecompressionTable[T]]
}
