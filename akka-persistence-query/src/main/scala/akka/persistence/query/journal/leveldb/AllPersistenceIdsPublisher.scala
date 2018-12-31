/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.leveldb

import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.persistence.JournalProtocol._
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.LeveldbJournal
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.Cancel
import akka.stream.actor.ActorPublisherMessage.Request

/**
 * INTERNAL API
 */
private[akka] object AllPersistenceIdsPublisher {
  def props(liveQuery: Boolean, maxBufSize: Int, writeJournalPluginId: String): Props =
    Props(new AllPersistenceIdsPublisher(liveQuery, maxBufSize, writeJournalPluginId))

  private case object Continue
}

/**
 * INTERNAL API
 */
// FIXME needs a be rewritten as a GraphStage (since 2.5.0)
private[akka] class AllPersistenceIdsPublisher(liveQuery: Boolean, maxBufSize: Int, writeJournalPluginId: String)
  extends ActorPublisher[String] with DeliveryBuffer[String] with ActorLogging {

  val journal: ActorRef = Persistence(context.system).journalFor(writeJournalPluginId)

  def receive = init

  def init: Receive = {
    case _: Request ⇒
      journal ! LeveldbJournal.SubscribeAllPersistenceIds
      context.become(active)
    case Cancel ⇒ context.stop(self)
  }

  def active: Receive = {
    case LeveldbJournal.CurrentPersistenceIds(allPersistenceIds) ⇒
      buf ++= allPersistenceIds
      deliverBuf()
      if (!liveQuery && buf.isEmpty)
        onCompleteThenStop()

    case LeveldbJournal.PersistenceIdAdded(persistenceId) ⇒
      if (liveQuery) {
        buf :+= persistenceId
        deliverBuf()
      }

    case _: Request ⇒
      deliverBuf()
      if (!liveQuery && buf.isEmpty)
        onCompleteThenStop()

    case Cancel ⇒ context.stop(self)
  }

}
