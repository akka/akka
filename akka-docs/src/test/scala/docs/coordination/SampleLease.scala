/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.coordination

import scala.concurrent.Future

import akka.coordination.lease.LeaseSettings
import akka.coordination.lease.scaladsl.Lease

//#lease-example
class SampleLease(settings: LeaseSettings) extends Lease(settings) {

  override def acquire(): Future[Boolean] = {
    Future.successful(true)
  }

  override def acquire(leaseLostCallback: Option[Throwable] => Unit): Future[Boolean] = {
    Future.successful(true)
  }

  override def release(): Future[Boolean] = {
    Future.successful(true)
  }

  override def checkLease(): Boolean = {
    true
  }
}
