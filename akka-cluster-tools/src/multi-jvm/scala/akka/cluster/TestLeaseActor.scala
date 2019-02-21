/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

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
import akka.cluster.TestLeaseActor.{ Acquire, Create, Release }
import akka.event.Logging
import akka.lease.LeaseSettings
import akka.lease.scaladsl.Lease
import akka.pattern.ask
import akka.util.Timeout

object TestLeaseActor {
  def props: Props =
    Props(new TestLeaseActor)

  final case class Acquire(owner: String)
  final case class Release(owner: String)
  final case class Create(lesaeName: String, ownerName: String)
}

class TestLeaseActor extends Actor with ActorLogging {
  import TestLeaseActor._

  // TODO support multiple named leases
  var owner: Option[String] = None
  var leaseName: Option[String] = None

  override def receive = {

    case Create(name, ownerName) ⇒
      log.info("Lease created with name {} ownerName {}", name, ownerName)
      leaseName = Some(name)

    case Acquire(o) ⇒
      owner match {
        case None ⇒
          log.info("ActorLease: acquired by [{}]", o)
          owner = Some(o)
          sender() ! true
        case Some(`o`) ⇒
          log.info("ActorLease: renewed by [{}]", o)
          sender() ! true
        case Some(existingOwner) ⇒
          log.info("ActorLease: requested by [{}], but already held by [{}]", o, existingOwner)
          sender() ! false
      }

    case Release(o) ⇒
      owner match {
        case None ⇒
          log.info("ActorLease: released by [{}] but no owner", o)
          owner = Some(o)
          sender() ! true
        case Some(`o`) ⇒
          log.info("ActorLease: released by [{}]", o)
          sender() ! true
        case Some(existingOwner) ⇒
          log.info("ActorLease: release attempt by [{}], but held by [{}]", o, existingOwner)
          sender() ! false
      }
  }

}

object TestLeaseActorClientExt extends ExtensionId[TestLeaseActorClientExt] with ExtensionIdProvider {
  override def get(system: ActorSystem): TestLeaseActorClientExt = super.get(system)
  override def lookup = TestLeaseActorClientExt
  override def createExtension(system: ExtendedActorSystem): TestLeaseActorClientExt = new TestLeaseActorClientExt(system)
}

class TestLeaseActorClientExt(val system: ExtendedActorSystem) extends Extension {

  private val leaseActor = new AtomicReference[ActorRef]()
  private val log = Logging(system, getClass)

  def getLeaseActor(): ActorRef = {
    val lease = leaseActor.get
    if (lease == null) throw new IllegalStateException("LeaseActorRef must be set first")
    lease
  }

  def setActorActor(client: ActorRef): Unit =
    leaseActor.set(client)

}

class TestLeaseActorClient(settings: LeaseSettings, system: ExtendedActorSystem) extends Lease(settings) {

  private val log = Logging(system, getClass)
  val leaseActor = TestLeaseActorClientExt(system).getLeaseActor()

  log.info("lease created {}", settings)
  leaseActor ! Create(settings.leaseName, settings.ownerName)

  private implicit val timeout = Timeout(3.seconds)

  override def acquire(): Future[Boolean] = {
    (leaseActor ? Acquire(settings.ownerName)).mapTo[Boolean]
  }

  override def release(): Future[Boolean] = {
    (leaseActor ? Release(settings.ownerName)).mapTo[Boolean]
  }

  override def checkLease(): Boolean = false

}
