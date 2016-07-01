/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import java.util.concurrent.atomic.AtomicReference
import java.{ util ⇒ ju }

import akka.actor.{ ActorRef, ActorSystem, Address }
import akka.event.Logging
import akka.remote.artery.compress.OutboundCompression.OutboundCompressionState

import scala.annotation.tailrec

/** INTERNAL API */
private[remote] final class OutboundActorRefCompression(system: ActorSystem, remoteAddress: Address)
  extends OutboundCompressionTable[ActorRef](system, remoteAddress) {

  flipTable(CompressionTable(
    version = 0,
    map = Map(
      system.deadLetters → 0
    )
  ))
}

/**
 * INTERNAL API
 * Base class for all outgoing compression.
 * Encapsulates the compressedId registration and lookup.
 */
private[remote] class OutboundCompressionTable[T](system: ActorSystem, remoteAddress: Address)
  extends AtomicReference[OutboundCompressionState[T]](OutboundCompressionState.initial) { // TODO could be instead via Unsafe
  import OutboundCompression._

  // TODO: The compression map may benefit from padding if we want multiple compressions to be running in parallel 

  private[this] val log = Logging(system, "OutboundCompressionTable")

  /**
   * Flips the currently used compression table to the new one (iff the new one has a version number higher than the currently used one).
   */
  // (╯°□°）╯︵ ┻━┻
  @tailrec final def flipTable(activate: CompressionTable[T]): Unit = {
    val state = get()
    if (state.version < activate.version) // TODO or we could demand it to be strictly `currentVersion + 1`
      if (compareAndSet(state, prepareState(activate)))
        log.debug("Successfully flipped compression table to version {}, for ourgoing connection to {}", activate.version, remoteAddress)
      else
        flipTable(activate) // retry
    else if (state.version == activate.version)
      log.warning("Received duplicate compression table (version: {})! Ignoring it.", state.version)
    else
      log.error("Received unexpected compression table with version nr [{}]! " +
        "Current version number is []")

  }

  // TODO this is crazy hot-path; optimised FastUtil-like Object->int hash map would perform better here (and avoid Integer) allocs
  final def compress(value: T): Int =
    get().table.getOrDefault(value, NotCompressedId)

  private final def prepareState(activate: CompressionTable[T]): OutboundCompressionState[T] = {
    val size = activate.map.size
    // load factor is `1` since we will never grow this table beyond the initial size,
    // this way we can avoid any rehashing from happening.
    val m = new ju.HashMap[T, Integer](size, 1.0f) // TODO could be replaced with primitive `int` specialized version
    val it = activate.map.keysIterator
    var i = 0
    while (it.hasNext) {
      m.put(it.next(), i) // TODO boxing :<
      i += 1
    }
    OutboundCompressionState(activate.version, m)
  }

  def toDebugString: String = {
    s"""${Logging.simpleName(getClass)}(
       |  version: ${get.version} to [$remoteAddress]
       |  ${get.table}
       |)""".stripMargin
  }

  override def toString = {
    val s = get
    s"""${Logging.simpleName(getClass)}(to: $remoteAddress, version: ${s.version}, compressedEntries: ${s.table.size})"""
  }

}

/** INTERNAL API */
private[remote] object OutboundCompression {
  // format: OFF
  final val DeadLettersId = 0
  final val NotCompressedId = -1

  // format: ON

  /** INTERNAL API */
  private[remote] final case class OutboundCompressionState[T](version: Long, table: ju.Map[T, Integer])
  private[remote] object OutboundCompressionState {
    def initial[T] = OutboundCompressionState[T](-1, ju.Collections.emptyMap())
  }

}
