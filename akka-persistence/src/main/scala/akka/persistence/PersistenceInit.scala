/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence

import java.util.concurrent.TimeUnit

import akka.actor.ActorLogging
import akka.actor.Props
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[akka] object PersistenceInit {

  def props(journalPluginId: String, snapshotPluginId: String, persistenceId: String): Props = {
    Props(new PersistenceInit(journalPluginId, snapshotPluginId, persistenceId))
  }
}

/**
 * INTERNAL API: Initialize a journal and snapshot plugin by starting this `PersistentActor`
 * and send any message to it. It will reply to the `sender()` with the same message when
 * recovery has completed.
 */
@InternalApi private[akka] class PersistenceInit(
    override val journalPluginId: String,
    override val snapshotPluginId: String,
    override val persistenceId: String)
    extends PersistentActor
    with ActorLogging {

  private val startTime = System.nanoTime()

  def receiveRecover: Receive = {
    case RecoveryCompleted =>
      log.debug(
        "Initialization completed for journal [{}] and snapshot [{}] plugins, with persistenceId [{}], in [{} ms]",
        journalPluginId,
        snapshotPluginId,
        persistenceId,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime))
    case _ =>
  }

  def receiveCommand: Receive = {
    case msg =>
      // recovery has completed
      sender() ! msg
      context.stop(self)
  }
}
