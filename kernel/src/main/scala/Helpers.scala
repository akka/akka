/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.kernel

import java.util.concurrent.locks.ReentrantReadWriteLock

import scala.actors._
import scala.actors.Actor._

import net.lag.logging.Logger

class SystemFailure(cause: Throwable) extends RuntimeException(cause)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Helpers extends Logging {

 // ================================================
  class ReadWriteLock {
    private val rwl = new ReentrantReadWriteLock
    private val readLock = rwl.readLock
    private val writeLock = rwl.writeLock

    def withWriteLock[T](body: => T): T = {
      writeLock.lock
      try {
        body
      } finally {
        writeLock.unlock
      }
    }

    def withReadLock[T](body: => T): T = {
      readLock.lock
      try {
        body
      } finally {
        readLock.unlock
      }
    }
  }

  // ================================================
  // implicit conversion between regular actor and actor with a type future
  implicit def actorWithFuture(a: Actor) = new ActorWithTypedFuture(a)

  abstract class FutureWithTimeout[T](ch: InputChannel[Any]) extends Future[T](ch) {
    def receiveWithin(timeout: Int) : Option[T]
    override def respond(f: T => Unit): Unit = throw new UnsupportedOperationException("Does not support the Responder API") 
  }

  def receiveOrFail[T](future: => FutureWithTimeout[T], timeout: Int, errorHandler: => T): T = {
    future.receiveWithin(timeout) match {
      case None => errorHandler
      case Some(reply) => reply
    }
  }

  class ActorWithTypedFuture(a: Actor) {
    require(a != null)

    def !!![A](msg: Any): FutureWithTimeout[A] = {
      val ftch = new Channel[Any](Actor.self)
      a.send(msg, ftch)
      new FutureWithTimeout[A](ftch) {
        def apply() =
          if (isSet) value.get.asInstanceOf[A]
          else ch.receive {
            case a =>
              value = Some(a)
              value.get.asInstanceOf[A]
          }
        def isSet = receiveWithin(0).isDefined
        def receiveWithin(timeout: Int): Option[A] = value match {
          case None => ch.receiveWithin(timeout) {
            case TIMEOUT =>
              log.debug("Future timed out while waiting for actor: {}", a)
              None
            case a =>
              value = Some(a)
              value.asInstanceOf[Option[A]]
          }
          case a => a.asInstanceOf[Option[A]]
        }
      }
    }
  }
}

