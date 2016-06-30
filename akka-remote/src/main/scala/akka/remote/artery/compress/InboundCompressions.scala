/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ ActorRef, ActorSystem, Address }
import akka.event.Logging
import akka.remote.artery.{ InboundContext, OutboundContext }
import akka.stream.impl.ConstantFun
import akka.util.{ OptionVal, PrettyDuration }

import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * INTERNAL API
 * Dedicated per remote system inbound compression table.
 *
 * The outbound context is available by looking it up in the association.
 * It can be used to advertise a compression table.
 * If the association is not complete - we simply dont advertise the table, which is fine (handshake not yet complete).
 */
private[remote] final class InboundActorRefCompression(
  system:         ActorSystem,
  settings:       CompressionSettings,
  originUid:      Long,
  inboundContext: InboundContext,
  heavyHitters:   TopHeavyHitters[ActorRef]
) extends InboundCompression[ActorRef](system, settings, originUid, inboundContext, heavyHitters, _.path.toSerializationFormat) {

  preAllocate(system.deadLetters)

  /* Since the table is empty here, anything we increment here becomes a heavy hitter immediately. */
  def preAllocate(allocations: ActorRef*): Unit = {
    allocations foreach { case ref ⇒ increment(null, ref, 100000) }
  }

  override def decompress(tableId: Long, idx: Int): OptionVal[ActorRef] =
    if (idx == 0) OptionVal.Some(system.deadLetters)
    else super.decompress(tableId, idx)

  scheduleNextTableAdvertisement()
  override protected def tableAdvertisementInterval = settings.actorRefs.advertisementInterval

  def advertiseCompressionTable(association: OutboundContext, table: CompressionTable[ActorRef]): Unit = {
    log.debug(s"Advertise ActorRef compression [$table] to [${association.remoteAddress}]")
    association.sendControl(CompressionProtocol.ActorRefCompressionAdvertisement(inboundContext.localAddress, table))
  }
}

final class InboundManifestCompression(
  system:         ActorSystem,
  settings:       CompressionSettings,
  originUid:      Long,
  inboundContext: InboundContext,
  heavyHitters:   TopHeavyHitters[String]
) extends InboundCompression[String](system, settings, originUid, inboundContext, heavyHitters, ConstantFun.scalaIdentityFunction) {

  scheduleNextTableAdvertisement()
  override protected def tableAdvertisementInterval = settings.manifests.advertisementInterval

  override def advertiseCompressionTable(association: OutboundContext, table: CompressionTable[String]): Unit = {
    log.debug(s"Advertise ClassManifest compression [$table] to [${association.remoteAddress}]")
    association.sendControl(CompressionProtocol.ClassManifestCompressionAdvertisement(inboundContext.localAddress, table))
  }
}

/**
 * INTERNAL API
 * Handles counting and detecting of heavy-hitters and compressing them via a table lookup.
 */
