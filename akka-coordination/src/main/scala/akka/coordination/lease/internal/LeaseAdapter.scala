/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.coordination.lease.internal

import java.util.Optional
import java.util.concurrent.CompletionStage

import akka.annotation.InternalApi
import akka.coordination.lease.LeaseSettings
import akka.coordination.lease.javadsl.Lease
import akka.coordination.lease.scaladsl.{ Lease ⇒ ScalaLease }

import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._

/**
 * INTERNAL API
 */
@InternalApi
private[akka] class LeaseAdapter(delegate: ScalaLease) extends Lease {
  override def acquire(): CompletionStage[Boolean] = delegate.acquire().toJava
  override def acquire(leaseLostCallback: Optional[Throwable] ⇒ Unit): CompletionStage[Boolean] = delegate.acquire(o ⇒ leaseLostCallback(o.asJava)).toJava
  override def release(): CompletionStage[Boolean] = delegate.release().toJava
  override def checkLease(): Boolean = delegate.checkLease()
  override def getSettings(): LeaseSettings = delegate.settings
}
