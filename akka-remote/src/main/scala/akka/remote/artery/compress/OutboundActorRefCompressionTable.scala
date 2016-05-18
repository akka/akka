/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.actor.{ Address, ActorRef, ActorSystem }
import akka.event.Logging

import scala.annotation.tailrec

final class OutboundActorRefCompressionTable(system: ActorSystem, remoteAddress: Address)
  extends OutboundCompressionTable[ActorRef](system, remoteAddress) {

  preAllocate(
    system.deadLetters → 0
  )

  //  if (system.toString.contains("systemB"))
  //    system.log.error(new Throwable, "new OutboundActorRefCompressionTable   = " + this.hashCode())

  def preAllocate(allocations: (ActorRef, Int)*): Unit =
    allocations foreach { case (ref, id) ⇒ register(ref, id) }
}

/**
 * Base class for all outgoing compression.
 * Encapsulates the compressedId registration and lookup.
 *
 * Not thread safe.
 */
class OutboundCompressionTable[T](system: ActorSystem, remoteAddress: Address) {
  import OutboundCompressionTable._

  private val settings = CompressionSettings(system)

  private val log = system.log

  // TODO can we specialize this? (tuning due here)
  @volatile private[this] var backing = Map.empty[T, Int] // TODO could use unsafe to swap the map instead of volatile

  // mapping guarding
  private[this] var compressionIdsAllocated = -1
  private[this] var aheadAllocatedCompressionIds = Set.empty[Int]

  def register(value: T, id: Int): Unit = {
    backing.get(value) match {
      case None if isNextCompressionId(id) ⇒
        log.debug("Outbound: Registering new compression from [{}] to [{}].", value, id) // TODO should be debug
        addFastForwardCompressionIdsAllocatedCounter()
        backing = backing.updated(value, id)

        if (settings.debug) log.debug("Outgoing: Updated compression table state: \n{}", toDebugString) // TODO debug  

      case None ⇒
        // TODO could be wrong? since we can not guarantee alocations come in sequence?
        if (compressionIdAlreadyAllocated(id))
          throw new AllocatedSameIdMultipleTimesException(id, backing.find(_._2 == id).get._1, value)

        aheadAllocatedCompressionIds += id
        backing = backing.updated(value, id)

      case Some(existingId) ⇒
        throw new ConflictingCompressionException(value, id, existingId)
    }
  }

  def compressionIdAlreadyAllocated(id: Int): Boolean =
    id <= compressionIdsAllocated || aheadAllocatedCompressionIds.contains(id)

  def compress(value: T): Int = {
    backing.get(value) match { // TODO possibly optimise avoid the Option? Depends on used Map
      case None     ⇒ NotCompressedId
      case Some(id) ⇒ id
    }
  }

  private def isNextCompressionId(id: Int): Boolean =
    id == compressionIdsAllocated + 1

  private def addFastForwardCompressionIdsAllocatedCounter(): Unit = {
    @tailrec def fastForwardConsume(): Unit = {
      val nextId = compressionIdsAllocated + 1
      if (aheadAllocatedCompressionIds.contains(nextId)) {
        aheadAllocatedCompressionIds = aheadAllocatedCompressionIds.filterNot(_ == nextId)
        compressionIdsAllocated += 1
        fastForwardConsume()
      } else ()
    }

    compressionIdsAllocated += 1
    fastForwardConsume()
  }

  def toDebugString: String = {
    val pad = backing.keys.iterator.map(_.toString.length).max
    s"""${Logging.simpleName(getClass)}(
     |  hashCode: ${this.hashCode()} to [$remoteAddress]
     |  compressionIdsAllocated: ${compressionIdsAllocated + 1},
     |  aheadAllocatedCompressionIds: $aheadAllocatedCompressionIds)
     |    
     |  ${backing.map { case (k, v) ⇒ k.toString.padTo(pad, " ").mkString("") + " => " + v }.mkString("\n  ")}
     |)""".stripMargin
  }

  override def toString =
    s"""${Logging.simpleName(getClass)}(compressionIdsAllocated: ${compressionIdsAllocated + 1}, aheadAllocatedCompressionIds: $aheadAllocatedCompressionIds)"""
}
object OutboundCompressionTable {
  // format: OFF
  final val DeadLettersId   = 0
  final val NotCompressedId = -1
  // format: ON
}

final class ConflictingCompressionException(value: Any, id: Int, existingId: Int)
  extends IllegalStateException(
    s"Value [$value] was already given a compression id [$id], " +
      s"yet new compressionId for it was given: $existingId. This could lead to inconsistencies!")

final class AllocatedSameIdMultipleTimesException(id: Int, previousValue: Any, conflictingValue: Any)
  extends IllegalStateException(
    s"Attempted to allocate compression id [$id] second time, " +
      s"was already bound to value [$previousValue], " +
      s"tried to bind to [$conflictingValue]!")
