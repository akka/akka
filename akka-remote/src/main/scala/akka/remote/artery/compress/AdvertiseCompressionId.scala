/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery.compress

import akka.actor.Address

/** Callback invoked when a compression id allocation should be advertised to the remote actor system. */
trait AdvertiseCompressionId[T] {
  def apply(remoteAddress: Address, ref: T, id: Int): Unit
}
