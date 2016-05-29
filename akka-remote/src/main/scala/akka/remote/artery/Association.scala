/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.Queue

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.util.Success
import akka.{ Done, NotUsed }
import akka.actor.ActorRef
import akka.actor.ActorSelectionMessage
import akka.actor.Address
import akka.actor.RootActorPath
import akka.dispatch.sysmsg.SystemMessage
import akka.event.Logging
import akka.remote.{ LargeDestination, RegularDestination, RemoteActorRef, UniqueAddress }
import akka.remote.EndpointManager.Send
import akka.remote.artery.AeronSink.GaveUpSendingException
import akka.remote.artery.InboundControlJunction.ControlMessageSubject
import akka.remote.artery.OutboundControlJunction.OutboundControlIngress
import akka.remote.artery.OutboundHandshake.HandshakeTimeoutException
import akka.remote.artery.SystemMessageDelivery.ClearSystemMessageDelivery
import akka.stream.AbruptTerminationException
import akka.stream.Materializer
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Source
import akka.util.{ Unsafe, WildcardTree }
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue

/**
 * INTERNAL API
 */
private[remote] object Association {
  final case class QueueWrapper(queue: Queue[Send]) extends SendQueue.ProducerApi[Send] {
    override def offer(message: Send): Boolean = queue.offer(message)
  }
}

/**
 * INTERNAL API
 *
 * Thread-safe, mutable holder for association state. Main entry point for remote destined message to a specific
 * remote address.
 */
