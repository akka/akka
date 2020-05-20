/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sbr

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.Future

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.coordination.lease.LeaseSettings
import akka.coordination.lease.scaladsl.Lease

object TestLeaseExt extends ExtensionId[TestLeaseExt] with ExtensionIdProvider {
  override def get(system: ActorSystem): TestLeaseExt = super.get(system)
  override def lookup = TestLeaseExt
  override def createExtension(system: ExtendedActorSystem): TestLeaseExt = new TestLeaseExt(system)
}

class TestLeaseExt(val system: ExtendedActorSystem) extends Extension {

  private val testLease = new AtomicReference[TestLease]()

  def getTestLease(): TestLease = {
    val lease = testLease.get
    if (lease == null) throw new IllegalStateException("TestLease must be set first")
    lease
  }

  def setTestLease(lease: TestLease): Unit =
    testLease.set(lease)

}

object TestLease {
  final case class AcquireReq(owner: String)
  final case class ReleaseReq(owner: String)
}

class TestLease(settings: LeaseSettings, system: ExtendedActorSystem) extends Lease(settings) {
  import TestLease._

  TestLeaseExt(system).setTestLease(this)

  private val nextAcquireResult = new AtomicReference[Future[Boolean]](Future.successful(false))

  private val probe = new AtomicReference[Option[ActorRef]](None)

  def setNextAcquireResult(next: Future[Boolean]): Unit =
    nextAcquireResult.set(next)

  def setProbe(ref: ActorRef): Unit =
    probe.set(Some(ref))

  override def acquire(): Future[Boolean] = {
    probe.get().foreach(_ ! AcquireReq(settings.ownerName))
    nextAcquireResult.get()
  }

  override def acquire(leaseLostCallback: Option[Throwable] => Unit): Future[Boolean] =
    acquire()

  override def release(): Future[Boolean] = {
    probe.get().foreach(_ ! ReleaseReq(settings.ownerName))
    Future.successful(true)
  }

  override def checkLease(): Boolean = false
}
