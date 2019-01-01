/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.compress

import java.util
import java.util.Comparator

/**
 * INTERNAL API: Versioned compression table to be advertised between systems
 *
 * @param version Either -1 for disabled or a version between 0 and 127
 */
private[remote] final case class CompressionTable[T](originUid: Long, version: Byte, dictionary: Map[T, Int]) {
  import CompressionTable.NotCompressedId

  def compress(value: T): Int =
    dictionary.get(value) match {
      case Some(id) ⇒ id
      case None     ⇒ NotCompressedId
    }

  def invert: DecompressionTable[T] =
    if (dictionary.isEmpty) DecompressionTable.empty[T].copy(originUid = originUid, version = version)
    else {
      // TODO: these are some expensive sanity checks, about the numbers being consecutive, without gaps
      // TODO: we can remove them, make them re-map (not needed I believe though)
      val expectedGaplessSum = Integer.valueOf((dictionary.size * (dictionary.size + 1)) / 2) /* Dirichlet */
      require(dictionary.values.min == 0, "Compression table should start allocating from 0, yet lowest allocated id was " + dictionary.values.min)
      require(dictionary.values.sum + dictionary.size == expectedGaplessSum, "Given compression map does not seem to be gap-less and starting from zero, " +
        "which makes compressing it into an Array difficult, bailing out! Map was: " + dictionary)

      val tups = new Array[(Object, Int)](dictionary.size).asInstanceOf[Array[(T, Int)]]
      val ts = new Array[Object](dictionary.size).asInstanceOf[Array[T]]

      var i = 0
      val mit = dictionary.iterator
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

      DecompressionTable[T](originUid, version, ts)
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

  private[this] val _empty = new CompressionTable[Any](0, 0, Map.empty)
  def empty[T] = _empty.asInstanceOf[CompressionTable[T]]
}
