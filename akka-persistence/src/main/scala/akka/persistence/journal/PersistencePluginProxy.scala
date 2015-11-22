/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.journal

import java.net.URISyntaxException

import akka.util.Helpers.Requiring
import scala.concurrent.duration._
import akka.actor._
import scala.concurrent.duration.FiniteDuration
import akka.persistence.JournalProtocol
import akka.persistence.Persistence
import java.util.concurrent.TimeoutException
import akka.persistence.AtomicWrite
import akka.persistence.NonPersistentRepr
import akka.persistence.DeleteMessagesFailure
import com.typesafe.config.Config
import akka.persistence.SaveSnapshotFailure
import akka.persistence.DeleteSnapshotFailure
import akka.persistence.DeleteSnapshotsFailure
import akka.persistence.SnapshotProtocol

object PersistencePluginProxy {
  final case class TargetLocation(address: Address)
  private case object InitTimeout

  def setTargetLocation(system: ActorSystem, address: Address): Unit = {
    Persistence(system).journalFor(null) ! TargetLocation(address)
    if (system.settings.config.getString("akka.persistence.snapshot-store.plugin") != "")
      Persistence(system).snapshotStoreFor(null) ! TargetLocation(address)
  }

  private sealed trait PluginType {
    def qualifier: String
  }
  private case object Journal extends PluginType {
    override def qualifier: String = "journal"
  }
  private case object SnapshotStore extends PluginType {
    override def qualifier: String = "snapshot-store"
  }
}

final class PersistencePluginProxy(config: Config) extends Actor with Stash with ActorLogging {
  import PersistencePluginProxy._
  import JournalProtocol._
  import SnapshotProtocol._

  private val pluginId = self.path.name
  private val pluginType: PluginType = pluginId match {
    case "akka.persistence.journal.proxy"        ⇒ Journal
    case "akka.persistence.snapshot-store.proxy" ⇒ SnapshotStore
    case other ⇒
      throw new IllegalArgumentException("Unknown plugin type: " + other)
  }

  private val timeout: FiniteDuration = config.getDuration("init-timeout", MILLISECONDS).millis
  private val targetPluginId: String = {
    val key = s"target-${pluginType.qualifier}-plugin"
    config.getString(key).requiring(_ != "", s"$pluginId.$key must be defined")
  }
  private val startTarget: Boolean = config.getBoolean(s"start-target-${pluginType.qualifier}")

  override def preStart(): Unit = {
    if (startTarget) {
      val target = pluginType match {
        case Journal ⇒
          log.info("Starting target journal [{}]", targetPluginId)
          Persistence(context.system).journalFor(targetPluginId)
        case SnapshotStore ⇒
          log.info("Starting target snapshot-store [{}]", targetPluginId)
          Persistence(context.system).snapshotStoreFor(targetPluginId)
      }
      context.become(active(target, targetAtThisNode = true))
    } else {
      context.system.scheduler.scheduleOnce(timeout, self, InitTimeout)(context.dispatcher)
    }

    val targetAddressKey = s"target-${pluginType.qualifier}-address"
    if (config.hasPath(targetAddressKey)) {
      val targetAddress = config.getString(targetAddressKey)
      try {
        log.info("Setting target {} address to {}", pluginType.qualifier, targetAddress)
        PersistencePluginProxy.setTargetLocation(context.system, AddressFromURIString(targetAddress))
      } catch {
        case _: URISyntaxException ⇒ log.warning("Invalid URL provided for target {} address: {}", pluginType.qualifier, targetAddress)
      }
    }
  }

  private val selfAddress: Address =
    context.system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress

  private def timeoutException() = new TimeoutException(s"Target ${pluginType.qualifier} not initialized. " +
    "Use `PersistencePluginProxy.setTargetLocation`")

  def receive = init

  def init: Receive = {
    case TargetLocation(address) ⇒
      context.setReceiveTimeout(1.second) // for retries
      context.become(identifying(address))
    case InitTimeout ⇒
      log.info("Initialization timed-out (after {}), Use `PersistencePluginProxy.setTargetLocation`", timeout)
      context.become(initTimedOut)
      unstashAll() // will trigger appropriate failures
    case msg ⇒
      stash()
  }

  def becomeIdentifying(address: Address): Unit = {
    sendIdentify(address)
    context.setReceiveTimeout(1.second) // for retries
    context.become(identifying(address))
  }

  def sendIdentify(address: Address): Unit = {
    val sel = context.actorSelection(RootActorPath(address) / "system" / targetPluginId)
    log.info("Trying to identify target {} at {}", pluginType.qualifier, sel)
    sel ! Identify(targetPluginId)
  }

  def identifying(address: Address): Receive = ({
    case ActorIdentity(`targetPluginId`, Some(target)) ⇒
      log.info("Found target {} at [{}]", pluginType.qualifier, address)
      context.setReceiveTimeout(Duration.Undefined)
      unstashAll()
      context.become(active(target, address == selfAddress))
    case _: ActorIdentity ⇒ // will retry after ReceiveTimeout
    case ReceiveTimeout ⇒
      sendIdentify(address)
  }: Receive).orElse(init)

  def active(targetJournal: ActorRef, targetAtThisNode: Boolean): Receive = {
    case TargetLocation(address) ⇒
      if (targetAtThisNode && address != selfAddress)
        becomeIdentifying(address)
    case InitTimeout ⇒
    case msg ⇒
      import akka.pattern.{ ask, pipe }
      val sender = context.sender()
      val f = ask(targetJournal, msg)(1.second)
      pipe(f)(context.system.dispatcher) to (sender, self)
  }

  def initTimedOut: Receive = {

    case req: JournalProtocol.Request ⇒ req match { // exhaustive match
      case WriteMessages(messages, persistentActor, actorInstanceId) ⇒
        persistentActor ! WriteMessagesFailed(timeoutException)
        messages.foreach {
          case a: AtomicWrite ⇒
            a.payload.foreach { p ⇒
              persistentActor ! WriteMessageFailure(p, timeoutException, actorInstanceId)
            }
          case r: NonPersistentRepr ⇒
            persistentActor ! LoopMessageSuccess(r.payload, actorInstanceId)
        }
      case ReplayMessages(fromSequenceNr, toSequenceNr, max, persistenceId, persistentActor) ⇒
        persistentActor ! ReplayMessagesFailure(timeoutException)
      case DeleteMessagesTo(persistenceId, toSequenceNr, persistentActor) ⇒
        persistentActor ! DeleteMessagesFailure(timeoutException, toSequenceNr)
    }

    case req: SnapshotProtocol.Request ⇒ req match { // exhaustive match
      case LoadSnapshot(persistenceId, criteria, toSequenceNr) ⇒
        sender() ! LoadSnapshotResult(None, toSequenceNr)
      case SaveSnapshot(metadata, snapshot) ⇒
        sender() ! SaveSnapshotFailure(metadata, timeoutException)
      case DeleteSnapshot(metadata) ⇒
        sender() ! DeleteSnapshotFailure(metadata, timeoutException)
      case DeleteSnapshots(persistenceId, criteria) ⇒
        sender() ! DeleteSnapshotsFailure(criteria, timeoutException)
    }

    case TargetLocation(address) ⇒
      becomeIdentifying(address)

    case other ⇒
      val e = timeoutException()
      log.error(e, "Failed PersistencePluginProxy request: {}", e.getMessage)
  }

}
