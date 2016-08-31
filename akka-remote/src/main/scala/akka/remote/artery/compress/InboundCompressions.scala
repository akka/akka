/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongFunction

import scala.concurrent.duration.{ Duration, FiniteDuration }

import akka.actor.{ ActorRef, ActorSystem, Address }
import akka.event.{ Logging, NoLogging }
import akka.remote.artery.{ ArterySettings, InboundContext, OutboundContext }
import akka.util.{ OptionVal, PrettyDuration }
import org.agrona.collections.Long2ObjectHashMap

/**
 * INTERNAL API
 * Decompress and cause compression advertisements.
 *
 * One per inbound message stream thus must demux by originUid to use the right tables.
 */
private[remote] trait InboundCompressions {
  def hitActorRef(originUid: Long, remote: Address, ref: ActorRef, n: Int): Unit
  def decompressActorRef(originUid: Long, tableVersion: Int, idx: Int): OptionVal[ActorRef]
  def confirmActorRefCompressionAdvertisement(originUid: Long, tableVersion: Int): Unit

  def hitClassManifest(originUid: Long, remote: Address, manifest: String, n: Int): Unit
  def decompressClassManifest(originUid: Long, tableVersion: Int, idx: Int): OptionVal[String]
  def confirmClassManifestCompressionAdvertisement(originUid: Long, tableVersion: Int): Unit
}

/**
 * INTERNAL API
 *
 * One per incoming Aeron stream, actual compression tables are kept per-originUid and created on demand.
 */
private[remote] final class InboundCompressionsImpl(
  system:         ActorSystem,
  inboundContext: InboundContext,
  settings:       ArterySettings.Compression) extends InboundCompressions {

  // FIXME we also must remove the ones that won't be used anymore - when quarantine triggers
  private[this] val _actorRefsIns = new Long2ObjectHashMap[InboundActorRefCompression]()
  private val createInboundActorRefsForOrigin = new LongFunction[InboundActorRefCompression] {
    override def apply(originUid: Long): InboundActorRefCompression = {
      val actorRefHitters = new TopHeavyHitters[ActorRef](settings.ActorRefs.Max)
      new InboundActorRefCompression(system, settings, originUid, inboundContext, actorRefHitters)
    }
  }
  private def actorRefsIn(originUid: Long): InboundActorRefCompression =
    _actorRefsIns.computeIfAbsent(originUid, createInboundActorRefsForOrigin)

  private[this] val _classManifestsIns = new Long2ObjectHashMap[InboundManifestCompression]()
  private val createInboundManifestsForOrigin = new LongFunction[InboundManifestCompression] {
    override def apply(originUid: Long): InboundManifestCompression = {
      val manifestHitters = new TopHeavyHitters[String](settings.Manifests.Max)
      new InboundManifestCompression(system, settings, originUid, inboundContext, manifestHitters)
    }
  }
  private def classManifestsIn(originUid: Long): InboundManifestCompression =
    _classManifestsIns.computeIfAbsent(originUid, createInboundManifestsForOrigin)

  // actor ref compression ---

  override def decompressActorRef(originUid: Long, tableVersion: Int, idx: Int): OptionVal[ActorRef] =
    actorRefsIn(originUid).decompress(tableVersion, idx)
  override def hitActorRef(originUid: Long, address: Address, ref: ActorRef, n: Int): Unit =
    actorRefsIn(originUid).increment(address, ref, n)
  override def confirmActorRefCompressionAdvertisement(originUid: Long, tableVersion: Int): Unit =
    actorRefsIn(originUid).confirmAdvertisement(tableVersion)

  // class manifest compression ---

  override def decompressClassManifest(originUid: Long, tableVersion: Int, idx: Int): OptionVal[String] =
    classManifestsIn(originUid).decompress(tableVersion, idx)
  override def hitClassManifest(originUid: Long, address: Address, manifest: String, n: Int): Unit =
    classManifestsIn(originUid).increment(address, manifest, n)
  override def confirmClassManifestCompressionAdvertisement(originUid: Long, tableVersion: Int): Unit =
    actorRefsIn(originUid).confirmAdvertisement(tableVersion)

  // testing utilities ---

  /** INTERNAL API: for testing only */
  private[remote] def runNextActorRefAdvertisement() = {
    import scala.collection.JavaConverters._
    _actorRefsIns.values().asScala.foreach { inbound ⇒ inbound.runNextTableAdvertisement() }
  }

  /** INTERNAL API: for testing only */
  private[remote] def runNextClassManifestAdvertisement() = {
    import scala.collection.JavaConverters._
    _classManifestsIns.values().asScala.foreach { inbound ⇒ inbound.runNextTableAdvertisement() }
  }
}

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
  settings:       ArterySettings.Compression,
  originUid:      Long,
  inboundContext: InboundContext,
  heavyHitters:   TopHeavyHitters[ActorRef]) extends InboundCompression[ActorRef](system, settings, originUid, inboundContext, heavyHitters) {

  preAllocate(system.deadLetters)

  /* Since the table is empty here, anything we increment here becomes a heavy hitter immediately. */
  def preAllocate(allocations: ActorRef*): Unit = {
    allocations foreach { case ref ⇒ increment(null, ref, 100000) }
  }

  override def decompress(tableVersion: Int, idx: Int): OptionVal[ActorRef] =
    if (idx == 0) OptionVal.Some(system.deadLetters)
    else super.decompress(tableVersion, idx)

  scheduleNextTableAdvertisement()
  override protected def tableAdvertisementInterval = settings.ActorRefs.AdvertisementInterval

  override def advertiseCompressionTable(outboundContext: OutboundContext, table: CompressionTable[ActorRef]): Unit = {
    log.debug(s"Advertise ActorRef compression [$table], from [${inboundContext.localAddress}] to [${outboundContext.remoteAddress}]")
    outboundContext.sendControl(CompressionProtocol.ActorRefCompressionAdvertisement(inboundContext.localAddress, table))
  }
}

