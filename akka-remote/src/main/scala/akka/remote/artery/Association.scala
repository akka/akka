/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.Queue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration

import akka.{ Done, NotUsed }
import akka.actor.ActorRef
import akka.actor.ActorSelectionMessage
import akka.actor.Address
import akka.dispatch.sysmsg.SystemMessage
import akka.event.Logging
import akka.remote._
import akka.remote.DaemonMsgCreate
import akka.remote.QuarantinedEvent
import akka.remote.artery.AeronSink.GaveUpSendingException
import akka.remote.artery.Encoder.ChangeOutboundCompression
import akka.remote.artery.Encoder.ChangeOutboundCompressionFailed
import akka.remote.artery.InboundControlJunction.ControlMessageSubject
import akka.remote.artery.OutboundControlJunction.OutboundControlIngress
import akka.remote.artery.OutboundHandshake.HandshakeTimeoutException
import akka.remote.artery.SendQueue.ProducerApi
import akka.remote.artery.SystemMessageDelivery.ClearSystemMessageDelivery
import akka.remote.artery.compress.CompressionProtocol._
import akka.remote.artery.compress.CompressionTable
import akka.stream.AbruptTerminationException
import akka.stream.Materializer
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Source
import akka.util.{ Unsafe, WildcardIndex }
import akka.util.OptionVal
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue
import akka.remote.artery.compress.CompressionProtocol._

/**
 * INTERNAL API
 */
