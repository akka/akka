/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import java.util
import java.util.Comparator

/** INTERNAL API: Versioned compression table to be advertised between systems */
private[remote] final case class CompressionTable[T](version: Int, map: Map[T, Int]) {
  import CompressionTable.NotCompressedId

  def compress(value: T): Int =
    map.get(value) match {
      case Some(id) ⇒ id
      case None     ⇒ NotCompressedId
    }

  def invert: DecompressionTable[T] =
    if (map.isEmpty) DecompressionTable.empty[T].copy(version = version)
    else {
      // TODO: these are some expensive sanity checks, about the numbers being consecutive, without gaps
      // TODO: we can remove them, make them re-map (not needed I believe though)
      val expectedGaplessSum = Integer.valueOf((map.size * (map.size + 1)) / 2) /* Dirichlet */
      require(map.values.min == 0, "Compression table should start allocating from 0, yet lowest allocated id was " + map.values.min)
      require(map.values.sum + map.size == expectedGaplessSum, "Given compression map does not seem to be gap-less and starting from zero, " +
        "which makes compressing it into an Array difficult, bailing out! Map was: " + map)

      val tups = Array.ofDim[(Object, Int)](map.size).asInstanceOf[Array[(T, Int)]]
      val ts = Array.ofDim[Object](map.size).asInstanceOf[Array[T]]

      var i = 0
      val mit = map.iterator
      while (i < tups.length) {
        tups(i) = mit.next()
        i += 1
      }
      util.Arrays.sort(tups, CompressionTable.compareBy2ndValue[T])

      i = 0
      while (i < tups.length) {
        ts(i) = tups(i)._1
        i += 1
      }

      DecompressionTable[T](version, ts)
    }
}
/** INTERNAL API */
private[remote] object CompressionTable {
  final val NotCompressedId = -1

  final val CompareBy2ndValue: Comparator[(Object, Int)] = new Comparator[(Object, Int)] {
    override def compare(o1: (Object, Int), o2: (Object, Int)): Int =
      o1._2 compare o2._2
  }
  def compareBy2ndValue[T]: Comparator[Tuple2[T, Int]] = CompareBy2ndValue.asInstanceOf[Comparator[(T, Int)]]

  private[this] val _empty = new CompressionTable[Any](0, Map.empty)
  def empty[T] = _empty.asInstanceOf[CompressionTable[T]]
}