private[remote] class Association(
  val transport:               ArteryTransport,
  val materializer:            Materializer,
  override val remoteAddress:  Address,
  override val controlSubject: ControlMessageSubject,
  largeMessageDestinations:    WildcardTree[NotUsed])
  extends AbstractAssociation with OutboundContext {
  import Association._

  private val log = Logging(transport.system, getClass.getName)
  private val controlQueueSize = transport.provider.remoteSettings.SysMsgBufferSize
  // FIXME config queue size, and it should perhaps also be possible to use some kind of LinkedQueue
  //       such as agrona.ManyToOneConcurrentLinkedQueue or AbstractNodeQueue for less memory consumption
  private val queueSize = 3072
  private val largeQueueSize = 256

  private val restartTimeout: FiniteDuration = 5.seconds // FIXME config
  private val maxRestarts = 5 // FIXME config
  private val restartCounter = new RestartCounter(maxRestarts, restartTimeout)
  private val largeMessageChannelEnabled = largeMessageDestinations.children.nonEmpty

  // We start with the raw wrapped queue and then it is replaced with the materialized value of
  // the `SendQueue` after materialization. Using same underlying queue. This makes it possible to
  // start sending (enqueuing) to the Association immediate after construction.

  def createQueue(capacity: Int): Queue[Send] =
    new ManyToOneConcurrentArrayQueue[Send](capacity)

  @volatile private[this] var queue: SendQueue.ProducerApi[Send] = QueueWrapper(createQueue(queueSize))
  @volatile private[this] var largeQueue: SendQueue.ProducerApi[Send] = QueueWrapper(createQueue(largeQueueSize))
  @volatile private[this] var controlQueue: SendQueue.ProducerApi[Send] = QueueWrapper(createQueue(controlQueueSize))
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

  def completeHandshake(peer: UniqueAddress): Unit = {
    require(
      remoteAddress == peer.address,
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
              log.debug(
                "Incarnation {} of association to [{}] with new UID [{}] (old UID [{}])",
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
          val send = Send(message, senderOption, recipient, None)
          if (!controlQueue.offer(send)) {
            quarantine(reason = s"Due to overflow of control queue, size [$controlQueueSize]")
            transport.system.deadLetters ! send
          }
        case _ ⇒
          val send = Send(message, senderOption, recipient, None)
          val offerOk =
            if (largeMessageChannelEnabled && isLargeMessageDestination(recipient))
              largeQueue.offer(send)
            else
              queue.offer(send)
          if (!offerOk)
            transport.system.deadLetters ! send
      }
    } else if (log.isDebugEnabled)
      log.debug("Dropping message to quarantined system {}", remoteAddress)
  }

  private def isLargeMessageDestination(recipient: ActorRef): Boolean = {
    recipient match {
      case r: RemoteActorRef if r.cachedLargeMessageDestinationFlag ne null ⇒ r.cachedLargeMessageDestinationFlag eq LargeDestination
      case r: RemoteActorRef ⇒
        if (largeMessageDestinations.find(r.path.elements.iterator).data.isEmpty) {
          r.cachedLargeMessageDestinationFlag = RegularDestination
          false
        } else {
          log.debug("Using large message stream for {}", r.path)
          r.cachedLargeMessageDestinationFlag = LargeDestination
          true
        }
      case _ ⇒ false
    }
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

  @tailrec final def quarantine(reason: String, uid: Option[Long]): Unit = {
    uid match {
      case Some(u) ⇒
        val current = associationState
        current.uniqueRemoteAddressValue() match {
          case Some(Success(peer)) if peer.uid == u ⇒
            if (!current.isQuarantined(u)) {
              val newState = current.newQuarantined()
              if (swapState(current, newState)) {
                // quarantine state change was performed
                log.warning(
                  "Association to [{}] with UID [{}] is irrecoverably failed. Quarantining address. {}",
                  remoteAddress, u, reason)
                // end delivery of system messages to that incarnation after this point
                send(ClearSystemMessageDelivery, None, dummyRecipient)
                // try to tell the other system that we have quarantined it
                sendControl(Quarantined(localAddress, peer))
              } else
                quarantine(reason, uid) // recursive
            }
          case Some(Success(peer)) ⇒
            log.debug(
              "Quarantine of [{}] ignored due to non-matching UID, quarantine requested for [{}] but current is [{}]. {}",
              remoteAddress, u, peer.uid, reason)
          case None ⇒
            log.debug(
              "Quarantine of [{}] ignored because handshake not completed, quarantine request was for old incarnation. {}",
              remoteAddress, reason)
        }
      case None ⇒
        // FIXME should we do something more, old impl used gating?
        log.warning("Quarantine of [{}] ignored because unknown UID", remoteAddress)
    }

  }

  /**
   * Called once after construction when the `Association` instance
   * wins the CAS in the `AssociationRegistry`. It will materialize
   * the streams. It is possible to sending (enqueuing) to the association
   * before this method is called.
   */
  def associate(): Unit = {
    if (!controlQueue.isInstanceOf[QueueWrapper])
      throw new IllegalStateException("associate() must only be called once")

    // it's important to materialize the outboundControl stream first,
    // so that outboundControlIngress is ready when stages for all streams start
    runOutboundControlStream()
    runOutboundOrdinaryMessagesStream()

    if (largeMessageChannelEnabled) {
      runOutboundLargeMessagesStream()
    }
  }

  private def runOutboundControlStream(): Unit = {
    // stage in the control stream may access the outboundControlIngress before returned here
    // using CountDownLatch to make sure that materialization is completed before accessing outboundControlIngress
    materializing = new CountDownLatch(1)

    val wrapper = getOrCreateQueueWrapper(controlQueue, queueSize)
    controlQueue = wrapper // use new underlying queue immediately for restarts
    val (queueValue, (control, completed)) = Source.fromGraph(new SendQueue[Send])
      .toMat(transport.outboundControl(this))(Keep.both)
      .run()(materializer)
    queueValue.inject(wrapper.queue)
    // replace with the materialized value, still same underlying queue
    controlQueue = queueValue
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

  private def getOrCreateQueueWrapper(q: SendQueue.ProducerApi[Send], capacity: Int): QueueWrapper =
    q match {
      case existing: QueueWrapper ⇒ existing
      case _ ⇒
        // use new queue for restarts
        QueueWrapper(createQueue(capacity))
    }

  private def runOutboundOrdinaryMessagesStream(): Unit = {
    val wrapper = getOrCreateQueueWrapper(queue, queueSize)
    queue = wrapper // use new underlying queue immediately for restarts
    val (queueValue, completed) = Source.fromGraph(new SendQueue[Send])
      .toMat(transport.outbound(this))(Keep.both)
      .run()(materializer)
    queueValue.inject(wrapper.queue)
    // replace with the materialized value, still same underlying queue
    queue = queueValue
    attachStreamRestart("Outbound message stream", completed, _ ⇒ runOutboundOrdinaryMessagesStream())
  }

  private def runOutboundLargeMessagesStream(): Unit = {
    val wrapper = getOrCreateQueueWrapper(queue, largeQueueSize)
    largeQueue = wrapper // use new underlying queue immediately for restarts
    val (queueValue, completed) = Source.fromGraph(new SendQueue[Send])
      .toMat(transport.outboundLarge(this))(Keep.both)
      .run()(materializer)
    queueValue.inject(wrapper.queue)
    // replace with the materialized value, still same underlying queue
    largeQueue = queueValue
    attachStreamRestart("Outbound large message stream", completed, _ ⇒ runOutboundLargeMessagesStream())
  }

  private def attachStreamRestart(streamName: String, streamCompleted: Future[Done], restart: Throwable ⇒ Unit): Unit = {
    implicit val ec = materializer.executionContext
    streamCompleted.onFailure {
      case _ if transport.isShutdown     ⇒ // don't restart after shutdown
      case _: AbruptTerminationException ⇒ // ActorSystem shutdown
      case cause: GaveUpSendingException ⇒
        log.debug("{} to {} failed. Restarting it. {}", streamName, remoteAddress, cause.getMessage)
        // restart unconditionally, without counting restarts
        restart(cause)
      case cause ⇒
        if (restartCounter.restart()) {
          log.error(cause, "{} to {} failed. Restarting it. {}", streamName, remoteAddress, cause.getMessage)
          restart(cause)
        } else {
          log.error(cause, s"{} to {} failed and restarted {} times within {} seconds. Terminating system. ${cause.getMessage}",
            streamName, remoteAddress, maxRestarts, restartTimeout.toSeconds)
          transport.system.terminate()
        }
    }
  }

  override def toString(): String =
    s"Association($localAddress -> $remoteAddress with $associationState)"

}

/**
 * INTERNAL API
 */
private[remote] class AssociationRegistry(createAssociation: Address ⇒ Association) {
  private[this] val associationsByAddress = new AtomicReference[Map[Address, Association]](Map.empty)
  private[this] val associationsByUid = new ConcurrentHashMap[Long, Association]() // FIXME replace with specialized Long Map

  @tailrec final def association(remoteAddress: Address): Association = {
    val currentMap = associationsByAddress.get
    currentMap.get(remoteAddress) match {
      case Some(existing) ⇒ existing
      case None ⇒
        val newAssociation = createAssociation(remoteAddress)
        val newMap = currentMap.updated(remoteAddress, newAssociation)
        if (associationsByAddress.compareAndSet(currentMap, newMap)) {
          newAssociation.associate() // start it, only once
          newAssociation
        } else
          association(remoteAddress) // lost CAS, retry
    }
  }

  def association(uid: Long): Association =
    associationsByUid.get(uid)

  def setUID(peer: UniqueAddress): Association = {
    val a = association(peer.address)
    val previous = associationsByUid.put(peer.uid, a)
    if ((previous ne null) && (previous ne a))
      throw new IllegalArgumentException(s"UID collision old [$previous] new [$a]")
    a
  }
}
