/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.trace

import akka.actor.{ ActorSystem, ActorRef, ExtendedActorSystem }
import akka.util.{ ByteIterator, ByteString }
import scala.annotation.tailrec
import scala.collection.immutable

/**
 * Implementation of Tracer that delegates to multiple tracers.
 * A MultiTracer is only created when there are two or more tracers attached.
 * Trace contexts are stored as a sequence, and aligned with tracers when retrieved.
 * Efficient implementation using an array and manually inlined loops.
 */
private[akka] class MultiTracer(val tracers: immutable.Seq[Tracer]) extends Tracer {
  implicit private val byteOrder = java.nio.ByteOrder.BIG_ENDIAN

  // DO NOT MODIFY THIS ARRAY
  private[this] val _tracers: Array[Tracer] = tracers.toArray

  private[this] val length: Int = _tracers.length

  final def systemStarted(system: ActorSystem): Unit = {
    var i = 0
    while (i < length) {
      _tracers(i).systemStarted(system)
      i += 1
    }
  }

  final def systemShutdown(system: ActorSystem): Unit = {
    var i = 0
    while (i < length) {
      _tracers(i).systemShutdown(system)
      i += 1
    }
  }

  final def actorTold(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      _tracers(i).actorTold(actorRef, message, sender)
      i += 1
    }
  }

  final def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      _tracers(i).actorReceived(actorRef, message, sender)
      i += 1
    }
  }

  final def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      _tracers(i).actorCompleted(actorRef, message, sender)
      i += 1
    }
  }

  final def remoteMessageSent(actorRef: ActorRef, message: Any, size: Int, sender: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      _tracers(i).remoteMessageSent(actorRef, message, size, sender)
      i += 1
    }
  }

  final def remoteMessageReceived(actorRef: ActorRef, message: Any, size: Int, sender: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      _tracers(i).remoteMessageReceived(actorRef, message, size, sender)
      i += 1
    }
  }

  final def getContext(): Any = {
    val array = new Array[Any](length)
    var i = 0
    while (i < length) {
      array(i) = _tracers(i).getContext()
      i += 1
    }
    new MultiContext(array)
  }

  final def setContext(context: Any): Unit = {
    // assume we have a correct MultiContext from getContext
    val contexts = context.asInstanceOf[MultiContext]
    var i = 0
    while (i < length) {
      _tracers(i).setContext(contexts(i))
      i += 1
    }
  }

  final def clearContext(): Unit = {
    var i = 0
    while (i < length) {
      _tracers(i).clearContext()
      i += 1
    }
  }

  final def identifier: Int = 0

  // not called: serialization by TracedMessageSerializer uses serializeContexts below for identifier mapping
  final def serializeContext(system: ExtendedActorSystem, context: Any): Array[Byte] = Array.empty[Byte]

  final def serializeContexts(system: ExtendedActorSystem, context: Any): immutable.Seq[(Int, Array[Byte])] = {
    // assume we have a correct MultiContext from getContext
    val contexts = context.asInstanceOf[MultiContext]
    val builder = immutable.Seq.newBuilder[(Int, Array[Byte])]
    builder.sizeHint(length)
    var i = 0
    while (i < length) {
      val ctx = contexts(i)
      if (ctx != Tracer.emptyContext) {
        val id = _tracers(i).identifier
        val bytes = _tracers(i).serializeContext(system, ctx)
        builder += (id -> bytes)
      }
      i += 1
    }
    builder.result
  }

  // not called: deserialization by TracedMessageSerializer uses deserializeContexts below for identifier mapping
  final def deserializeContext(system: ExtendedActorSystem, context: Array[Byte]): Any = Tracer.emptyContext

  final def deserializeContexts(system: ExtendedActorSystem, contexts: Map[Int, Array[Byte]]): Any = {
    val array = new Array[Any](length)
    var i = 0
    while (i < length) {
      val id = _tracers(i).identifier
      val ctx = if (contexts.isDefinedAt(id)) _tracers(i).deserializeContext(system, contexts(id)) else Tracer.emptyContext
      array(i) = ctx
      i += 1
    }
    new MultiContext(array)
  }
}

/**
 * A simple read-only wrapper for an array of context objects.
 */
private[akka] final class MultiContext(private[this] val contexts: Array[Any]) {

  def apply(i: Int): Any = contexts(i)

  def length: Int = contexts.length

  override def equals(that: Any): Boolean = that match {
    case that: MultiContext ⇒
      (this eq that) || {
        val n = length
        (n == that.length) && {
          var i = 0
          while (i < n && this(i) == that(i)) i += 1
          i == n
        }
      }
    case _ ⇒ false
  }

  override def hashCode: Int = scala.util.hashing.MurmurHash3.orderedHash(contexts, MultiContext.hashSeed)

  override def toString: String = contexts.mkString("MultiContext(", ",", ")")
}

private[akka] object MultiContext {
  val hashSeed = "MultiContext".hashCode
}
