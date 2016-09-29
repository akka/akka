/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

/** INTERNAL API */
private[remote] final case class DecompressionTable[T](originUid: Long, version: Byte, table: Array[T]) {

  private[this] val length = table.length

  def get(idx: Int): T = {
    if (idx >= length)
      throw new IllegalArgumentException(s"Attempted decompression of unknown id: [$idx]! " +
        s"Only $length ids allocated in table version [$version] for origin [$originUid].")
    table(idx)
  }

  def invert: CompressionTable[T] =
    CompressionTable(originUid, version, Map(table.zipWithIndex: _*))

  /** Writes complete table as String (heavy operation) */
  override def toString =
    s"DecompressionTable($originUid, $version, " +
      s"Map(${table.zipWithIndex.map({ case (t, i) ⇒ s"$i -> $t" }).mkString(",")}))"
}

/** INTERNAL API */
private[remote] object DecompressionTable {
  private[this] val _empty = DecompressionTable(0, 0, Array.empty)
  def empty[T] = _empty.asInstanceOf[DecompressionTable[T]]
}
