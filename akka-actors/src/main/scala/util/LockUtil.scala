/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.util

import java.util.concurrent.locks.{ReentrantReadWriteLock, ReentrantLock}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ReentrantGuard {
  val lock = new ReentrantLock

  def withGuard[T](body: => T): T = {
    lock.lock
    try {
      body
    } finally {
      lock.unlock
    }
  }

  def tryWithGuard[T](body: => T): T = {
    while(!lock.tryLock) { Thread.sleep(10) } // wait on the monitor to be unlocked
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
  private val readLock = rwl.readLock
  private val writeLock = rwl.writeLock

  def withWriteGuard[T](body: => T): T = {
    writeLock.lock
    try {
      body
    } finally {
      writeLock.unlock
    }
  }

  def withReadGuard[T](body: => T): T = {
    readLock.lock
    try {
      body
    } finally {
      readLock.unlock
    }
  }
}

