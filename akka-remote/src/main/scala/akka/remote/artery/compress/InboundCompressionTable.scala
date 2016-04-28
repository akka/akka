/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

/**
 * Handles counting and detecting of heavy-hitters and compressing them via a table lookup.
 *
 * Not thread safe.
 */
class InboundCompressionTable(compressionPercentage: Double) {
  require(0 <= compressionPercentage && compressionPercentage <= 1, "compressionThreshold should represent a percentage between 0-1 (inclusive).")

  // TODO calibrate properly
  private[this] val cms = new CountMinSketch(10, 10, 0)

  // TODO could be an optimised map since we use incremental longs as keys
  private[this] var compressionAllocations = Map.empty[Long, String]
  private[this] var currentCompressionId = InboundCompressionTable.CompressionAllocationCounterStart

  /**
   * Decompress given identifier into original String representation.
   * @throws UnknownCompressionIdException if given id is not known, this may indicate a bug – such situation should not happen.
   */
  final def decompress(idx: Long): String =
    compressionAllocations.get(idx) match {
      case Some(value) ⇒ value
      case None        ⇒ throw new UnknownCompressionIdException(idx)
    }

  /** Add `1` occurance for the given key and call `heavyHittedDetected` if element has become a heavy hitter. */
  // [1]  on each message we add the actor path here
  // [2]  if it becomes a heavy hitter, we allocate an identifier for it and invoke the callback
  // [3]> the callback for example then triggers an CompressionAdvertisement to the receiving side
  final def add(key: String): Unit = add(key, 1)

  /** Add `n` occurance for the given key and call `heavyHittedDetected` if element has become a heavy hitter. */
  final def add(key: String, n: Int): Unit = {
    cms.add(key, n) // mutable
  }

  private def allocateCompressionId(value: String): Long = {
    val idx = nextCompressionId()

    compressionAllocations.get(idx) match {
      case Some(previouslyCompressedValue) ⇒
        // should never really happen, but let's not assume that
        throw new ExistingCompressionIdReuseAttemptException(idx, previouslyCompressedValue)

      case None ⇒
        // good, the idx is not used so we can allocate it
        compressionAllocations = compressionAllocations.updated(idx, value)
        idx
    }
  }

  private def nextCompressionId(): Long = {
    val id = currentCompressionId
    currentCompressionId += 1
    id
  }

}

object InboundCompressionTable {
  val CompressionAllocationCounterStart = 64L // we leave 64 slots (0 counts too) for pre-allocated Akka compressions
}

final class ExistingCompressionIdReuseAttemptException(id: Long, value: String)
  extends RuntimeException(
    s"Attempted to re-allocate compressionId [$id] which is still in use for compressing [$value]! " +
      s"This should never happen and is likely an implementation bug.")

final class UnknownCompressionIdException(id: Long)
  extends RuntimeException(
    s"Attempted de-compress unknown id [$id]! " +
      s"This should never happen and is likely an implementation bug.")
