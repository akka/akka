/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.aeron.scaladsl

import scala.concurrent.duration.FiniteDuration

/**
 * @param channel  Aeron Channel name. E.g. aeron:udp?endpoint=localhost:40123 or aeron:ipc
 * @param streamId Stream within the Channel to publish to
 */
// TODO, not a case class
case class AeronSinkSettings(
  channel:     String,
  streamId:    Int,
  giveUpAfter: FiniteDuration
)
