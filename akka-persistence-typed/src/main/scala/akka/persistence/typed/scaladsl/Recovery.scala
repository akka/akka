/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.annotation.InternalApi
import akka.persistence.typed.internal.{ DefaultRecovery, DisabledRecovery }

/**
 * Strategy for recovery of snapshots and events.
 */
trait Recovery {
  def asJava: akka.persistence.typed.javadsl.Recovery

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def toClassic: akka.persistence.Recovery

}

/**
 * Strategy for recovery of snapshots and events.
 */
object Recovery {

  /**
   * Snapshots and events are recovered
   */
  val default: Recovery = DefaultRecovery

  /**
   * Neither snapshots nor events are recovered
   */
  val disabled: Recovery = DisabledRecovery

}