final class InboundManifestCompression(
  system:         ActorSystem,
  settings:       ArterySettings.Compression,
  originUid:      Long,
  inboundContext: InboundContext,
  heavyHitters:   TopHeavyHitters[String]) extends InboundCompression[String](system, settings, originUid, inboundContext, heavyHitters) {

  scheduleNextTableAdvertisement()
  override protected def tableAdvertisementInterval = settings.Manifests.AdvertisementInterval

  override lazy val log = NoLogging

  override def advertiseCompressionTable(outboundContext: OutboundContext, table: CompressionTable[String]): Unit = {
    log.debug(s"Advertise ClassManifest compression [$table] to [${outboundContext.remoteAddress}]")
    outboundContext.sendControl(CompressionProtocol.ClassManifestCompressionAdvertisement(inboundContext.localAddress, table))
  }
}
/**
 * INTERNAL API
 */
private[remote] object InboundCompression {

  object State {
    def empty[T] = State(
      oldTable = DecompressionTable.empty[T].copy(version = -1),
      activeTable = DecompressionTable.empty[T],
      nextTable = DecompressionTable.empty[T].copy(version = 1),
      advertisementInProgress = None)
  }

  final case class State[T](
    oldTable:                DecompressionTable[T],
    activeTable:             DecompressionTable[T],
    nextTable:               DecompressionTable[T],
    advertisementInProgress: Option[CompressionTable[T]]) {

    def startUsingNextTable(): State[T] =
      State(
        oldTable = activeTable,
        activeTable = nextTable,
        nextTable = DecompressionTable.empty[T].copy(version = nextTable.version + 1),
        advertisementInProgress = None)
  }

}

/**
 * INTERNAL API
 * Handles counting and detecting of heavy-hitters and compressing them via a table lookup.
 */
