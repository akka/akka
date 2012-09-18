/*
 * Copyright 2009 Twitter, Inc.
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.actor.mailbox.filebased.filequeue

import java.io._
import scala.collection.mutable
import akka.event.LoggingAdapter
import scala.concurrent.util.Duration
import java.util.concurrent.TimeUnit
import akka.actor.mailbox.filebased.FileBasedMailboxSettings

// a config value that's backed by a global setting but may be locally overridden
class OverlaySetting[T](base: ⇒ T) {
  @volatile
  private var local: Option[T] = None

  def set(value: Option[T]) = local = value

  def apply() = local.getOrElse(base)
}

trait Prependable[T] {
  def prepend(t: T): Unit
}

class PersistentQueue(persistencePath: String, val name: String, val settings: FileBasedMailboxSettings, log: LoggingAdapter) {

  private case object ItemArrived

  // current size of all data in the queue:
  private var queueSize: Long = 0

  // # of items EVER added to the queue:
  private var _totalItems: Long = 0

  // # of items that were expired by the time they were read:
  private var _totalExpired: Long = 0

  // age (in milliseconds) of the last item read from the queue:
  private var _currentAge: Long = 0

  // # of items thot were discarded because the queue was full:
  private var _totalDiscarded: Long = 0

  // # of items in the queue (including those not in memory)
  private var queueLength: Long = 0

  private var queue: mutable.Queue[QItem] with Prependable[QItem] = new mutable.Queue[QItem] with Prependable[QItem] {
    // scala's Queue doesn't (yet?) have a way to put back.
    def prepend(item: QItem) = prependElem(item)
  }
  private var _memoryBytes: Long = 0

  private var closed = false
  private var paused = false

  def overlay[T](base: ⇒ T) = new OverlaySetting(base)

  // attempting to add an item after the queue reaches this size (in items) will fail.
  final val maxItems = overlay(PersistentQueue.maxItems)

  // attempting to add an item after the queue reaches this size (in bytes) will fail.
  final val maxSize = overlay(PersistentQueue.maxSize)

  // attempting to add an item larger than this size (in bytes) will fail.
  final val maxItemSize = overlay(PersistentQueue.maxItemSize)

  // maximum expiration time for this queue (seconds).
  final val maxAge = overlay(PersistentQueue.maxAge)

  // maximum journal size before the journal should be rotated.
  final val maxJournalSize = overlay(PersistentQueue.maxJournalSize)

  // maximum size of a queue before it drops into read-behind mode.
  final val maxMemorySize = overlay(PersistentQueue.maxMemorySize)

  // maximum overflow (multiplier) of a journal file before we re-create it.
  final val maxJournalOverflow = overlay(PersistentQueue.maxJournalOverflow)

  // absolute maximum size of a journal file until we rebuild it, no matter what.
  final val maxJournalSizeAbsolute = overlay(PersistentQueue.maxJournalSizeAbsolute)

  // whether to drop older items (instead of newer) when the queue is full
  final val discardOldWhenFull = overlay(PersistentQueue.discardOldWhenFull)

  // whether to keep a journal file at all
  final val keepJournal = overlay(PersistentQueue.keepJournal)

  // whether to sync the journal after each transaction
  final val syncJournal = overlay(PersistentQueue.syncJournal)

  // (optional) move expired items over to this queue
  final val expiredQueue = overlay(PersistentQueue.expiredQueue)

  private var journal = new Journal(new File(persistencePath, name).getCanonicalPath, syncJournal(), log)

  // track tentative remofinal vals
  private var xidCounter: Int = 0
  private val openTransactions = new mutable.HashMap[Int, QItem]
  def openTransactionCount = openTransactions.size
  def openTransactionIds = openTransactions.keys.toList.sortWith(_ - _ > 0)

  def length: Long = synchronized { queueLength }
  def totalItems: Long = synchronized { _totalItems }
  def bytes: Long = synchronized { queueSize }
  def journalSize: Long = synchronized { journal.size }
  def totalExpired: Long = synchronized { _totalExpired }
  def currentAge: Long = synchronized { if (queueSize == 0) 0 else _currentAge }
  def totalDiscarded: Long = synchronized { _totalDiscarded }
  def isClosed: Boolean = synchronized { closed || paused }

  // mostly for unit tests.
  def memoryLength: Long = synchronized { queue.size }
  def memoryBytes: Long = synchronized { _memoryBytes }
  def inReadBehind = synchronized { journal.inReadBehind }

  configure(settings)

  def configure(settings: FileBasedMailboxSettings) = synchronized {
    maxItems set Some(settings.MaxItems)
    maxSize set Some(settings.MaxSize)
    maxItemSize set Some(settings.MaxItemSize)
    maxAge set Some(settings.MaxAge.toSeconds.toInt)
    maxJournalSize set Some(settings.MaxJournalSize)
    maxMemorySize set Some(settings.MaxMemorySize)
    maxJournalOverflow set Some(settings.MaxJournalOverflow)
    maxJournalSizeAbsolute set Some(settings.MaxJournalSizeAbsolute)
    discardOldWhenFull set Some(settings.DiscardOldWhenFull)
    keepJournal set Some(settings.KeepJournal)
    syncJournal set Some(settings.SyncJournal)
    log.info("Configuring queue %s: journal=%s, max-items=%s, max-size=%s, max-age=%s, max-journal-size=%s, max-memory-size=%s, max-journal-overflow=%s, max-journal-size-absolute=%s, discard-old-when-full=%s, sync-journal=%s"
      .format(
        name, keepJournal(), maxItems(), maxSize(), maxAge(), maxJournalSize(), maxMemorySize(),
        maxJournalOverflow(), maxJournalSizeAbsolute(), discardOldWhenFull(), syncJournal()))
    if (!keepJournal()) journal.erase()
  }

  def dumpConfig(): Array[String] = synchronized {
    Array(
      "max-items=" + maxItems(),
      "max-size=" + maxSize(),
      "max-age=" + maxAge(),
      "max-journal-size=" + maxJournalSize(),
      "max-memory-size=" + maxMemorySize(),
      "max-journal-overflow=" + maxJournalOverflow(),
      "max-journal-size-absolute=" + maxJournalSizeAbsolute(),
      "discard-old-when-full=" + discardOldWhenFull(),
      "journal=" + keepJournal(),
      "sync-journal=" + syncJournal(),
      "move-expired-to" + expiredQueue().map { _.name }.getOrElse("(none)"))
  }

  def dumpStats(): Array[(String, String)] = synchronized {
    Array(
      ("items", length.toString),
      ("bytes", bytes.toString),
      ("total-items", totalItems.toString),
      ("logsize", journalSize.toString),
      ("expired-items", totalExpired.toString),
      ("mem-items", memoryLength.toString),
      ("mem-bytes", memoryBytes.toString),
      ("age", currentAge.toString),
      ("discarded", totalDiscarded.toString),
      ("open-transactions", openTransactionCount.toString))
  }

  private final def adjustExpiry(startingTime: Long, expiry: Long): Long = {
    if (maxAge() > 0) {
      val maxExpiry = startingTime + maxAge()
      if (expiry > 0) (expiry min maxExpiry) else maxExpiry
    } else {
      expiry
    }
  }

  /**
   * Add a value to the end of the queue, transactionally.
   */
  def add(value: Array[Byte], expiry: Long): Boolean = synchronized {
    if (closed || value.size > maxItemSize()) return false
    while (queueLength >= maxItems() || queueSize >= maxSize()) {
      if (!discardOldWhenFull()) return false
      _remove(false)
      _totalDiscarded += 1
      if (keepJournal()) journal.remove()
    }

    val now = System.currentTimeMillis
    val item = QItem(now, adjustExpiry(now, expiry), value, 0)
    if (keepJournal() && !journal.inReadBehind) {
      if (journal.size > maxJournalSize() * maxJournalOverflow() && queueSize < maxJournalSize()) {
        // force re-creation of the journal.
        log.debug("Rolling journal file for '{}' (qsize={})", name, queueSize)
        journal.roll(xidCounter, openTransactionIds map { openTransactions(_) }, queue)
      }
      if (queueSize >= maxMemorySize()) {
        log.debug("Dropping to read-behind for queue '{}' ({} bytes)", name, queueSize)
        journal.startReadBehind
      }
    }
    _add(item)
    if (keepJournal()) journal.add(item)
    true
  }

  def add(value: Array[Byte]): Boolean = add(value, 0)

  /**
   * Peek at the head item in the queue, if there is one.
   */
  def peek(): Option[QItem] = {
    synchronized {
      if (closed || paused || queueLength == 0) {
        None
      } else {
        _peek()
      }
    }
  }

  /**
   * Remove and return an item from the queue, if there is one.
   *
   * @param transaction true if this should be considered the first part
   *     of a transaction, to be committed or rolled back (put back at the
   *     head of the queue)
   */
  def remove(transaction: Boolean): Option[QItem] = {
    synchronized {
      if (closed || paused || queueLength == 0) {
        None
      } else {
        val item = _remove(transaction)
        if (keepJournal()) {
          if (transaction) journal.removeTentative() else journal.remove()

          if ((queueLength == 0) && (journal.size >= maxJournalSize())) {
            log.debug("Rolling journal file for '{}'", name)
            journal.roll(xidCounter, openTransactionIds map { openTransactions(_) }, Nil)
          }
        }
        item
      }
    }
  }

  /**
   * Remove and return an item from the queue, if there is one.
   */
  def remove(): Option[QItem] = remove(false)

  /**
   * Return a transactionally-removed item to the queue. This is a rolled-
   * back transaction.
   */
  def unremove(xid: Int) {
    synchronized {
      if (!closed) {
        if (keepJournal()) journal.unremove(xid)
        _unremove(xid)
      }
    }
  }

  def confirmRemove(xid: Int) {
    synchronized {
      if (!closed) {
        if (keepJournal()) journal.confirmRemove(xid)
        openTransactions.remove(xid)
      }
    }
  }

  def flush() {
    while (remove(false).isDefined) {}
  }

  /**
   * Close the queue's journal file. Not safe to call on an active queue.
   */
  def close(): Unit = synchronized {
    closed = true
    if (keepJournal()) journal.close()
  }

  def pauseReads(): Unit = synchronized {
    paused = true
  }

  def resumeReads(): Unit = synchronized {
    paused = false
  }

  def setup(): Unit = synchronized {
    queueSize = 0
    replayJournal
  }

  def destroyJournal(): Unit = synchronized {
    if (keepJournal()) journal.erase()
  }

  private final def nextXid(): Int = {
    do {
      xidCounter += 1
    } while (openTransactions contains xidCounter)
    xidCounter
  }

  private final def fillReadBehind() {
    // if we're in read-behind mode, scan forward in the journal to keep memory as full as
    // possible. this amortizes the disk overhead across all reads.
    while (keepJournal() && journal.inReadBehind && _memoryBytes < maxMemorySize()) {
      journal.fillReadBehind { item ⇒
        queue += item
        _memoryBytes += item.data.length
      }
      if (!journal.inReadBehind) {
        log.debug("Coming out of read-behind for queue '{}'", name)
      }
    }
  }

  def replayJournal() {
    if (!keepJournal()) return

    log.debug("Replaying transaction journal for '{}'", name)
    xidCounter = 0

    journal.replay(name) {
      case JournalItem.Add(item) ⇒
        _add(item)
        // when processing the journal, this has to happen after:
        if (!journal.inReadBehind && queueSize >= maxMemorySize()) {
          log.debug("Dropping to read-behind for queue '{}' ({} bytes)", name, queueSize)
          journal.startReadBehind
        }
      case JournalItem.Remove             ⇒ _remove(false)
      case JournalItem.RemoveTentative    ⇒ _remove(true)
      case JournalItem.SavedXid(xid)      ⇒ xidCounter = xid
      case JournalItem.Unremove(xid)      ⇒ _unremove(xid)
      case JournalItem.ConfirmRemove(xid) ⇒ openTransactions.remove(xid)
      case x                              ⇒ log.warning("Unexpected item in journal: {}", x)
    }

    log.debug("Finished transaction journal for '{}' ({} items, {} bytes)",
      name, queueLength, journal.size)
    journal.open

    // now, any unfinished transactions must be backed out.
    for (xid ← openTransactionIds) {
      journal.unremove(xid)
      _unremove(xid)
    }
  }

  def toList(): List[QItem] = {
    discardExpired
    queue.toList
  }

  //  -----  internal implementations

  private def _add(item: QItem) {
    discardExpired
    if (!journal.inReadBehind) {
      queue += item
      _memoryBytes += item.data.length
    }
    _totalItems += 1
    queueSize += item.data.length
    queueLength += 1
  }

  private def _peek(): Option[QItem] = {
    discardExpired
    if (queue.isEmpty) None else Some(queue.front)
  }

  private def _remove(transaction: Boolean): Option[QItem] = {
    discardExpired()
    if (queue.isEmpty) return None

    val now = System.currentTimeMillis
    val item = queue.dequeue
    val len = item.data.length
    queueSize -= len
    _memoryBytes -= len
    queueLength -= 1
    val xid = if (transaction) nextXid else 0

    fillReadBehind
    _currentAge = now - item.addTime
    if (transaction) {
      item.xid = xid
      openTransactions(xid) = item
    }
    Some(item)
  }

  final def discardExpired(): Int = {
    if (queue.isEmpty || journal.isReplaying) {
      0
    } else {
      val realExpiry = adjustExpiry(queue.front.addTime, queue.front.expiry)
      if ((realExpiry != 0) && (realExpiry < System.currentTimeMillis)) {
        _totalExpired += 1
        val item = queue.dequeue
        val len = item.data.length
        queueSize -= len
        _memoryBytes -= len
        queueLength -= 1
        fillReadBehind
        if (keepJournal()) journal.remove()
        expiredQueue().map { _.add(item.data, 0) }
        1 + discardExpired()
      } else {
        0
      }
    }
  }

  private def _unremove(xid: Int) = {
    openTransactions.remove(xid) map { item ⇒
      queueLength += 1
      queueSize += item.data.length
      queue prepend item
      _memoryBytes += item.data.length
    }
  }
}

object PersistentQueue {
  @volatile
  var maxItems: Int = Int.MaxValue
  @volatile
  var maxSize: Long = Long.MaxValue
  @volatile
  var maxItemSize: Long = Long.MaxValue
  @volatile
  var maxAge: Int = 0
  @volatile
  var maxJournalSize: Long = 16 * 1024 * 1024
  @volatile
  var maxMemorySize: Long = 128 * 1024 * 1024
  @volatile
  var maxJournalOverflow: Int = 10
  @volatile
  var maxJournalSizeAbsolute: Long = Long.MaxValue
  @volatile
  var discardOldWhenFull: Boolean = false
  @volatile
  var keepJournal: Boolean = true
  @volatile
  var syncJournal: Boolean = false
  @volatile
  var expiredQueue: Option[PersistentQueue] = None
}
