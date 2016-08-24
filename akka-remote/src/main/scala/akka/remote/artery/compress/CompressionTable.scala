/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

/** INTERNAL API: Versioned compression table to be advertised between systems */
private[akka] final case class CompressionTable[T](version: Int, map: Map[T, Int]) {
  import CompressionTable.NotCompressedId

  def compress(value: T): Int =
    map.get(value) match {
      case Some(id) ⇒ id
      case None     ⇒ NotCompressedId
    }

  def invert: DecompressionTable[T] =
    if (map.isEmpty) DecompressionTable.empty[T].copy(version = version)
    else {
      // TODO: these are some expensive sanity checks, about the numbers being consequitive, without gaps
      // TODO: we can remove them, make them re-map (not needed I believe though)
      val expectedGaplessSum = Integer.valueOf((map.size * (map.size + 1)) / 2) /* Dirichlet */
      require(map.values.min == 0, "Compression table should start allocating from 0, yet lowest allocated id was " + map.values.min)
      require(map.values.sum + map.size == expectedGaplessSum, "Given compression map does not seem to be gap-less and starting from zero, " +
        "which makes compressing it into an Array difficult, bailing out! Map was: " + map)

      val vals = map.toList.sortBy(_._2).iterator.map(_._1)
      val dtab = Array.ofDim[Object](map.size).asInstanceOf[Array[T]]
      vals.copyToArray(dtab) // TODO HEAVY, AVOID COPYING AND THE MAP ETC!!!
      DecompressionTable[T](version, dtab)
    }
}
/** INTERNAL API */
private[remote] object CompressionTable {
  final val NotCompressedId = -1

  private[this] val _empty = new CompressionTable[Any](0, Map.empty)
  def empty[T] = _empty.asInstanceOf[CompressionTable[T]]
}