private[remote] abstract class InboundCompression[T >: Null](
  val system:       ActorSystem,
  val settings:     ArterySettings.Compression,
  originUid:        Long,
  inboundContext:   InboundContext,
  val heavyHitters: TopHeavyHitters[T]) {

  lazy val log = Logging(system, getClass.getSimpleName)

  // FIXME NOTE: there exist edge cases around, we advertise table 1, accumulate table 2, the remote system has not used 2 yet,
  // yet we technically could already prepare table 3, then it starts using table 1 suddenly. Edge cases like that.
  // SOLUTION 1: We don't start building new tables until we've seen the previous one be used (move from new to active)
  //             This is nice as it practically disables all the "build the table" work when the other side is not interested in using it.
  // SOLUTION 2: We end up dropping messages when old table comes in (we do that anyway)

  private[this] val state: AtomicReference[InboundCompression.State[T]] = new AtomicReference(InboundCompression.State.empty)

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
  def decompress(incomingTableVersion: Int, idx: Int): OptionVal[T] = {
    val current = state.get
    val oldVersion = current.oldTable.version
    val activeVersion = current.activeTable.version

    if (incomingTableVersion == -1) OptionVal.None // no compression, bail out early
    else if (incomingTableVersion == activeVersion) {
      val value: T = current.activeTable.get(idx)
      if (value != null) OptionVal.Some[T](value)
      else throw new UnknownCompressedIdException(idx)
    } else if (incomingTableVersion == oldVersion) {
      // must handle one old table due to messages in flight during advertisement
      val value: T = current.oldTable.get(idx)
      if (value != null) OptionVal.Some[T](value)
      else throw new UnknownCompressedIdException(idx)
    } else if (incomingTableVersion < activeVersion) {
      log.warning("Received value compressed with old table: [{}], current table version is: [{}]", incomingTableVersion, activeVersion)
      OptionVal.None
    } else if (incomingTableVersion == current.nextTable.version) {
      log.debug(
        "Received first value compressed using the next prepared compression table, flipping to it (version: {})",
        current.nextTable.version)
      confirmAdvertisement(incomingTableVersion)
      decompress(incomingTableVersion, idx) // recurse, activeTable will not be able to handle this
    } else {
      // which means that incoming version was > nextTable.version, which likely is a bug
      log.error(
        "Inbound message is using compression table version higher than the highest allocated table on this node. " +
          "This should not happen! State: activeTable: {}, nextTable: {}, incoming tableVersion: {}",
        activeVersion, current.nextTable.version, incomingTableVersion)
      OptionVal.None
    }
  }

  def confirmAdvertisement(tableVersion: Int): Unit = {
    val current = state.get
    current.advertisementInProgress match {
      case Some(inProgress) if tableVersion == inProgress.version ⇒
        if (state.compareAndSet(current, current.startUsingNextTable()))
          log.debug("Confirmed compression table version {}", tableVersion)
      case Some(inProgress) if tableVersion != inProgress.version ⇒
        log.debug("Confirmed compression table version {} but in progress {}", tableVersion, inProgress.version)
      case None ⇒ // already confirmed
    }

  }

  /**
   * Add `n` occurance for the given key and call `heavyHittedDetected` if element has become a heavy hitter.
   * Empty keys are omitted.
   */
  // TODO not so happy about passing around address here, but in incoming there's no other earlier place to get it?
  def increment(remoteAddress: Address, value: T, n: Long): Unit = {
    val count = cms.addObjectAndEstimateCount(value, n)

    // TODO optimise order of these, what is more expensive?
    // TODO (now the `previous` is, but if aprox datatype there it would be faster)... Needs pondering.
    addAndCheckIfheavyHitterDetected(value, count)
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
   */
  private[remote] def triggerNextTableAdvertisement(): Unit = // TODO use this in tests for triggering
    runNextTableAdvertisement()

  def scheduleNextTableAdvertisement(): Unit =
    tableAdvertisementInterval match {
      case d: FiniteDuration ⇒
        try {
          system.scheduler.scheduleOnce(d, ScheduledTableAdvertisementRunnable)(system.dispatcher)
          log.debug("Scheduled {} advertisement in [{}] from now...", getClass.getSimpleName, PrettyDuration.format(tableAdvertisementInterval, includeNanos = false, 1))
        } catch {
          case ex: IllegalStateException ⇒
            // this is usually harmless
            log.debug("Unable to schedule {} advertisement, " +
              "likely system is shutting down. Reason: {}", getClass.getName, ex.getMessage)
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
  private[remote] def runNextTableAdvertisement() = {
    val current = state.get
    current.advertisementInProgress match {
      case None ⇒
        inboundContext.association(originUid) match {
          case OptionVal.Some(association) ⇒
            val table = prepareCompressionAdvertisement(current.nextTable.version)
            // TODO expensive, check if building the other way wouldn't be faster?
            val nextState = current.copy(nextTable = table.invert, advertisementInProgress = Some(table))
            if (state.compareAndSet(current, nextState))
              advertiseCompressionTable(association, table)

          case OptionVal.None ⇒
            // otherwise it's too early, association not ready yet.
            // so we don't build the table since we would not be able to send it anyway.
            log.warning("No Association for originUid [{}] yet, unable to advertise compression table.", originUid)
        }

      case Some(inProgress) ⇒
        // The ActorRefCompressionAdvertisement message is resent because it can be lost
        log.debug("Advertisment in progress for version {}, resending", inProgress.version)
        inboundContext.association(originUid) match {
          case OptionVal.Some(association) ⇒
            advertiseCompressionTable(association, inProgress) // resend
          case OptionVal.None ⇒
        }
    }
  }

  /**
   * Must be implementeed by extending classes in order to send a [[akka.remote.artery.ControlMessage]]
   * of apropriate type to the remote system in order to advertise the compression table to it.
   */
  protected def advertiseCompressionTable(association: OutboundContext, table: CompressionTable[T]): Unit

  private def prepareCompressionAdvertisement(nextTableVersion: Int): CompressionTable[T] = {
    // TODO surely we can do better than that, optimise
    CompressionTable(nextTableVersion, Map(heavyHitters.snapshot.filterNot(_ == null).zipWithIndex: _*))
  }

  override def toString =
    s"""${getClass.getSimpleName}(countMinSketch: $cms, heavyHitters: $heavyHitters)"""

}

final class UnknownCompressedIdException(id: Long)
  extends RuntimeException(
    s"Attempted de-compress unknown id [$id]! " +
      s"This could happen if this node has started a new ActorSystem bound to the same address as previously, " +
      s"and previous messages from a remote system were still in flight (using an old compression table). " +
      s"The remote system is expected to drop the compression table and this system will advertise a new one.")
