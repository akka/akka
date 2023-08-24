/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.typed.internal

import akka.annotation.InternalStableApi

/**
 * INTERNAL API: Can be implemented by `ReadJournal` if it supports emitting `EventEnvelope` that
 * has not deserialized the event, i.e. envelope with [[akka.persistence.query.typed.EventEnvelope.SerializedEvent]].
 */
@InternalStableApi private[akka] trait WithSerializedEvent[Self] {

  @InternalStableApi private[akka] def useSerializedEvent: Boolean

  @InternalStableApi private[akka] def withSerializedEvent(): Self

}
