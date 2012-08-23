/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import scala.concurrent.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.ActorSystem
import scala.concurrent.util.FiniteDuration

class FileBasedMailboxSettings(val systemSettings: ActorSystem.Settings, val userConfig: Config)
  extends DurableMailboxSettings {

  def name: String = "file-based"

  val config = initialize
  import config._

  final val QueuePath: String = getString("directory-path")
  final val MaxItems: Int = getInt("max-items")
  final val MaxSize: Long = getBytes("max-size")
  final val MaxItemSize: Long = getBytes("max-item-size")
  final val MaxAge: FiniteDuration = Duration(getMilliseconds("max-age"), MILLISECONDS)
  final val MaxJournalSize: Long = getBytes("max-journal-size")
  final val MaxMemorySize: Long = getBytes("max-memory-size")
  final val MaxJournalOverflow: Int = getInt("max-journal-overflow")
  final val MaxJournalSizeAbsolute: Long = getBytes("max-journal-size-absolute")
  final val DiscardOldWhenFull: Boolean = getBoolean("discard-old-when-full")
  final val KeepJournal: Boolean = getBoolean("keep-journal")
  final val SyncJournal: Boolean = getBoolean("sync-journal")

  final val CircuitBreakerMaxFailures: Int = getInt("circuit-breaker.max-failures")
  final val CircuitBreakerCallTimeout: FiniteDuration = Duration.fromNanos(getNanoseconds("circuit-breaker.call-timeout"))
  final val CircuitBreakerResetTimeout: FiniteDuration = Duration.fromNanos(getNanoseconds("circuit-breaker.reset-timeout"))
}
