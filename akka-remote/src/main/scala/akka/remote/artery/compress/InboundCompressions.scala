/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import java.util.concurrent.atomic.AtomicReference
import java.util.function.{ Consumer, LongFunction }

import akka.actor.{ ActorRef, ActorSystem, Address }
import akka.event.{ Logging, LoggingAdapter }
import akka.remote.artery._
import akka.util.OptionVal
import org.agrona.collections.Long2ObjectHashMap

import scala.annotation.tailrec
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * INTERNAL API
 * Decompress and cause compression advertisements.
 *
 * One per inbound message stream thus must demux by originUid to use the right tables.
 */
private[remote] trait InboundCompressions {
  def hitActorRef(originUid: Long, remote: Address, ref: ActorRef, n: Int): Unit
  def decompressActorRef(originUid: Long, tableVersion: Byte, idx: Int): OptionVal[ActorRef]
  def confirmActorRefCompressionAdvertisement(originUid: Long, tableVersion: Byte): Unit
  /** Triggers compression advertisement via control message. */
  def runNextActorRefAdvertisement(): Unit

  def hitClassManifest(originUid: Long, remote: Address, manifest: String, n: Int): Unit
  def decompressClassManifest(originUid: Long, tableVersion: Byte, idx: Int): OptionVal[String]
  def confirmClassManifestCompressionAdvertisement(originUid: Long, tableVersion: Byte): Unit
  /** Triggers compression advertisement via control message. */
  def runNextClassManifestAdvertisement(): Unit

  /**
   * Remove compression and cancel advertisement scheduling for a specific origin
   */
  def close(originUid: Long): Unit

}

/**
 * INTERNAL API
 *
 * One per incoming Aeron stream, actual compression tables are kept per-originUid and created on demand.
 */
