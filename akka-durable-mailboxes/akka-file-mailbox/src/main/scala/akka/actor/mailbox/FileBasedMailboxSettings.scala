/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.ActorSystem

class FileBasedMailboxSettings(val systemSettings: ActorSystem.Settings, val userConfig: Config)
  extends DurableMailboxSettings {

  def name: String = "file-based"

  val config = initialize
  import config._

  val QueuePath: String = getString("directory-path")
  val MaxItems: Int = getInt("max-items")
  val MaxSize: Long = getBytes("max-size")
  val MaxItemSize: Long = getBytes("max-item-size")
  val MaxAge: Duration = Duration(getMilliseconds("max-age"), MILLISECONDS)
  val MaxJournalSize: Long = getBytes("max-journal-size")
  val MaxMemorySize: Long = getBytes("max-memory-size")
  val MaxJournalOverflow: Int = getInt("max-journal-overflow")
  val MaxJournalSizeAbsolute: Long = getBytes("max-journal-size-absolute")
  val DiscardOldWhenFull: Boolean = getBoolean("discard-old-when-full")
  val KeepJournal: Boolean = getBoolean("keep-journal")
  val SyncJournal: Boolean = getBoolean("sync-journal")

  val CircuitBreakerMaxFailures = getInt("circuit-breaker.max-failures")
  val CircuitBreakerCallTimeout = Duration.fromNanos(getNanoseconds("circuit-breaker.call-timeout"))
  val CircuitBreakerResetTimeout = Duration.fromNanos(getNanoseconds("circuit-breaker.reset-timeout"))
  val CircuitBreakerMaxFailures = getInt("circuit-breaker.max-failures")
  val CircuitBreakerCallTimeout = Duration.fromNanos(getNanoseconds("circuit-breaker.call-timeout"))
  val CircuitBreakerResetTimeout = Duration.fromNanos(getNanoseconds("circuit-breaker.reset-timeout"))
}