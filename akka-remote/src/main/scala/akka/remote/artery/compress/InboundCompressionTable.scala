/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.actor.{ Address, ActorRef, ActorSystem }
import akka.event.Logging

final class InboundActorRefCompressionTable(
  system:                   ActorSystem,
  heavyHitters:             TopHeavyHitters[ActorRef],
  onNewHeavyHitterDetected: AdvertiseCompressionId[ActorRef]
) extends InboundCompressionTable[ActorRef](system, heavyHitters, _.path.toSerializationFormat, onNewHeavyHitterDetected) {

  preAllocate(
    system.deadLetters
  )

  /* Since the table is empty here, anything we increment here becomes a heavy hitter immediately. */
  def preAllocate(allocations: ActorRef*): Unit = {
    allocations foreach { case ref ⇒ increment(null, ref, 100000) }
  }

  override def shouldAdvertiseCompressionId(idx: Int): Boolean =
    idx > 0 // 0 is special => deadLetters

  override def decompress(idx: Int): ActorRef =
    if (idx == 0) system.deadLetters
    else super.decompress(idx)
}

/**
 * Handles counting and detecting of heavy-hitters and compressing them via a table lookup.
 * Mutable and not thread-safe.
 *
 * Compression flow goes like:
 * [1]  on each message we add the actor path here
 * [2]  if it becomes a heavy hitter, we allocate an identifier for it and invoke the callback
 * [3]> the callback for example then triggers an CompressionAdvertisement to the receiving side
 */
// TODO should the onHeavyHitter be inside HeavyHitters?
class InboundCompressionTable[T](
  system:                   ActorSystem,
  heavyHitters:             TopHeavyHitters[T],
  convertKeyToString:       T ⇒ String,
  onNewHeavyHitterDetected: AdvertiseCompressionId[T]) {
  require(heavyHitters != null, "heavyHitters must not be null")

  private val settings = CompressionSettings(system)
  val log = Logging(system, "InboundCompressionTable")

  // TODO calibrate properly (h/w have direct relation to preciseness and max capacity)
  private[this] val cms = new CountMinSketch(100, 100, System.currentTimeMillis().toInt)

  @volatile private[this] var compressionAllocations = Map.empty[Int, T] // TODO replace with a specialized LongMap
  private[this] var currentCompressionId = InboundCompressionTable.CompressionAllocationCounterStart

  /**
   * Decompress given identifier into original String representation.
   *
   * @throws UnknownCompressedIdException if given id is not known, this may indicate a bug – such situation should not happen.
   */
  def decompress(idx: Int): T = {
    if (settings.debug) log.debug(s"Decompress [{}] => {}", idx, compressionAllocations.get(idx))
    compressionAllocations.get(idx) match {
      case Some(value) ⇒ value
      case None        ⇒ throw new UnknownCompressedIdException(idx)
    }
  }

  /**
   * Add `n` occurance for the given key and call `heavyHittedDetected` if element has become a heavy hitter.
   * Empty keys are omitted.
   */
  // TODO not so happy about passing around address here, but in incoming there's no other earlier place to get it?
  def increment(remoteAddress: Address, value: T, n: Long): Unit = {
    val key = convertKeyToString(value)
    if (shouldIgnore(key)) {
      // ignore...
    } else {
      //      val countBefore = cms.estimateCount(key)
      val count = cms.addAndEstimateCount(key, n)
      //      log.warning(s"HIT: increment $key + $n => ($countBefore->) $count; (addAndCheckIfheavyHitterDetected(value, count) = ${addAndCheckIfheavyHitterDetected(value, count)}); (!wasCompressedPreviously(key) = ${!wasCompressedPreviously(key)})")

      // TODO optimise order of these, what is more expensive? (now the `previous` is, but if aprox datatype there it would be faster)... Needs pondering.
      if (addAndCheckIfheavyHitterDetected(value, count) && !wasCompressedPreviously(key)) {
        val idx = allocateCompressedId(value)
        log.debug("Allocated compression id [" + idx + "] for [" + value + "], in association with [" + remoteAddress + "]")
        if (shouldAdvertiseCompressionId(idx)) { // TODO change to "time based accumulate new table => advertise it"
          // TODO guard with if
          log.debug(s"Inbound: Heavy hitter detected: [{} => $idx], {} hits recorded for it (confidence: {}, relative error (eps) {}).\n" +
            s"All allocations: ${compressionAllocations}", key, count, cms.getConfidence, cms.getRelativeError)
          onNewHeavyHitterDetected(remoteAddress, value, idx) // would be used to signal via side-channel to OutboundCompression that we want to send a ActorRefCompressionAdvertisement
        }
      }
    }
  }

  /** Some compression IDs are special and known upfront by both sides, thus need not be advertised (e.g. deadLetters => 0) */
  def shouldAdvertiseCompressionId(idx: Int): Boolean =
    true // TODO this will be different in the "advertise entire table mode", it will be "once table is big enough or much time passed"

  private def shouldIgnore(key: String) = { // TODO this is hacky, if we'd do this we trigger compression too early (before association exists, so control messages fail)
    key match {
      case null ⇒ true
      case ""   ⇒ true // empty class manifest for example
      case _    ⇒ key.endsWith("/system/dummy") || key.endsWith("/") // TODO dummy likely shouldn't exist? can we remove it?
    }
  }

  // TODO this must be optimised, we really don't want to scan the entire key-set each time to make sure
  private def wasCompressedPreviously(key: String): Boolean =
    compressionAllocations.values.exists(_ == key) // TODO expensive, aprox or something sneakier?

  /** Mutates heavy hitters */
  private def addAndCheckIfheavyHitterDetected(value: T, count: Long): Boolean = {
    heavyHitters.update(value, count)
  }

  private def allocateCompressedId(value: T): Int = {
    val idx = nextCompressionId()
    compressionAllocations.get(idx) match {
      case Some(previouslyCompressedValue) ⇒
        // should never really happen, but let's not assume that
        throw new ExistingcompressedIdReuseAttemptException(idx, previouslyCompressedValue)

      case None ⇒
        // good, the idx is not used so we can allocate it
        compressionAllocations = compressionAllocations.updated(idx, value)
        idx
    }
  }

  private def nextCompressionId(): Int = {
    val id = currentCompressionId
    currentCompressionId += 1
    id
  }

  override def toString =
    s"""${getClass.getSimpleName}(countMinSketch: $cms, heavyHitters: $heavyHitters)"""

}

object InboundCompressionTable {
  val CompressionAllocationCounterStart = 0
  //  val CompressionAllocationCounterStart = 64L // we leave 64 slots (0 counts too) for pre-allocated Akka compressions
}

final class ExistingcompressedIdReuseAttemptException(id: Long, value: Any)
  extends RuntimeException(
    s"Attempted to re-allocate compressedId [$id] which is still in use for compressing [$value]! " +
      s"This should never happen and is likely an implementation bug.")

final class UnknownCompressedIdException(id: Long)
  extends RuntimeException(
    s"Attempted de-compress unknown id [$id]! " +
      s"This could happen if this node has started a new ActorSystem bound to the same address as previously, " +
      s"and previous messages from a remote system were still in flight (using an old compression table). " +
      s"The remote system is expected to drop the compression table and this system will advertise a new one.")
