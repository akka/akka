/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.util

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
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

