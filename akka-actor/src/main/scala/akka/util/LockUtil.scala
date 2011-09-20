/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import java.util.concurrent.locks.{ ReentrantReadWriteLock, ReentrantLock }
import java.util.concurrent.atomic.{ AtomicBoolean }
import akka.event.EventHandler

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final class ReentrantGuard {
  val lock = new ReentrantLock

  final def withGuard[T](body: ⇒ T): T = {
    lock.lock
    try {
      body
    } finally {
      lock.unlock
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ReadWriteGuard {
  private val rwl = new ReentrantReadWriteLock
  val readLock = rwl.readLock
  val writeLock = rwl.writeLock

  def withWriteGuard[T](body: ⇒ T): T = {
    writeLock.lock
    try {
      body
    } finally {
      writeLock.unlock
    }
  }

  def withReadGuard[T](body: ⇒ T): T = {
    readLock.lock
    try {
      body
    } finally {
      readLock.unlock
    }
  }
}

/**
 * A very simple lock that uses CCAS (Compare Compare-And-Swap)
 * Does not keep track of the owner and isn't Reentrant, so don't nest and try to stick to the if*-methods
 */
class SimpleLock(startLocked: Boolean = false) extends AtomicBoolean(startLocked) {
  def ifPossible(perform: () ⇒ Unit): Boolean = {
    if (tryLock()) {
      try {
        perform
      } finally {
        unlock()
      }
      true
    } else false
  }

  def ifPossibleYield[T](perform: () ⇒ T): Option[T] = {
    if (tryLock()) {
      try {
        Some(perform())
      } finally {
        unlock()
      }
    } else None
  }

  def ifPossibleApply[T, R](value: T)(function: (T) ⇒ R): Option[R] = {
    if (tryLock()) {
      try {
        Some(function(value))
      } finally {
        unlock()
      }
    } else None
  }

  def tryLock() = compareAndSet(false, true)

  def tryUnlock() = compareAndSet(true, false)

  def locked = get

  def unlock(): Unit = set(false)
}

/**
 * An atomic switch that can be either on or off
 */
class Switch(startAsOn: Boolean = false) {
  private val switch = new AtomicBoolean(startAsOn)

  protected def transcend(from: Boolean, action: ⇒ Unit): Boolean = synchronized {
    if (switch.compareAndSet(from, !from)) {
      try {
        action
      } catch {
        case e: Throwable ⇒
          EventHandler.error(e, this, e.getMessage)
          switch.compareAndSet(!from, from) // revert status
          throw e
      }
      true
    } else false
  }

  /**
   * Executes the provided action if the lock is on under a lock, so be _very_ careful with longrunning/blocking operations in it
   * Only executes the action if the switch is on, and switches it off immediately after obtaining the lock
   * Will switch it back on if the provided action throws an exception
   */
  def switchOff(action: ⇒ Unit): Boolean = transcend(from = true, action)

  /**
   * Executes the provided action if the lock is off under a lock, so be _very_ careful with longrunning/blocking operations in it
   * Only executes the action if the switch is off, and switches it on immediately after obtaining the lock
   * Will switch it back off if the provided action throws an exception
   */
  def switchOn(action: ⇒ Unit): Boolean = transcend(from = false, action)

  /**
   * Switches the switch off (if on), uses locking
   */
  def switchOff: Boolean = synchronized { switch.compareAndSet(true, false) }

  /**
   * Switches the switch on (if off), uses locking
   */
  def switchOn: Boolean = synchronized { switch.compareAndSet(false, true) }

  /**
   * Executes the provided action and returns its value if the switch is IMMEDIATELY on (i.e. no lock involved)
   */
  def ifOnYield[T](action: ⇒ T): Option[T] = {
    if (switch.get) Some(action)
    else None
  }

  /**
   * Executes the provided action and returns its value if the switch is IMMEDIATELY off (i.e. no lock involved)
   */
  def ifOffYield[T](action: ⇒ T): Option[T] = {
    if (!switch.get) Some(action)
    else None
  }

  /**
   * Executes the provided action and returns if the action was executed or not, if the switch is IMMEDIATELY on (i.e. no lock involved)
   */
  def ifOn(action: ⇒ Unit): Boolean = {
    if (switch.get) {
      action
      true
    } else false
  }

  /**
   * Executes the provided action and returns if the action was executed or not, if the switch is IMMEDIATELY off (i.e. no lock involved)
   */
  def ifOff(action: ⇒ Unit): Boolean = {
    if (!switch.get) {
      action
      true
    } else false
  }

  /**
   * Executes the provided action and returns its value if the switch is on, waiting for any pending changes to happen before (locking)
   * Be careful of longrunning or blocking within the provided action as it can lead to deadlocks or bad performance
   */
  def whileOnYield[T](action: ⇒ T): Option[T] = synchronized {
    if (switch.get) Some(action)
    else None
  }

  /**
   * Executes the provided action and returns its value if the switch is off, waiting for any pending changes to happen before (locking)
   * Be careful of longrunning or blocking within the provided action as it can lead to deadlocks or bad performance
   */
  def whileOffYield[T](action: ⇒ T): Option[T] = synchronized {
    if (!switch.get) Some(action)
    else None
  }

  /**
   * Executes the provided action and returns if the action was executed or not, if the switch is on, waiting for any pending changes to happen before (locking)
   * Be careful of longrunning or blocking within the provided action as it can lead to deadlocks or bad performance
   */
  def whileOn(action: ⇒ Unit): Boolean = synchronized {
    if (switch.get) {
      action
      true
    } else false
  }

  /**
   * Executes the provided action and returns if the action was executed or not, if the switch is off, waiting for any pending changes to happen before (locking)
   * Be careful of longrunning or blocking within the provided action as it can lead to deadlocks or bad performance
   */
  def whileOff(action: ⇒ Unit): Boolean = synchronized {
    if (switch.get) {
      action
      true
    } else false
  }

  /**
   * Executes the provided callbacks depending on if the switch is either on or off waiting for any pending changes to happen before (locking)
   * Be careful of longrunning or blocking within the provided action as it can lead to deadlocks or bad performance
   */
  def fold[T](on: ⇒ T)(off: ⇒ T) = synchronized {
    if (switch.get) on else off
  }

  /**
   * Returns whether the switch is IMMEDIATELY on (no locking)
   */
  def isOn = switch.get

  /**
   * Returns whether the switch is IMMEDDIATELY off (no locking)
   */
  def isOff = !isOn
}
