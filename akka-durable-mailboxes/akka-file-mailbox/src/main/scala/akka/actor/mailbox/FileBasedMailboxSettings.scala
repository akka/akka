/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.ActorSystem

class FileBasedMailboxSettings(val system: ActorSystem, val userConfig: Config) extends DurableMailboxSettings {

  def name = "file-based"

  val config = initialize

  import config._

  val QueuePath = getString("directory-path")

  val MaxItems = getInt("max-items")
  val MaxSize = getBytes("max-size")
  val MaxItemSize = getBytes("max-item-size")
  val MaxAge = Duration(getMilliseconds("max-age"), MILLISECONDS)
  val MaxJournalSize = getBytes("max-journal-size")
  val MaxMemorySize = getBytes("max-memory-size")
  val MaxJournalOverflow = getInt("max-journal-overflow")
  val MaxJournalSizeAbsolute = getBytes("max-journal-size-absolute")
  val DiscardOldWhenFull = getBoolean("discard-old-when-full")
  val KeepJournal = getBoolean("keep-journal")
  val SyncJournal = getBoolean("sync-journal")

}