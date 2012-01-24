/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor._

object FileBasedMailboxExtension extends ExtensionId[FileBasedMailboxSettings] with ExtensionIdProvider {
  override def get(system: ActorSystem): FileBasedMailboxSettings = super.get(system)
  def lookup() = this
  def createExtension(system: ExtendedActorSystem) = new FileBasedMailboxSettings(system.settings.config)
}

class FileBasedMailboxSettings(val config: Config) extends Extension {

  import config._

  val QueuePath = getString("akka.actor.mailbox.file-based.directory-path")

  val MaxItems = getInt("akka.actor.mailbox.file-based.max-items")
  val MaxSize = getBytes("akka.actor.mailbox.file-based.max-size")
  val MaxItemSize = getBytes("akka.actor.mailbox.file-based.max-item-size")
  val MaxAge = Duration(getMilliseconds("akka.actor.mailbox.file-based.max-age"), MILLISECONDS)
  val MaxJournalSize = getBytes("akka.actor.mailbox.file-based.max-journal-size")
  val MaxMemorySize = getBytes("akka.actor.mailbox.file-based.max-memory-size")
  val MaxJournalOverflow = getInt("akka.actor.mailbox.file-based.max-journal-overflow")
  val MaxJournalSizeAbsolute = getBytes("akka.actor.mailbox.file-based.max-journal-size-absolute")
  val DiscardOldWhenFull = getBoolean("akka.actor.mailbox.file-based.discard-old-when-full")
  val KeepJournal = getBoolean("akka.actor.mailbox.file-based.keep-journal")
  val SyncJournal = getBoolean("akka.actor.mailbox.file-based.sync-journal")

}