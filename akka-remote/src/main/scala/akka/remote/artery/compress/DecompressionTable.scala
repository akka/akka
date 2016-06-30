/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

/** INTERNAL API */
private[remote] final case class DecompressionTable[T](version: Long, table: Array[T]) {
  def get(idx: Int): T = table(idx)

  def invert: CompressionTable[T] =
    CompressionTable(version, Map(table.zipWithIndex: _*))

  /** Writes complete table as String (heavy operation) */
  def toDebugString =
    getClass.getName +
      s"(version: $version, " +
      (
        if (table.length == 0) "[empty]"
        else s"table: [${table.zipWithIndex.map({ case (t, i) ⇒ s"$i -> $t" }).mkString(",")}"
      ) + "])"
}

/** INTERNAL API */
private[remote] object DecompressionTable {
  private[this] val _empty = DecompressionTable(0, Array.empty)
  def empty[T] = _empty.asInstanceOf[DecompressionTable[T]]
}
