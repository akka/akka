/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import java.util.concurrent.ConcurrentHashMap

import akka.actor._

import scala.annotation.switch

class OutboundCompressionTable(system: ActorSystem) {
  import OutboundCompressionTable._

  // TODO make it Long-specialized for perf
  private[this] val backing = new ConcurrentHashMap[ActorRef, Integer]()

  def register(ref: ActorRef, id: Int): Unit = {
    val previousValue = backing.putIfAbsent(ref, id)
    // TODO handle if second binding to same value?
  }

  def compress(ref: ActorRef): CompressedActorRef = {
    if (ref == system.deadLetters) {
      // short-circuiting deadLetters compression
      CompressedDeadLetters
    } else {
      val n: Int = backing.getOrDefault(ref, NotCompressedId)
      (n: @switch) match {
        case NotCompressedId ⇒ NotCompressed
        case id              ⇒ CompressedActorRef(id)
      }
    }
  }

}
object OutboundCompressionTable {
  // format: OFF
  final val NotCompressedId       = -1
  
  final val NotCompressed         = CompressedActorRef(NotCompressedId)
  final val CompressedDeadLetters = CompressedActorRef(0)
  // format: ON
}

final case class CompressedActorRef(id: Long) extends AnyVal
