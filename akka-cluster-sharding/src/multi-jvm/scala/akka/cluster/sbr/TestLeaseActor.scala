/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sbr

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.Props
import TestLeaseActor.Acquire
import TestLeaseActor.Release
import akka.coordination.lease.LeaseSettings
import akka.coordination.lease.scaladsl.Lease
import akka.pattern.ask
import akka.util.Timeout

object TestLeaseActor {
  def props: Props =
    Props(new TestLeaseActor)

  final case class Acquire(owner: String)
  final case class Release(owner: String)
}

class TestLeaseActor extends Actor with ActorLogging {
  import TestLeaseActor._

  var owner: Option[String] = None

  override def receive = {
    case Acquire(o) =>
      owner match {
        case None =>
          log.info("ActorLease: acquired by [{}]", o)
          owner = Some(o)
          sender() ! true
        case Some(`o`) =>
          log.info("ActorLease: renewed by [{}]", o)
          sender() ! true
        case Some(existingOwner) =>
          log.info("ActorLease: requested by [{}], but already held by [{}]", o, existingOwner)
          sender() ! false
      }

    case Release(o) =>
      owner match {
        case None =>
          log.info("ActorLease: released by [{}] but no owner", o)
          owner = Some(o)
          sender() ! true
        case Some(`o`) =>
          log.info("ActorLease: released by [{}]", o)
          sender() ! true
        case Some(existingOwner) =>
          log.info("ActorLease: release attempt by [{}], but held by [{}]", o, existingOwner)
          sender() ! false
      }
  }

}

object TestLeaseActorClientExt extends ExtensionId[TestLeaseActorClientExt] with ExtensionIdProvider {
  override def get(system: ActorSystem): TestLeaseActorClientExt = super.get(system)
  override def lookup = TestLeaseActorClientExt
  override def createExtension(system: ExtendedActorSystem): TestLeaseActorClientExt =
    new TestLeaseActorClientExt(system)
}

class TestLeaseActorClientExt(val system: ExtendedActorSystem) extends Extension {

  private val leaseClient = new AtomicReference[TestLeaseActorClient]()

  def getActorLeaseClient(): TestLeaseActorClient = {
    val lease = leaseClient.get
    if (lease == null) throw new IllegalStateException("ActorLeaseClient must be set first")
    lease
  }

  def setActorLeaseClient(client: TestLeaseActorClient): Unit =
    leaseClient.set(client)

}

class TestLeaseActorClient(settings: LeaseSettings, system: ExtendedActorSystem) extends Lease(settings) {

  TestLeaseActorClientExt(system).setActorLeaseClient(this)

  private implicit val timeout = Timeout(3.seconds)

  private val _leaseRef = new AtomicReference[ActorRef]

  private def leaseRef: ActorRef = {
    val ref = _leaseRef.get
    if (ref == null) throw new IllegalStateException("ActorLeaseRef must be set first")
    ref
  }

  def setActorLeaseRef(ref: ActorRef): Unit =
    _leaseRef.set(ref)

  override def acquire(): Future[Boolean] = {
    (leaseRef ? Acquire(settings.ownerName)).mapTo[Boolean]
  }

  override def acquire(leaseLostCallback: Option[Throwable] => Unit): Future[Boolean] =
    acquire()

  override def release(): Future[Boolean] = {
    (leaseRef ? Release(settings.ownerName)).mapTo[Boolean]
  }

  override def checkLease(): Boolean = false
}
