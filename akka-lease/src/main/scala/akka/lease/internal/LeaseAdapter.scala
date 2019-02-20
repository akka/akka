/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.lease.internal

import java.util.concurrent.CompletionStage

import akka.annotation.InternalApi
import akka.lease.LeaseSettings
import akka.lease.javadsl.Lease
import akka.lease.scaladsl.{ Lease ⇒ ScalaLease }

import scala.compat.java8.FutureConverters._

/**
 * INTERNAL API
 */
@InternalApi
private[akka] class LeaseAdapter(delegate: ScalaLease) extends Lease() {
  override def acquire(): CompletionStage[Boolean] = delegate.acquire().toJava
  override def release(): CompletionStage[Boolean] = delegate.release().toJava
  override def checkLease(): Boolean = delegate.checkLease()
  override def getSetting(): LeaseSettings = delegate.settings
}
