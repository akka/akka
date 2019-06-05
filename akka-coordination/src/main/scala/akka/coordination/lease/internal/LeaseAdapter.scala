/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.coordination.lease.internal

import java.util.Optional
import java.util.concurrent.CompletionStage
import java.util.function.Consumer

import akka.annotation.InternalApi
import akka.coordination.lease.LeaseSettings
import akka.coordination.lease.javadsl.Lease
import akka.coordination.lease.scaladsl.{ Lease => ScalaLease }

import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._
import scala.concurrent.ExecutionContext

/**
 * INTERNAL API
 */
@InternalApi
final private[akka] class LeaseAdapter(delegate: ScalaLease)(implicit val ec: ExecutionContext) extends Lease {

  override def acquire(): CompletionStage[java.lang.Boolean] = delegate.acquire().map(Boolean.box).toJava

  override def acquire(leaseLostCallback: Consumer[Optional[Throwable]]): CompletionStage[java.lang.Boolean] = {
    delegate.acquire(o => leaseLostCallback.accept(o.asJava)).map(Boolean.box).toJava
  }

  override def release(): CompletionStage[java.lang.Boolean] = delegate.release().map(Boolean.box).toJava
  override def checkLease(): Boolean = delegate.checkLease()
  override def getSettings(): LeaseSettings = delegate.settings
}
