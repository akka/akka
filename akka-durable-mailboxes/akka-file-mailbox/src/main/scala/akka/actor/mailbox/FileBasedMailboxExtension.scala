/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRoot
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.{ ExtensionProvider, ActorSystem, Extension, ActorSystemImpl }

object FileBasedMailboxExtension extends Extension[FileBasedMailboxSettings] with ExtensionProvider {
  def lookup() = this
  def createExtension(system: ActorSystemImpl) = new FileBasedMailboxSettings(system.applicationConfig)
}

class FileBasedMailboxSettings(cfg: Config) {
  private def referenceConfig: Config =
    ConfigFactory.parseResource(classOf[ActorSystem], "/akka-file-mailbox-reference.conf",
      ConfigParseOptions.defaults.setAllowMissing(false))
  val config: ConfigRoot = ConfigFactory.emptyRoot("akka-file-mailbox").withFallback(cfg).withFallback(referenceConfig).resolve()

  import config._

  val QueuePath = getString("akka.actor.mailbox.file-based.directory-path")

  val MaxItems = getInt("akka.actor.mailbox.file-based.max-items")
  val MaxSize = getMemorySizeInBytes("akka.actor.mailbox.file-based.max-size")
  val MaxItemSize = getMemorySizeInBytes("akka.actor.mailbox.file-based.max-item-size")
  val MaxAge = Duration(getMilliseconds("akka.actor.mailbox.file-based.max-age"), MILLISECONDS)
  val MaxJournalSize = getMemorySizeInBytes("akka.actor.mailbox.file-based.max-journal-size")
  val MaxMemorySize = getMemorySizeInBytes("akka.actor.mailbox.file-based.max-memory-size")
  val MaxJournalOverflow = getInt("akka.actor.mailbox.file-based.max-journal-overflow")
  val MaxJournalSizeAbsolute = getMemorySizeInBytes("akka.actor.mailbox.file-based.max-journal-size-absolute")
  val DiscardOldWhenFull = getBoolean("akka.actor.mailbox.file-based.discard-old-when-full")
  val KeepJournal = getBoolean("akka.actor.mailbox.file-based.keep-journal")
  val SyncJournal = getBoolean("akka.actor.mailbox.file-based.sync-journal")

}