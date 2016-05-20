/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.util.Success
import akka.Done
import akka.actor.ActorRef
import akka.actor.ActorSelectionMessage
import akka.actor.Address
import akka.actor.RootActorPath
import akka.dispatch.sysmsg.SystemMessage
import akka.event.Logging
import akka.remote.EndpointManager.Send
import akka.remote.RemoteActorRef
import akka.remote.UniqueAddress
import akka.remote.artery.InboundControlJunction.ControlMessageSubject
import akka.remote.artery.OutboundControlJunction.OutboundControlIngress
import akka.remote.artery.OutboundHandshake.HandshakeTimeoutException
import akka.remote.artery.SystemMessageDelivery.ClearSystemMessageDelivery
import akka.stream.AbruptTerminationException
import akka.stream.Materializer
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.util.Unsafe
import akka.remote.PriorityMessage

/**
 * INTERNAL API
 *
 * Thread-safe, mutable holder for association state. Main entry point for remote destined message to a specific
 * remote address.
 */
private[akka] class Association(
  val transport: ArteryTransport,
  val materializer: Materializer,
  override val remoteAddress: Address,
  override val controlSubject: ControlMessageSubject)
  extends AbstractAssociation with OutboundContext {

  private val log = Logging(transport.system, getClass.getName)
  private val controlQueueSize = transport.provider.remoteSettings.SysMsgBufferSize

  private val restartTimeout: FiniteDuration = 5.seconds // FIXME config
  private val maxRestarts = 5 // FIXME config
  private val restartCounter = new RestartCounter(maxRestarts, restartTimeout)

  @volatile private[this] var queue: SourceQueueWithComplete[Send] = _
  @volatile private[this] var controlQueue: SourceQueueWithComplete[Send] = _
  @volatile private[this] var _outboundControlIngress: OutboundControlIngress = _
  @volatile private[this] var materializing = new CountDownLatch(1)

  def outboundControlIngress: OutboundControlIngress = {
    if (_outboundControlIngress ne null)
      _outboundControlIngress
    else {
      // materialization not completed yet
      materializing.await(10, TimeUnit.SECONDS)
      if (_outboundControlIngress eq null)
        throw new IllegalStateException("outboundControlIngress not initialized yet")
      _outboundControlIngress
    }
  }

  override def localAddress: UniqueAddress = transport.localAddress

  /**
   * Holds reference to shared state of Association - *access only via helper methods*
   */
  @volatile
  private[this] var _sharedStateDoNotCallMeDirectly: AssociationState = AssociationState()

  /**
   * Helper method for access to underlying state via Unsafe
   *
   * @param oldState Previous state
   * @param newState Next state on transition
   * @return Whether the previous state matched correctly
   */
  @inline
  private[this] def swapState(oldState: AssociationState, newState: AssociationState): Boolean =
    Unsafe.instance.compareAndSwapObject(this, AbstractAssociation.sharedStateOffset, oldState, newState)

  /**
   * @return Reference to current shared state
   */
  def associationState: AssociationState =
    Unsafe.instance.getObjectVolatile(this, AbstractAssociation.sharedStateOffset).asInstanceOf[AssociationState]

  override def completeHandshake(peer: UniqueAddress): Unit = {
    require(remoteAddress == peer.address,
      s"wrong remote address in completeHandshake, got ${peer.address}, expected ${remoteAddress}")
    val current = associationState
    current.uniqueRemoteAddressPromise.trySuccess(peer)
    current.uniqueRemoteAddressValue() match {
      case Some(Success(`peer`)) ⇒ // our value
      case _ ⇒
        val newState = current.newIncarnation(Promise.successful(peer))
        if (swapState(current, newState)) {
          current.uniqueRemoteAddressValue() match {
            case Some(Success(old)) ⇒
              log.debug("Incarnation {} of association to [{}] with new UID [{}] (old UID [{}])",
                newState.incarnation, peer.address, peer.uid, old.uid)
            case _ ⇒
            // Failed, nothing to do
          }
          // if swap failed someone else completed before us, and that is fine
        }
    }
  }

  // OutboundContext
  override def sendControl(message: ControlMessage): Unit =
    outboundControlIngress.sendControlMessage(message)

  def send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef): Unit = {
    // allow ActorSelectionMessage to pass through quarantine, to be able to establish interaction with new system
    // FIXME where is that ActorSelectionMessage check in old remoting?
    if (message.isInstanceOf[ActorSelectionMessage] || !associationState.isQuarantined() || message == ClearSystemMessageDelivery) {
      // FIXME: Use a different envelope than the old Send, but make sure the new is handled by deadLetters properly
      message match {
        case _: SystemMessage | ClearSystemMessageDelivery ⇒
          implicit val ec = materializer.executionContext
          controlQueue.offer(Send(message, senderOption, recipient, None)).onFailure {
            case e ⇒
              quarantine(reason = s"Due to overflow of control queue, size [$controlQueueSize]")
          }
        case _: PriorityMessage | ActorSelectionMessage(_: PriorityMessage, _, _) ⇒
          // bypass the system message delivery and such
          sendControl(OutboundControlJunction.Wrapped(Send(message, senderOption, recipient, None)))
        case _ ⇒
          queue.offer(Send(message, senderOption, recipient, None))
      }
    } else if (log.isDebugEnabled)
      log.debug("Dropping message to quarantined system {}", remoteAddress)
  }

  // FIXME we should be able to Send without a recipient ActorRef
  override val dummyRecipient: RemoteActorRef =
    transport.provider.resolveActorRef(RootActorPath(remoteAddress) / "system" / "dummy").asInstanceOf[RemoteActorRef]

  // OutboundContext
  override def quarantine(reason: String): Unit = {
    val uid = associationState.uniqueRemoteAddressValue() match {
      case Some(Success(a)) ⇒ Some(a.uid)
      case _                ⇒ None
    }
    quarantine(reason, uid)
  }

  @tailrec final def quarantine(reason: String, uid: Option[Int]): Unit = {
    uid match {
      case Some(u) ⇒
        val current = associationState
        current.uniqueRemoteAddressValue() match {
          case Some(Success(peer)) if peer.uid == u ⇒
            if (!current.isQuarantined(u)) {
              val newState = current.newQuarantined()
              if (swapState(current, newState)) {
                // quarantine state change was performed
                log.warning("Association to [{}] with UID [{}] is irrecoverably failed. Quarantining address. {}",
                  remoteAddress, u, reason)
                // end delivery of system messages to that incarnation after this point
                send(ClearSystemMessageDelivery, None, dummyRecipient)
                // try to tell the other system that we have quarantined it
                sendControl(Quarantined(localAddress, peer))
              } else
                quarantine(reason, uid) // recursive
            }
          case Some(Success(peer)) ⇒
            log.debug("Quarantine of [{}] ignored due to non-matching UID, quarantine requested for [{}] but current is [{}]. {}",
              remoteAddress, u, peer.uid, reason)
          case None ⇒
            log.debug("Quarantine of [{}] ignored because handshake not completed, quarantine request was for old incarnation. {}",
              remoteAddress, reason)
        }
      case None ⇒
        // FIXME should we do something more, old impl used gating?
        log.warning("Quarantine of [{}] ignored because unknown UID", remoteAddress)
    }

  }

  // Idempotent
  def associate(): Unit = {
    if (controlQueue eq null) {
      // it's important to materialize the outboundControl stream first,
      // so that outboundControlIngress is ready when stages for all streams start
      runOutboundControlStream()
      runOutboundOrdinaryMessagesStream()
    }
  }

  private def runOutboundControlStream(): Unit = {
    // stage in the control stream may access the outboundControlIngress before returned here
    // using CountDownLatch to make sure that materialization is completed before accessing outboundControlIngress
    materializing = new CountDownLatch(1)
    val (q, (control, completed)) = Source.queue(controlQueueSize, OverflowStrategy.backpressure)
      .toMat(transport.outboundControl(this))(Keep.both)
      .run()(materializer)
    controlQueue = q
    _outboundControlIngress = control
    materializing.countDown()
    attachStreamRestart("Outbound control stream", completed, cause ⇒ {
      runOutboundControlStream()
      cause match {
        case _: HandshakeTimeoutException ⇒ // ok, quarantine not possible without UID
        case _                            ⇒ quarantine("Outbound control stream restarted")
      }
    })
  }

  private def runOutboundOrdinaryMessagesStream(): Unit = {
    val (q, completed) = Source.queue(256, OverflowStrategy.dropBuffer)
      .toMat(transport.outbound(this))(Keep.both)
      .run()(materializer)
    queue = q
    attachStreamRestart("Outbound message stream", completed, _ ⇒ runOutboundOrdinaryMessagesStream())
  }

  private def attachStreamRestart(streamName: String, streamCompleted: Future[Done], restart: Throwable ⇒ Unit): Unit = {
    implicit val ec = materializer.executionContext
    streamCompleted.onFailure {
      case _: AbruptTerminationException ⇒ // ActorSystem shutdown
      case cause ⇒
        if (!transport.isShutdown)
          if (restartCounter.restart()) {
            log.error(cause, "{} failed. Restarting it.", streamName)
            restart(cause)
          } else {
            log.error(cause, "{} failed and restarted {} times within {} seconds. Terminating system.",
              streamName, maxRestarts, restartTimeout.toSeconds)
            transport.system.terminate()
          }
    }
  }
}