private[remote] abstract class InboundCompression[T >: Null](
  val system:         ActorSystem,
  val settings:       CompressionSettings,
  originUid:          Long,
  inboundContext:     InboundContext,
  val heavyHitters:   TopHeavyHitters[T],
  convertKeyToString: T ⇒ String) { // TODO avoid converting to string, in order to use the ActorRef.hashCode!

  val log = Logging(system, "InboundCompressionTable")

  // TODO atomic / state machine? the InbouncCompression could even extend ActomicReference[State]!

  // TODO NOTE: there exist edge cases around, we advertise table 1, accumulate table 2, the remote system has not used 2 yet,
  // yet we technically could already prepare table 3, then it starts using table 1 suddenly. Edge cases like that.
  // SOLUTION 1: We don't start building new tables until we've seen the previous one be used (move from new to active)
  //             This is nice as it practically disables all the "build the table" work when the other side is not interested in using it.
  // SOLUTION 2: We end up dropping messages when old table comes in (we do that anyway)

  // TODO have a marker that "advertised table XXX", so we don't generate a new-new one until the new one is in use?

  // 2 tables are used, one is "still in use", and the
  @volatile private[this] var activeTable = DecompressionTable.empty[T]
  @volatile private[this] var nextTable = DecompressionTable.empty[T]

  // TODO calibrate properly (h/w have direct relation to preciseness and max capacity)
  private[this] val cms = new CountMinSketch(100, 100, System.currentTimeMillis().toInt)

  /* ==== COMPRESSION ==== */

  /**
   * Decompress given identifier into its original representation.
   * Passed in tableIds must only ever be in not-decreasing order (as old tables are dropped),
   * tableIds must not have gaps. If an "old" tableId is received the value will fail to be decompressed.
   *
   * @throws UnknownCompressedIdException if given id is not known, this may indicate a bug – such situation should not happen.
   */
  // not tailrec because we allow special casing in sub-class, however recursion is always at most 1 level deep
  def decompress(tableVersion: Long, idx: Int): OptionVal[T] = {
    val activeVersion = activeTable.version // TODO move into state

    if (tableVersion == -1) OptionVal.None // no compression, bail out early
    else if (tableVersion == activeVersion) {
      val value: T = activeTable.get(idx)
      if (settings.debug) log.debug(s"Decompress [{}] => {}", idx, value)
      if (value != null) OptionVal.Some[T](value)
      else throw new UnknownCompressedIdException(idx)
    } else if (tableVersion < activeVersion) {
      log.warning("Received value compressed with old table: [{}], current table version is: [{}]", tableVersion, activeVersion)
      OptionVal.None
    } else if (tableVersion == nextTable.version) {
      flipTables()
      decompress(tableVersion, idx) // recurse, activeTable will not be able to handle this
    } else {
      // which means that incoming version was > nextTable.version, which likely is a bug
      log.error("Inbound message is using compression table version higher than the highest allocated table on this node. " +
        "This should not happen! State: activeTable: {}, nextTable, incoming tableVersion: {}", activeVersion, nextTable, tableVersion)
      OptionVal.None
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
      val count = cms.addAndEstimateCount(key, n)

      // TODO optimise order of these, what is more expensive? 
      // TODO (now the `previous` is, but if aprox datatype there it would be faster)... Needs pondering.
      val wasHeavyHitter = addAndCheckIfheavyHitterDetected(value, count)
      if (wasHeavyHitter)
        log.debug(s"Heavy hitter detected: {} [count: {}]", value, count)
      //      if (wasHeavyHitter && !wasCompressedPreviously(key)) {
      //        val idx = prepareCompressionAdvertisement()
      //        log.debug("Allocated compression id [" + idx + "] for [" + value + "], in association with [" + remoteAddress + "]")
      //      }
    }
  }

  private def shouldIgnore(key: String) = { // TODO this is hacky, if we'd do this we trigger compression too early (before association exists, so control messages fail)
    key match {
      case null ⇒ true
      case ""   ⇒ true // empty class manifest for example
      case _    ⇒ key.endsWith("/system/dummy") || key.endsWith("/") // TODO dummy likely shouldn't exist? can we remove it?
    }
  }

  // TODO this must be optimised, we really don't want to scan the entire key-set each time to make sure
  private def wasCompressedPreviously(key: String): Boolean = {
    var i = 0
    val len = activeTable.table.length
    while (i < len) {
      if (activeTable.table(i) == key) return true
      i += 1
    }
    false
  }

  /** Mutates heavy hitters */
  private def addAndCheckIfheavyHitterDetected(value: T, count: Long): Boolean = {
    heavyHitters.update(value, count)
  }

  /* ==== TABLE ADVERTISEMENT ==== */

  protected def tableAdvertisementInterval: Duration

  /**
   * INTERNAL / TESTING API
   * Used for manually triggering when a compression table should be advertised.
   * Note that most likely you'd want to set the advertisment-interval to `0` when using this.
   *
   * TODO: Technically this would be solvable by a "triggerable" scheduler.
   */
  private[remote] def triggerNextTableAdvertisement(): Unit = // TODO expose and use in tests
    runNextTableAdvertisement()

  def scheduleNextTableAdvertisement(): Unit =
    tableAdvertisementInterval match {
      case d: FiniteDuration ⇒
        try {
          system.scheduler.scheduleOnce(d, ScheduledTableAdvertisementRunnable)(system.dispatcher)
          log.debug("Scheduled {} advertisement in [{}] from now...", getClass.getSimpleName, PrettyDuration.format(tableAdvertisementInterval, includeNanos = false, 1))
        } catch {
          case ex: IllegalStateException ⇒
            log.warning("Unable to schedule {} advertisement, " +
              "likely system is shutting down. " +
              "Reason: {}", getClass.getName, ex.getMessage)
        }
      case _ ⇒ // ignore...
    }

  private val ScheduledTableAdvertisementRunnable = new Runnable {
    override def run(): Unit =
      try runNextTableAdvertisement()
      finally scheduleNextTableAdvertisement()
  }

  /**
   * Entry point to advertising a new compression table.
   *
   * [1] First we must *hand the new table over to the Incoming compression side on this system*,
   *     so it will not be used by someone else before "we" know about it in the Decoder.
   * [2] Then the table must be *advertised to the remote system*, and MAY start using it immediately
   *
   * It must be advertised to the other side so it can start using it in its outgoing compression.
   * Triggers compression table advertisement. May be triggered by schedule or manually, i.e. for testing.
   */
  def runNextTableAdvertisement() = { // TODO guard against re-entrancy?
    inboundContext.association(originUid) match {
      case OptionVal.Some(association) ⇒
        val table = prepareCompressionAdvertisement()
        nextTable = table.invert // TODO expensive, check if building the other way wouldn't be faster?
        advertiseCompressionTable(association, table)

      case OptionVal.None ⇒
        // otherwise it's too early, association not ready yet.
        // so we don't build the table since we would not be able to send it anyway.
        log.warning("No Association for originUid [{}] yet, unable to advertise compression table.", originUid)
    }
  }

  /**
   * Must be implementeed by extending classes in order to send a [[akka.remote.artery.ControlMessage]]
   * of apropriate type to the remote system in order to advertise the compression table to it.
   */
  protected def advertiseCompressionTable(association: OutboundContext, table: CompressionTable[T]): Unit

  /** Drop `activeTable` and start using the `nextTable` in its place. */
  private def flipTables(): Unit = {
    log.debug("Swaping active decompression table to version {}.", nextTable.version)
    activeTable = nextTable
    nextTable = DecompressionTable.empty
    // TODO we want to keep the currentTableVersion in State too, update here as well then
  }

  private def prepareCompressionAdvertisement(): CompressionTable[T] = {
    // TODO surely we can do better than that, optimise
    CompressionTable(activeTable.version + 1, Map(heavyHitters.snapshot.filterNot(_ == null).zipWithIndex: _*))
  }

  override def toString =
    s"""${getClass.getSimpleName}(countMinSketch: $cms, heavyHitters: $heavyHitters)"""

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