private[remote] object Association {
  final case class QueueWrapper(queue: Queue[OutboundEnvelope]) extends SendQueue.ProducerApi[OutboundEnvelope] {
    override def offer(message: OutboundEnvelope): Boolean = queue.offer(message)
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
  largeMessageDestinations:    WildcardIndex[NotUsed],
  priorityMessageDestinations: WildcardIndex[NotUsed],
  outboundEnvelopePool:        ObjectPool[ReusableOutboundEnvelope])
  extends AbstractAssociation with OutboundContext {
  import Association._

  private val log = Logging(transport.system, getClass.getName)
  private val controlQueueSize = transport.settings.Advanced.SysMsgBufferSize
  // FIXME config queue size, and it should perhaps also be possible to use some kind of LinkedQueue
  //       such as agrona.ManyToOneConcurrentLinkedQueue or AbstractNodeQueue for less memory consumption
  private val queueSize = 3072
  private val largeQueueSize = 256

  private val restartCounter = new RestartCounter(transport.settings.Advanced.OutboundMaxRestarts, transport.settings.Advanced.OutboundRestartTimeout)

  // We start with the raw wrapped queue and then it is replaced with the materialized value of
  // the `SendQueue` after materialization. Using same underlying queue. This makes it possible to
  // start sending (enqueuing) to the Association immediate after construction.

  def createQueue(capacity: Int): Queue[OutboundEnvelope] =
    new ManyToOneConcurrentArrayQueue[OutboundEnvelope](capacity)

  @volatile private[this] var queue: SendQueue.ProducerApi[OutboundEnvelope] = QueueWrapper(createQueue(queueSize))
  @volatile private[this] var largeQueue: SendQueue.ProducerApi[OutboundEnvelope] = QueueWrapper(createQueue(largeQueueSize))
  @volatile private[this] var controlQueue: SendQueue.ProducerApi[OutboundEnvelope] = QueueWrapper(createQueue(controlQueueSize))
  @volatile private[this] var _outboundControlIngress: OutboundControlIngress = _
  @volatile private[this] var materializing = new CountDownLatch(1)
  @volatile private[this] var changeOutboundCompression: Option[ChangeOutboundCompression] = None

  def changeActorRefCompression(table: CompressionTable[ActorRef]): Future[Done] =
    changeOutboundCompression match {
      case Some(c) ⇒ c.changeActorRefCompression(table)
      case None    ⇒ Future.failed(new ChangeOutboundCompressionFailed)
    }

  def changeClassManifestCompression(table: CompressionTable[String]): Future[Done] =
    changeOutboundCompression match {
      case Some(c) ⇒ c.changeClassManifestCompression(table)
      case None    ⇒ Future.failed(new ChangeOutboundCompressionFailed)
    }

  def clearCompression(): Future[Done] =
    changeOutboundCompression match {
      case Some(c) ⇒ c.clearCompression()
      case None    ⇒ Future.failed(new ChangeOutboundCompressionFailed)
    }

  private val _testStages: CopyOnWriteArrayList[TestManagementApi] = new CopyOnWriteArrayList

  def testStages(): List[TestManagementApi] = {
    import scala.collection.JavaConverters._
    _testStages.asScala.toList
  }

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

  def completeHandshake(peer: UniqueAddress): Future[Done] = {
    require(
      remoteAddress == peer.address,
      s"wrong remote address in completeHandshake, got ${peer.address}, expected $remoteAddress")
    val current = associationState

    current.uniqueRemoteAddressValue() match {
      case Some(`peer`) ⇒
        // handshake already completed
        Future.successful(Done)
      case _ ⇒
        // clear outbound compression, it's safe to do that several times if someone else
        // completes handshake at same time, but it's important to clear it before
        // we signal that the handshake is completed (uniqueRemoteAddressPromise.trySuccess)
        import transport.system.dispatcher
        clearCompression().map { _ ⇒
          current.uniqueRemoteAddressPromise.trySuccess(peer)
          current.uniqueRemoteAddressValue() match {
            case Some(`peer`) ⇒
            // our value
            case _ ⇒
              val newState = current.newIncarnation(Promise.successful(peer))
              if (swapState(current, newState)) {
                current.uniqueRemoteAddressValue() match {
                  case Some(old) ⇒
                    log.debug(
                      "Incarnation {} of association to [{}] with new UID [{}] (old UID [{}])",
                      newState.incarnation, peer.address, peer.uid, old.uid)
                  case None ⇒
                  // Failed, nothing to do
                }
                // if swap failed someone else completed before us, and that is fine
              }
          }
          Done
        }
    }
  }

  // OutboundContext
  override def sendControl(message: ControlMessage): Unit =
    outboundControlIngress.sendControlMessage(message)

  def send(message: Any, sender: OptionVal[ActorRef], recipient: OptionVal[RemoteActorRef]): Unit = {
    def createOutboundEnvelope(): OutboundEnvelope =
      outboundEnvelopePool.acquire().init(recipient, message.asInstanceOf[AnyRef], sender)

    // allow ActorSelectionMessage to pass through quarantine, to be able to establish interaction with new system
    // FIXME where is that ActorSelectionMessage check in old remoting?
    if (message.isInstanceOf[ActorSelectionMessage] || !associationState.isQuarantined() || message == ClearSystemMessageDelivery) {
      message match {
        case _: SystemMessage | ClearSystemMessageDelivery | _: ControlMessage ⇒
          val outboundEnvelope = createOutboundEnvelope()
          if (!controlQueue.offer(createOutboundEnvelope())) {
            quarantine(reason = s"Due to overflow of control queue, size [$controlQueueSize]")
            transport.system.deadLetters ! outboundEnvelope
          }
        case _: DaemonMsgCreate ⇒
          // DaemonMsgCreate is not a SystemMessage, but must be sent over the control stream because
          // remote deployment process depends on message ordering for DaemonMsgCreate and Watch messages.
          // It must also be sent over the ordinary message stream so that it arrives (and creates the
          // destination) before the first ordinary message arrives.
          val outboundEnvelope1 = createOutboundEnvelope()
          if (!controlQueue.offer(outboundEnvelope1))
            transport.system.deadLetters ! outboundEnvelope1
          val outboundEnvelope2 = createOutboundEnvelope()
          if (!queue.offer(outboundEnvelope2))
            transport.system.deadLetters ! outboundEnvelope2
        case _ ⇒
          val outboundEnvelope = createOutboundEnvelope()
          val queue = selectQueue(recipient)
          val offerOk = queue.offer(outboundEnvelope)
          if (!offerOk)
            transport.system.deadLetters ! outboundEnvelope
      }
    } else if (log.isDebugEnabled)
      log.debug("Dropping message to quarantined system {}", remoteAddress)
  }

  @tailrec
  private def selectQueue(recipient: OptionVal[RemoteActorRef]): ProducerApi[OutboundEnvelope] = {
    recipient match {
      case OptionVal.Some(r) ⇒
        r.cachedMessageDestinationFlag match {
          case RegularDestination  ⇒ queue
          case PriorityDestination ⇒ controlQueue
          case LargeDestination    ⇒ largeQueue
          case null ⇒
            // only happens when messages are sent to new remote destination
            // and is then cached on the RemoteActorRef
            val elements = r.path.elements
            if (priorityMessageDestinations.find(elements).isDefined) {
              log.debug("Using priority message stream for {}", r.path)
              r.cachedMessageDestinationFlag = PriorityDestination
            } else if (transport.largeMessageChannelEnabled && largeMessageDestinations.find(elements).isDefined) {
              log.debug("Using large message stream for {}", r.path)
              r.cachedMessageDestinationFlag = LargeDestination
            } else {
              r.cachedMessageDestinationFlag = RegularDestination
            }
            selectQueue(recipient)
        }
      case OptionVal.None ⇒ queue
    }
  }

  // OutboundContext
  override def quarantine(reason: String): Unit = {
    val uid = associationState.uniqueRemoteAddressValue().map(_.uid)
    quarantine(reason, uid)
  }

  @tailrec final def quarantine(reason: String, uid: Option[Long]): Unit = {
    uid match {
      case Some(u) ⇒
        val current = associationState
        current.uniqueRemoteAddressValue() match {
          case Some(peer) if peer.uid == u ⇒
            if (!current.isQuarantined(u)) {
              val newState = current.newQuarantined()
              if (swapState(current, newState)) {
                // quarantine state change was performed
                log.warning(
                  "Association to [{}] with UID [{}] is irrecoverably failed. Quarantining address. {}",
                  remoteAddress, u, reason)
                // clear outbound compression
                clearCompression()
                // FIXME when we complete the switch to Long UID we must use Long here also, issue #20644
                transport.eventPublisher.notifyListeners(QuarantinedEvent(remoteAddress, u.toInt))
                // end delivery of system messages to that incarnation after this point
                send(ClearSystemMessageDelivery, OptionVal.None, OptionVal.None)
                // try to tell the other system that we have quarantined it
                sendControl(Quarantined(localAddress, peer))
              } else
                quarantine(reason, uid) // recursive
            }
          case Some(peer) ⇒
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
    runOutboundStreams()
  }

  private def runOutboundStreams(): Unit = {
    // it's important to materialize the outboundControl stream first,
    // so that outboundControlIngress is ready when stages for all streams start
    runOutboundControlStream()
    runOutboundOrdinaryMessagesStream()

    if (transport.largeMessageChannelEnabled)
      runOutboundLargeMessagesStream()
  }

  private def runOutboundControlStream(): Unit = {
    // stage in the control stream may access the outboundControlIngress before returned here
    // using CountDownLatch to make sure that materialization is completed before accessing outboundControlIngress
    materializing = new CountDownLatch(1)

    val wrapper = getOrCreateQueueWrapper(controlQueue, queueSize)
    controlQueue = wrapper // use new underlying queue immediately for restarts

    val (queueValue, (control, completed)) =
      if (transport.settings.Advanced.TestMode) {
        val ((queueValue, mgmt), (control, completed)) =
          Source.fromGraph(new SendQueue[OutboundEnvelope])
            .via(transport.outboundControlPart1(this))
            .viaMat(transport.outboundTestFlow(this))(Keep.both)
            .toMat(transport.outboundControlPart2(this))(Keep.both)
            .run()(materializer)
        _testStages.add(mgmt)
        (queueValue, (control, completed))
      } else {
        Source.fromGraph(new SendQueue[OutboundEnvelope])
          .via(transport.outboundControlPart1(this))
          .toMat(transport.outboundControlPart2(this))(Keep.both)
          .run()(materializer)
      }

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

  private def getOrCreateQueueWrapper(q: SendQueue.ProducerApi[OutboundEnvelope], capacity: Int): QueueWrapper =
    q match {
      case existing: QueueWrapper ⇒ existing
      case _ ⇒
        // use new queue for restarts
        QueueWrapper(createQueue(capacity))
    }

  private def runOutboundOrdinaryMessagesStream(): Unit = {
    val wrapper = getOrCreateQueueWrapper(queue, queueSize)
    queue = wrapper // use new underlying queue immediately for restarts

    val (queueValue, (changeCompression, completed)) =
      if (transport.settings.Advanced.TestMode) {
        val ((queueValue, mgmt), completed) = Source.fromGraph(new SendQueue[OutboundEnvelope])
          .viaMat(transport.outboundTestFlow(this))(Keep.both)
          .toMat(transport.outbound(this))(Keep.both)
          .run()(materializer)
        _testStages.add(mgmt)
        (queueValue, completed)
      } else {
        Source.fromGraph(new SendQueue[OutboundEnvelope])
          .toMat(transport.outbound(this))(Keep.both)
          .run()(materializer)
      }

    queueValue.inject(wrapper.queue)
    // replace with the materialized value, still same underlying queue
    queue = queueValue
    changeOutboundCompression = Some(changeCompression)

    attachStreamRestart("Outbound message stream", completed, _ ⇒ runOutboundOrdinaryMessagesStream())
  }

  private def runOutboundLargeMessagesStream(): Unit = {
    val wrapper = getOrCreateQueueWrapper(largeQueue, largeQueueSize)
    largeQueue = wrapper // use new underlying queue immediately for restarts

    val (queueValue, completed) =
      if (transport.settings.Advanced.TestMode) {
        val ((queueValue, mgmt), completed) = Source.fromGraph(new SendQueue[OutboundEnvelope])
          .viaMat(transport.outboundTestFlow(this))(Keep.both)
          .toMat(transport.outboundLarge(this))(Keep.both)
          .run()(materializer)
        _testStages.add(mgmt)
        (queueValue, completed)
      } else {
        Source.fromGraph(new SendQueue[OutboundEnvelope])
          .toMat(transport.outboundLarge(this))(Keep.both)
          .run()(materializer)
      }

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
            streamName, remoteAddress, transport.settings.Advanced.OutboundMaxRestarts, transport.settings.Advanced.OutboundRestartTimeout.toSeconds)
          transport.system.terminate()
        }
    }
  }

  override def toString: String =
    s"Association($localAddress -> $remoteAddress with $associationState)"

}

/**
 * INTERNAL API
 */
private[remote] class AssociationRegistry(createAssociation: Address ⇒ Association) {
  private[this] val associationsByAddress = new AtomicReference[Map[Address, Association]](Map.empty)
  private[this] val associationsByUid = new AtomicReference[ImmutableLongMap[Association]](ImmutableLongMap.empty)

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

  def association(uid: Long): OptionVal[Association] =
    associationsByUid.get.get(uid)

  @tailrec final def setUID(peer: UniqueAddress): Association = {
    val currentMap = associationsByUid.get
    val a = association(peer.address)

    currentMap.get(peer.uid) match {
      case OptionVal.Some(previous) ⇒
        if (previous eq a)
          // associationsByUid Map already contains the right association
          a
        else
          // make sure we don't overwrite same UID with different association
          throw new IllegalArgumentException(s"UID collision old [$previous] new [$a]")
      case _ ⇒
        // update associationsByUid Map with the uid -> assocation
        val newMap = currentMap.updated(peer.uid, a)
        if (associationsByUid.compareAndSet(currentMap, newMap))
          a
        else
          setUID(peer) // lost CAS, retry
    }
  }

  def allAssociations: Set[Association] =
    associationsByAddress.get.values.toSet
}
