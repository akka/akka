/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.typed.internal

import akka.annotation.InternalApi

/**
 * INTERNAL API: Can be implemented by `ReadJournal` if it supports emitting `EventEnvelope` that
 * has not deserialized the event, i.e. envelope with [[akka.persistence.query.typed.EventEnvelope.SerializedEvent]].
 */
@InternalApi private[akka] trait WithSerializedEvent {

  def useSerializedEvent: Boolean

  def withSerializedEvent(): WithSerializedEvent

}
