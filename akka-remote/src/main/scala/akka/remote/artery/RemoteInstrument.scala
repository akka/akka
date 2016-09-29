/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery

import akka.actor.{ ActorRef, ExtendedActorSystem }
import akka.util.{ ByteString, OptionVal }

/**
 * INTERNAL API
 *
 * Part of the monitoring SPI which allows attaching metadata to outbound remote messages,
 * and reading in metadata from incoming messages.
 *
 * Multiple instruments are automatically handled, however they MUST NOT overlap in their idenfitiers.
 *
 * Instances of `RemoteInstrument` are created from configuration. A new instance of RemoteInstrument
 * will be created for each encoder and decoder. It's only called from the stage, so if it dosn't
 * delegate to any shared instance it doesn't have to be thread-safe.
 */
abstract class RemoteInstrument {
  /**
   * Instrument identifier.
   *
   * MUST be >=0 and <32.
   *
   * Values between 0 and 7 are reserved for Akka internal use.
   */
  def identifier: Byte

  /**
   * Called right before putting the message onto the wire.
   * Parameters MAY be `null` (except `message`)!
   *
   * @return `metadata` rendered to be serialized into the remove envelope, or `null` if no metadata should be attached
   */
  def remoteMessageSent(recipient: ActorRef, message: Object, sender: ActorRef): ByteString

  /**
   * Called once a message (containing a metadata field designated for this instrument) has been deserialized from the wire.
   */
  def remoteMessageReceived(recipient: ActorRef, message: Object, sender: ActorRef, metadata: ByteString): Unit

}

object NoopRemoteInstrument extends RemoteInstrument {
  override def identifier: Byte =
    -1

  override def remoteMessageSent(recipient: ActorRef, message: Object, sender: ActorRef): ByteString =
    null

  override def remoteMessageReceived(recipient: ActorRef, message: Object, sender: ActorRef, metadata: ByteString): Unit =
    ()
}

/** INTERNAL API */
private[remote] object RemoteInstruments {
  def create(system: ExtendedActorSystem): Vector[RemoteInstrument] = {
    val c = system.settings.config
    val path = "akka.remote.artery.advanced.instruments"
    import scala.collection.JavaConverters._
    c.getStringList(path).asScala.map { fqcn ⇒
      system
        .dynamicAccess.createInstanceFor[RemoteInstrument](fqcn, Nil)
        .orElse(system.dynamicAccess.createInstanceFor[RemoteInstrument](fqcn, List(classOf[ExtendedActorSystem] → system)))
        .get
    }(collection.breakOut)
  }
}

/**
 * INTERNAL API
 *
 * This datastructure is specialized for addressing directly into a known IDs slot.
 * It is used when deserializing/serializing metadata and we know the ID we want to reach into.
 *
 * Mutable & NOT thread-safe.
 *
 * Fixed-size: 32-slots array-backed Map-like structure.
 * Lazy: The backing array is allocated lazily only when needed, thus we pay no cost for the metadata array if
 * the system is not using metadata.
 * Life-cycle: Owned and linked to the lifecycle of an [[OutboundEnvelope]].
 * Re-cycled:  Aimed to be re-cycled to produce the least possible GC-churn, by calling `clear()` when done with it.
 *
 * Reserved keys: The keys 0–7 are reserved for Akka internal purposes and future extensions.
 */
private[remote] final class MetadataMap[T >: Null] {
  val capacity = 32

  protected var backing: Array[T] = null // TODO re-think if a plain LinkedList wouldn't be fine here?

  private var _usedSlots = 0

  def usedSlots = _usedSlots

  def apply(i: Int): OptionVal[T] =
    if (backing == null) OptionVal.None
    else OptionVal[T](backing(i))

  def isEmpty = usedSlots == 0

  def nonEmpty = !isEmpty

  def hasValueFor(i: Int) = nonEmpty && backing(i) != null

  // FIXME too specialized...
  def foldLeftValues[A](zero: A)(f: (A, T) ⇒ A): A = {
    var acc: A = zero
    var hit = 0
    var i = 0
    while (i < capacity && hit < _usedSlots) {
      val it = backing(i)
      if (it != null) {
        acc = f(acc, it)
        hit += 1
      }
      i += 1
    }
    acc
  }

  /** Heavy operation, only used for error logging */
  def keysWithValues: List[Int] = backing.zipWithIndex.filter({ case (t, id) ⇒ t != null }).map(_._2).toList

  def foreach(f: (Byte, T) ⇒ Unit) = {
    var i = 0
    var hit = 0
    while (i < capacity && hit < _usedSlots) {
      val t = backing(i)
      if (t != null) {
        f(i.toByte, t)
        hit += 1
      }
      i += 1
    }
  }

  private def allocateIfNeeded(): Unit =
    if (backing == null) backing = Array.ofDim[Object](capacity).asInstanceOf[Array[T]]

  /**
   * Set a value at given index.
   * Setting a null value removes the entry (the slot is marked as not used).
   */
  def set(i: Int, t: T): Unit =
    if (t == null) {
      if (backing == null) ()
      else {
        if (backing(i) != null) _usedSlots -= 1 // we're clearing a spot
        backing(i) = null.asInstanceOf[T]
      }
    } else {
      allocateIfNeeded()

      if (backing(i) == null) {
        // was empty previously
        _usedSlots += 1
      } else {
        // replacing previous value, usedSlots remains unchanged
      }

      backing(i) = t
    }

  /**
   * If the backing array was already allocated clears it, if it wasn't does nothing (no-op).
   * This is so in order to pay no-cost when not using metadata - clearing then is instant.
   */
  def clear() =
    if (isEmpty) ()
    else {
      var i = 0
      while (i < capacity) {
        backing(i) = null.asInstanceOf[T]
        i += 1
      }
      _usedSlots = 0
    }

  override def toString() =
    if (backing == null) s"MetadataMap(<empty>)"
    else s"MetadataMap(${backing.toList.mkString("[", ",", "]")})"
}

/**
 * INTERNAL API
 */
private[remote] object MetadataMap {
  def apply[T >: Null]() = new MetadataMap[T]
}