private[remote] final class InboundCompressionsImpl(
  system:         ActorSystem,
  inboundContext: InboundContext,
  settings:       ArterySettings.Compression,
  eventSink:      EventSink                  = IgnoreEventSink) extends InboundCompressions {

  // None is used as tombstone value after closed
  // TODO would be nice if we can cleanup the tombstones
  // FIXME we should be able to remove the tombstones easily now
  private[this] val _actorRefsIns = new Long2ObjectHashMap[Option[InboundActorRefCompression]]()
  private[this] val _inboundActorRefsLog = Logging(system, classOf[InboundManifestCompression])
  private val createInboundActorRefsForOrigin = new LongFunction[Option[InboundActorRefCompression]] {
    override def apply(originUid: Long): Option[InboundActorRefCompression] = {
      val actorRefHitters = new TopHeavyHitters[ActorRef](settings.ActorRefs.Max)
      Some(new InboundActorRefCompression(_inboundActorRefsLog, settings, originUid, inboundContext, actorRefHitters))
    }
  }
  private def actorRefsIn(originUid: Long): Option[InboundActorRefCompression] =
    _actorRefsIns.computeIfAbsent(originUid, createInboundActorRefsForOrigin)

  // None is used as tombstone value after closed
  private[this] val _classManifestsIns = new Long2ObjectHashMap[Option[InboundManifestCompression]]()

  private[this] val _inboundManifestLog = Logging(system, classOf[InboundManifestCompression])
  private val createInboundManifestsForOrigin = new LongFunction[Option[InboundManifestCompression]] {
    override def apply(originUid: Long): Option[InboundManifestCompression] = {
      val manifestHitters = new TopHeavyHitters[String](settings.Manifests.Max)
      Some(new InboundManifestCompression(_inboundManifestLog, settings, originUid, inboundContext, manifestHitters))
    }
  }
  private def classManifestsIn(originUid: Long): Option[InboundManifestCompression] =
    _classManifestsIns.computeIfAbsent(originUid, createInboundManifestsForOrigin)

  // actor ref compression ---

  override def decompressActorRef(originUid: Long, tableVersion: Byte, idx: Int): OptionVal[ActorRef] =
    actorRefsIn(originUid) match {
      case Some(a) ⇒ a.decompress(tableVersion, idx)
      case None    ⇒ OptionVal.None
    }
  override def hitActorRef(originUid: Long, address: Address, ref: ActorRef, n: Int): Unit = {
    if (ArterySettings.Compression.Debug) println(s"[compress] hitActorRef($originUid, $address, $ref, $n)")
    actorRefsIn(originUid) match {
      case Some(a) ⇒ a.increment(address, ref, n)
      case None    ⇒ // closed
    }
  }
  override def confirmActorRefCompressionAdvertisement(originUid: Long, tableVersion: Byte): Unit = {
    _actorRefsIns.get(originUid) match {
      case null    ⇒ // ignore
      case Some(a) ⇒ a.confirmAdvertisement(tableVersion)
      case None    ⇒ // closed
    }
  }
  /** Send compression table advertisement over control stream. Should be called from Decoder. */
  override def runNextActorRefAdvertisement(): Unit = {
    val vs = _actorRefsIns.values.iterator()
    while (vs.hasNext) vs.next() match {
      case Some(inbound) ⇒
        eventSink.hiFreq(FlightRecorderEvents.Compression_Inbound_RunActorRefAdvertisement, 1)
        inbound.runNextTableAdvertisement()
      case None ⇒ // do nothing...
    }
  }

  // class manifest compression ---

  override def decompressClassManifest(originUid: Long, tableVersion: Byte, idx: Int): OptionVal[String] =
    classManifestsIn(originUid) match {
      case Some(a) ⇒ a.decompress(tableVersion, idx)
      case None    ⇒ OptionVal.None
    }

  override def hitClassManifest(originUid: Long, address: Address, manifest: String, n: Int): Unit = {
    if (ArterySettings.Compression.Debug) println(s"[compress] hitClassManifest($originUid, $address, $manifest, $n)")
    classManifestsIn(originUid) match {
      case Some(a) ⇒ a.increment(address, manifest, n)
      case None    ⇒ // closed
    }
  }
  override def confirmClassManifestCompressionAdvertisement(originUid: Long, tableVersion: Byte): Unit = {
    _classManifestsIns.get(originUid) match {
      case null    ⇒ // ignore
      case Some(a) ⇒ a.confirmAdvertisement(tableVersion)
      case None    ⇒ // closed
    }
  }
  /** Send compression table advertisement over control stream. Should be called from Decoder. */
  override def runNextClassManifestAdvertisement(): Unit = {
    val vs = _classManifestsIns.values.iterator()
    while (vs.hasNext) vs.next() match {
      case Some(inbound) ⇒
        eventSink.hiFreq(FlightRecorderEvents.Compression_Inbound_RunClassManifestAdvertisement, 1)
        inbound.runNextTableAdvertisement()
      case None ⇒ // do nothing...
    }
  }

  override def close(originUid: Long): Unit = {
    _actorRefsIns.putIfAbsent(originUid, None)
    _classManifestsIns.putIfAbsent(originUid, None)
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
  log:            LoggingAdapter,
  settings:       ArterySettings.Compression,
  originUid:      Long,
  inboundContext: InboundContext,
  heavyHitters:   TopHeavyHitters[ActorRef])
  extends InboundCompression[ActorRef](log, settings, originUid, inboundContext, heavyHitters) {

  override def decompress(tableVersion: Byte, idx: Int): OptionVal[ActorRef] =
    super.decompressInternal(tableVersion, idx, 0)

  override def advertiseCompressionTable(outboundContext: OutboundContext, table: CompressionTable[ActorRef]): Unit = {
    log.debug(s"Advertise {} compression [{}] to [{}#{}]", Logging.simpleName(getClass), table, outboundContext.remoteAddress, originUid)
    outboundContext.sendControl(CompressionProtocol.ActorRefCompressionAdvertisement(inboundContext.localAddress, table))
  }
}

/**
 * INTERNAL API
 */
private[remote] final class InboundManifestCompression(
  log:            LoggingAdapter,
  settings:       ArterySettings.Compression,
  originUid:      Long,
  inboundContext: InboundContext,
  heavyHitters:   TopHeavyHitters[String])
  extends InboundCompression[String](log, settings, originUid, inboundContext, heavyHitters) {

  override def advertiseCompressionTable(outboundContext: OutboundContext, table: CompressionTable[String]): Unit = {
    log.debug(s"Advertise {} compression [{}] to [{}#{}]", Logging.simpleName(getClass), table, outboundContext.remoteAddress, originUid)
    outboundContext.sendControl(CompressionProtocol.ClassManifestCompressionAdvertisement(inboundContext.localAddress, table))
  }

  override def increment(remoteAddress: Address, value: String, n: Long): Unit =
    if (value != "") super.increment(remoteAddress, value, n)

  override def decompress(incomingTableVersion: Byte, idx: Int): OptionVal[String] =
    decompressInternal(incomingTableVersion, idx, 0)
}
/**
 * INTERNAL API
 */
private[remote] object InboundCompression {

  object State {
    def empty[T] = State(
      oldTable = DecompressionTable.disabled[T],
      activeTable = DecompressionTable.empty[T],
      nextTable = DecompressionTable.empty[T].copy(version = 1),
      advertisementInProgress = None)
  }

  final case class State[T](
    oldTable:                DecompressionTable[T],
    activeTable:             DecompressionTable[T],
    nextTable:               DecompressionTable[T],
    advertisementInProgress: Option[CompressionTable[T]]) {

    def startUsingNextTable(): State[T] = {
      def incrementTableVersion(version: Byte): Byte =
        if (version == 127) 0
        else (version + 1).toByte

      State(
        oldTable = activeTable,
        activeTable = nextTable,
        nextTable = DecompressionTable.empty[T].copy(version = incrementTableVersion(nextTable.version)),
        advertisementInProgress = None)
    }
  }

}

/**
 * INTERNAL API
 * Handles counting and detecting of heavy-hitters and compressing them via a table lookup.
 *
 * Access to this class must be externally synchronised (e.g. by accessing it from only Actors or a GraphStage etc).
 */
private[remote] abstract class InboundCompression[T >: Null](
  val log:          LoggingAdapter,
  val settings:     ArterySettings.Compression,
  originUid:        Long,
  inboundContext:   InboundContext,
  val heavyHitters: TopHeavyHitters[T]) {

  private[this] var state: InboundCompression.State[T] = InboundCompression.State.empty

  // We should not continue sending advertisements to an association that might be dead (not quarantined yet)
  @volatile private[this] var alive = true
  private[this] var resendCount = 0

  private[this] val cms = new CountMinSketch(16, 1024, System.currentTimeMillis().toInt)

  log.debug("Initializing {} for originUid [{}]", Logging.simpleName(getClass), originUid)

  /* ==== COMPRESSION ==== */

  /** Override and specialize if needed, for default compression logic delegate to 3-param overload */
  def decompress(incomingTableVersion: Byte, idx: Int): OptionVal[T]

  /**
   * Decompress given identifier into its original representation.
   * Passed in tableIds must only ever be in not-decreasing order (as old tables are dropped),
   * tableIds must not have gaps. If an "old" tableId is received the value will fail to be decompressed.
   *
   * @throws UnknownCompressedIdException if given id is not known, this may indicate a bug – such situation should not happen.
   */
  @tailrec final def decompressInternal(incomingTableVersion: Byte, idx: Int, attemptCounter: Int): OptionVal[T] = {
    // effectively should never loop more than once, to avoid infinite recursion blow up eagerly
    if (attemptCounter > 2) throw new IllegalStateException(s"Unable to decompress $idx from table $incomingTableVersion. Internal state: ${state}")

    val current = state
    val oldVersion = current.oldTable.version
    val activeVersion = current.activeTable.version

    if (incomingTableVersion == DecompressionTable.DisabledVersion) OptionVal.None // no compression, bail out early
    else if (incomingTableVersion == activeVersion) {
      val value: T = current.activeTable.get(idx)
      if (value != null) OptionVal.Some[T](value)
      else throw new UnknownCompressedIdException(idx)
    } else if (incomingTableVersion == oldVersion) {
      // must handle one old table due to messages in flight during advertisement
      val value: T = current.oldTable.get(idx)
      if (value != null) OptionVal.Some[T](value)
      else throw new UnknownCompressedIdException(idx)
    } else if (current.advertisementInProgress.isDefined && incomingTableVersion == current.advertisementInProgress.get.version) {
      log.debug(
        "Received first value from originUid [{}] compressed using the advertised compression table, flipping to it (version: {})",
        originUid, current.nextTable.version)
      confirmAdvertisement(incomingTableVersion)
      decompressInternal(incomingTableVersion, idx, attemptCounter + 1) // recurse
    } else {
      // which means that incoming version was > nextTable.version, which likely that
      // it is using a table that was built for previous incarnation of this system
      log.warning(
        "Inbound message from originUid [{}] is using unknown compression table version. " +
          "It may have been sent with compression table built for previous incarnation of this system. " +
          "Versions activeTable: {}, nextTable: {}, incomingTable: {}",
        originUid, activeVersion, current.nextTable.version, incomingTableVersion)
      OptionVal.None
    }
  }

  final def confirmAdvertisement(tableVersion: Byte): Unit = {
    state.advertisementInProgress match {
      case Some(inProgress) if tableVersion == inProgress.version ⇒
        state = state.startUsingNextTable()
        log.debug("Confirmed compression table version [{}] for originUid [{}]", tableVersion, originUid)
      case Some(inProgress) if tableVersion != inProgress.version ⇒
        log.debug(
          "Confirmed compression table version [{}] for originUid [{}] but other version in progress [{}]",
          tableVersion, originUid, inProgress.version)
      case None ⇒
      // already confirmed
    }

  }

  /**
   * Add `n` occurrence for the given key and call `heavyHittedDetected` if element has become a heavy hitter.
   * Empty keys are omitted.
   */
  def increment(remoteAddress: Address, value: T, n: Long): Unit = {
    val count = cms.addObjectAndEstimateCount(value, n)
    addAndCheckIfheavyHitterDetected(value, count)
    alive = true
  }

  /** Mutates heavy hitters */
  private def addAndCheckIfheavyHitterDetected(value: T, count: Long): Boolean = {
    heavyHitters.update(value, count)
  }

  /* ==== TABLE ADVERTISEMENT ==== */

  /**
   * Entry point to advertising a new compression table.
   *
   * [1] First we must *hand the new table over to the Incoming compression side on this system*,
   * so it will not be used by someone else before "we" know about it in the Decoder.
   * [2] Then the table must be *advertised to the remote system*, and MAY start using it immediately
   *
   * It must be advertised to the other side so it can start using it in its outgoing compression.
   * Triggers compression table advertisement. May be triggered by schedule or manually, i.e. for testing.
   */
  private[remote] def runNextTableAdvertisement(): Unit = {
    if (ArterySettings.Compression.Debug) println(s"[compress] runNextTableAdvertisement, state = ${state}")
    state.advertisementInProgress match {
      case None ⇒
        inboundContext.association(originUid) match {
          case OptionVal.Some(association) ⇒
            if (alive) {
              val table = prepareCompressionAdvertisement(state.nextTable.version)
              // TODO expensive, check if building the other way wouldn't be faster?
              val nextState = state.copy(nextTable = table.invert, advertisementInProgress = Some(table))
              state = nextState
              alive = false // will be set to true on first incoming message
              resendCount = 0
              advertiseCompressionTable(association, table)
            } else
              log.debug("Inbound compression table for originUid [{}] not changed, no need to advertise same.", originUid)

          case OptionVal.None ⇒
            // otherwise it's too early, association not ready yet.
            // so we don't build the table since we would not be able to send it anyway.
            log.debug("No Association for originUid [{}] yet, unable to advertise compression table.", originUid)
        }

      case Some(inProgress) ⇒
        resendCount += 1
        if (resendCount <= 5) {
          // The ActorRefCompressionAdvertisement message is resent because it can be lost
          log.debug(
            "Advertisment in progress for originUid [{}] version {}, resending",
            originUid, inProgress.version)
          inboundContext.association(originUid) match {
            case OptionVal.Some(association) ⇒
              advertiseCompressionTable(association, inProgress) // resend
            case OptionVal.None ⇒
          }
        } else {
          // give up, it might be dead
          log.debug(
            "Advertisment in progress for originUid [{}] version {} but no confirmation after retries.",
            originUid, inProgress.version)
          confirmAdvertisement(inProgress.version)
        }
    }
  }

  /**
   * Must be implemented by extending classes in order to send a `ControlMessage`
   * of appropriate type to the remote system in order to advertise the compression table to it.
   */
  protected def advertiseCompressionTable(association: OutboundContext, table: CompressionTable[T]): Unit

  private def prepareCompressionAdvertisement(nextTableVersion: Byte): CompressionTable[T] = {
    // TODO optimised somewhat, check if still to heavy; could be encoded into simple array
    val mappings: Map[T, Int] = {
      val mb = Map.newBuilder[T, Int]
      mb ++= heavyHitters.iterator.zipWithIndex
      mb.result()
    }
    CompressionTable(originUid, nextTableVersion, mappings)
  }

  override def toString =
    s"""${getClass.getSimpleName}(countMinSketch: $cms, heavyHitters: $heavyHitters)"""

}

/**
 * INTERNAL API
 */
private[akka] final class UnknownCompressedIdException(id: Long)
  extends RuntimeException(
    s"Attempted de-compress unknown id [$id]! " +
      s"This could happen if this node has started a new ActorSystem bound to the same address as previously, " +
      s"and previous messages from a remote system were still in flight (using an old compression table). " +
      s"The remote system is expected to drop the compression table and this system will advertise a new one.")

/**
 * INTERNAL API
 *
 * Literarily, no compression!
 */
private[remote] case object NoInboundCompressions extends InboundCompressions {
  override def hitActorRef(originUid: Long, remote: Address, ref: ActorRef, n: Int): Unit = ()
  override def decompressActorRef(originUid: Long, tableVersion: Byte, idx: Int): OptionVal[ActorRef] =
    if (idx == -1) throw new IllegalArgumentException("Attemted decompression of illegal compression id: -1")
    else OptionVal.None
  override def confirmActorRefCompressionAdvertisement(originUid: Long, tableVersion: Byte): Unit = ()
  override def runNextActorRefAdvertisement(): Unit = ()

  override def hitClassManifest(originUid: Long, remote: Address, manifest: String, n: Int): Unit = ()
  override def decompressClassManifest(originUid: Long, tableVersion: Byte, idx: Int): OptionVal[String] =
    if (idx == -1) throw new IllegalArgumentException("Attemted decompression of illegal compression id: -1")
    else OptionVal.None
  override def confirmClassManifestCompressionAdvertisement(originUid: Long, tableVersion: Byte): Unit = ()
  override def runNextClassManifestAdvertisement(): Unit = ()

  override def close(originUid: Long): Unit = ()
}
