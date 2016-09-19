/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.Queue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
import akka.pattern.after
import akka.remote._
import akka.remote.DaemonMsgCreate
import akka.remote.QuarantinedEvent
import akka.remote.artery.AeronSink.GaveUpMessageException
import akka.remote.artery.ArteryTransport.AeronTerminated
import akka.remote.artery.Encoder.ChangeOutboundCompression
import akka.remote.artery.Encoder.ChangeOutboundCompressionFailed
import akka.remote.artery.InboundControlJunction.ControlMessageSubject
import akka.remote.artery.OutboundControlJunction.OutboundControlIngress
import akka.remote.artery.OutboundHandshake.HandshakeTimeoutException
import akka.remote.artery.SystemMessageDelivery.ClearSystemMessageDelivery
import akka.remote.artery.compress.CompressionProtocol._
import akka.remote.artery.compress.CompressionTable
import akka.stream.AbruptTerminationException
import akka.stream.KillSwitches
import akka.stream.Materializer
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.MergeHub
import akka.stream.scaladsl.Source
import akka.util.{ Unsafe, WildcardIndex }
import akka.util.OptionVal
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue

/**
 * INTERNAL API
 */
private[remote] object Association {
  sealed trait QueueWrapper extends SendQueue.ProducerApi[OutboundEnvelope] {
    def queue: Queue[OutboundEnvelope]
  }

  final case class QueueWrapperImpl(queue: Queue[OutboundEnvelope]) extends QueueWrapper {
    override def offer(message: OutboundEnvelope): Boolean = queue.offer(message)
  }

  final case class LazyQueueWrapper(queue: Queue[OutboundEnvelope], materialize: () ⇒ Unit) extends QueueWrapper {
    private val onlyOnce = new AtomicBoolean

    def runMaterialize(): Unit = {
      if (onlyOnce.compareAndSet(false, true))
        materialize()
    }

    override def offer(message: OutboundEnvelope): Boolean = {
      runMaterialize()
      queue.offer(message)
    }
  }

  final val ControlQueueIndex = 0
  final val LargeQueueIndex = 1
  final val OrdinaryQueueIndex = 2
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
  import FlightRecorderEvents._

  private val log = Logging(transport.system, getClass.getName)
  private val flightRecorder = transport.createFlightRecorderEventSink(synchr = true)

  override def settings = transport.settings
  private def advancedSettings = transport.settings.Advanced

  private val restartCounter = new RestartCounter(advancedSettings.OutboundMaxRestarts, advancedSettings.OutboundRestartTimeout)

  // We start with the raw wrapped queue and then it is replaced with the materialized value of
  // the `SendQueue` after materialization. Using same underlying queue. This makes it possible to
  // start sending (enqueuing) to the Association immediate after construction.

  def createQueue(capacity: Int): Queue[OutboundEnvelope] =
    new ManyToOneConcurrentArrayQueue[OutboundEnvelope](capacity)

  private val outboundLanes = advancedSettings.OutboundLanes
  private val controlQueueSize = advancedSettings.OutboundControlQueueSize
  private val queueSize = advancedSettings.OutboundMessageQueueSize
  private val largeQueueSize = advancedSettings.OutboundLargeMessageQueueSize

  private[this] val queues: Array[SendQueue.ProducerApi[OutboundEnvelope]] = Array.ofDim(2 + outboundLanes)
  queues(ControlQueueIndex) = QueueWrapperImpl(createQueue(controlQueueSize)) // control stream
  queues(LargeQueueIndex) = QueueWrapperImpl(createQueue(largeQueueSize)) // large messages stream
  (0 until outboundLanes).foreach { i ⇒
    queues(OrdinaryQueueIndex + i) = QueueWrapperImpl(createQueue(queueSize)) // ordinary messages stream
  }
  @volatile private[this] var queuesVisibility = false

  private def controlQueue: SendQueue.ProducerApi[OutboundEnvelope] = queues(ControlQueueIndex)
  private def largeQueue: SendQueue.ProducerApi[OutboundEnvelope] = queues(LargeQueueIndex)

  @volatile private[this] var _outboundControlIngress: OptionVal[OutboundControlIngress] = OptionVal.None
  @volatile private[this] var materializing = new CountDownLatch(1)
  @volatile private[this] var changeOutboundCompression: Vector[ChangeOutboundCompression] = Vector.empty
  // in case there is a restart at the same time as a compression table update
  private val changeCompressionTimeout = 5.seconds

  private[artery] def changeActorRefCompression(table: CompressionTable[ActorRef]): Future[Done] = {
    import transport.system.dispatcher
    val c = changeOutboundCompression
    val result =
      if (c.isEmpty) Future.successful(Done)
      else if (c.size == 1) c.head.changeActorRefCompression(table)
      else Future.sequence(c.map(_.changeActorRefCompression(table))).map(_ ⇒ Done)
    timeoutAfter(result, changeCompressionTimeout, new ChangeOutboundCompressionFailed)
  }
  private[this] val streamCompletions = new AtomicReference(Map.empty[String, Future[Done]])

  private[artery] def changeClassManifestCompression(table: CompressionTable[String]): Future[Done] = {
    import transport.system.dispatcher
    val c = changeOutboundCompression
    val result =
      if (c.isEmpty) Future.successful(Done)
      else if (c.size == 1) c.head.changeClassManifestCompression(table)
      else Future.sequence(c.map(_.changeClassManifestCompression(table))).map(_ ⇒ Done)
    timeoutAfter(result, changeCompressionTimeout, new ChangeOutboundCompressionFailed)
  }

  private def clearOutboundCompression(): Future[Done] = {
    import transport.system.dispatcher
    val c = changeOutboundCompression
    val result =
      if (c.isEmpty) Future.successful(Done)
      else if (c.size == 1) c.head.clearCompression()
      else Future.sequence(c.map(_.clearCompression())).map(_ ⇒ Done)
    timeoutAfter(result, changeCompressionTimeout, new ChangeOutboundCompressionFailed)
  }

  private def clearInboundCompression(originUid: Long): Unit =
    transport.inboundCompressions.foreach(_.close(originUid))

  private def timeoutAfter[T](f: Future[T], timeout: FiniteDuration, e: ⇒ Throwable): Future[T] = {
    import transport.system.dispatcher
    val f2 = after(timeout, transport.system.scheduler)(Future.failed(e))
    Future.firstCompletedOf(List(f, f2))
  }

  private def deadletters = transport.system.deadLetters

  def outboundControlIngress: OutboundControlIngress = {
    _outboundControlIngress match {
      case OptionVal.Some(o) ⇒ o
      case OptionVal.None ⇒
        controlQueue match {
          case w: LazyQueueWrapper ⇒ w.runMaterialize()
          case _                   ⇒
        }
        // materialization not completed yet
        materializing.await(10, TimeUnit.SECONDS)
        _outboundControlIngress match {
          case OptionVal.Some(o) ⇒ o
          case OptionVal.None    ⇒ throw new IllegalStateException("outboundControlIngress not initialized yet")
        }
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
        clearOutboundCompression().map { _ ⇒
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
                    clearInboundCompression(old.uid)
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
  override def sendControl(message: ControlMessage): Unit = {
    if (!transport.isShutdown)
      outboundControlIngress.sendControlMessage(message)
  }

  def send(message: Any, sender: OptionVal[ActorRef], recipient: OptionVal[RemoteActorRef]): Unit = {

    def createOutboundEnvelope(): OutboundEnvelope =
      outboundEnvelopePool.acquire().init(recipient, message.asInstanceOf[AnyRef], sender)

    // volatile read to see latest queue array
    val unused = queuesVisibility

    def dropped(queueIndex: Int, qSize: Int, env: OutboundEnvelope): Unit = {
      log.debug(
        "Dropping message [{}] from [{}] to [{}] due to overflow of send queue, size [{}]",
        message.getClass, sender.getOrElse(deadletters), recipient.getOrElse(recipient), qSize)
      flightRecorder.hiFreq(Transport_SendQueueOverflow, queueIndex)
      deadletters ! env
    }

    // allow ActorSelectionMessage to pass through quarantine, to be able to establish interaction with new system
    if (message.isInstanceOf[ActorSelectionMessage] || !associationState.isQuarantined() || message == ClearSystemMessageDelivery) {
      message match {
        case _: SystemMessage ⇒
          val outboundEnvelope = createOutboundEnvelope()
          if (!controlQueue.offer(createOutboundEnvelope())) {
            quarantine(reason = s"Due to overflow of control queue, size [$controlQueueSize]")
            dropped(ControlQueueIndex, controlQueueSize, outboundEnvelope)
          }
        case ActorSelectionMessage(_: PriorityMessage, _, _) | _: ControlMessage | ClearSystemMessageDelivery ⇒
          // ActorSelectionMessage with PriorityMessage is used by cluster and remote failure detector heartbeating
          val outboundEnvelope = createOutboundEnvelope()
          if (!controlQueue.offer(createOutboundEnvelope())) {
            dropped(ControlQueueIndex, controlQueueSize, outboundEnvelope)
          }
        case _: DaemonMsgCreate ⇒
          // DaemonMsgCreate is not a SystemMessage, but must be sent over the control stream because
          // remote deployment process depends on message ordering for DaemonMsgCreate and Watch messages.
          // It must also be sent over the ordinary message stream so that it arrives (and creates the
          // destination) before the first ordinary message arrives.
          val outboundEnvelope1 = createOutboundEnvelope()
          if (!controlQueue.offer(outboundEnvelope1))
            dropped(ControlQueueIndex, controlQueueSize, outboundEnvelope1)
          (0 until outboundLanes).foreach { i ⇒
            val outboundEnvelope2 = createOutboundEnvelope()
            if (!queues(OrdinaryQueueIndex + i).offer(outboundEnvelope2))
              dropped(OrdinaryQueueIndex + i, queueSize, outboundEnvelope2)
          }
        case _ ⇒
          val outboundEnvelope = createOutboundEnvelope()
          val queueIndex = selectQueue(recipient)
          val queue = queues(queueIndex)
          val offerOk = queue.offer(outboundEnvelope)
          if (!offerOk)
            dropped(queueIndex, queueSize, outboundEnvelope)

      }
    } else if (log.isDebugEnabled)
      log.debug(
        "Dropping message [{}] from [{}] to [{}] due to quarantined system [{}]",
        message.getClass, sender.getOrElse(deadletters), recipient.getOrElse(recipient), remoteAddress)
  }

  private def selectQueue(recipient: OptionVal[RemoteActorRef]): Int = {
    recipient match {
      case OptionVal.Some(r) ⇒
        r.cachedSendQueueIndex match {
          case -1 ⇒
            // only happens when messages are sent to new remote destination
            // and is then cached on the RemoteActorRef
            val elements = r.path.elements
            val idx =
              if (priorityMessageDestinations.find(elements).isDefined) {
                log.debug("Using priority message stream for {}", r.path)
                ControlQueueIndex
              } else if (transport.largeMessageChannelEnabled && largeMessageDestinations.find(elements).isDefined) {
                log.debug("Using large message stream for {}", r.path)
                LargeQueueIndex
              } else if (outboundLanes == 1) {
                OrdinaryQueueIndex
              } else {
                // select lane based on destination, to preserve message order
                OrdinaryQueueIndex + (math.abs(r.path.uid) % outboundLanes)
              }
            r.cachedSendQueueIndex = idx
            idx
          case idx ⇒ idx
        }

      case OptionVal.None ⇒
        OrdinaryQueueIndex
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
                clearOutboundCompression()
                clearInboundCompression(u)
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

    val wrapper = getOrCreateQueueWrapper(ControlQueueIndex, queueSize)
    queues(ControlQueueIndex) = wrapper // use new underlying queue immediately for restarts
    queuesVisibility = true // volatile write for visibility of the queues array

    val (queueValue, (control, completed)) =
      Source.fromGraph(new SendQueue[OutboundEnvelope])
        .toMat(transport.outboundControl(this))(Keep.both)
        .run()(materializer)

    queueValue.inject(wrapper.queue)
    // replace with the materialized value, still same underlying queue
    queues(ControlQueueIndex) = queueValue
    queuesVisibility = true // volatile write for visibility of the queues array
    _outboundControlIngress = OptionVal.Some(control)
    materializing.countDown()
    attachStreamRestart("Outbound control stream", ControlQueueIndex, controlQueueSize,
      completed, () ⇒ runOutboundControlStream())
  }

  private def getOrCreateQueueWrapper(queueIndex: Int, capacity: Int): QueueWrapper = {
    val unused = queuesVisibility // volatile read to see latest queues array
    queues(queueIndex) match {
      case existing: QueueWrapper ⇒ existing
      case _ ⇒
        // use new queue for restarts
        QueueWrapperImpl(createQueue(capacity))
    }
  }

  private def runOutboundOrdinaryMessagesStream(): Unit = {
    if (outboundLanes == 1) {
      val queueIndex = OrdinaryQueueIndex
      val wrapper = getOrCreateQueueWrapper(queueIndex, queueSize)
      queues(queueIndex) = wrapper // use new underlying queue immediately for restarts
      queuesVisibility = true // volatile write for visibility of the queues array

      val ((queueValue, testMgmt), (changeCompression, completed)) =
        Source.fromGraph(new SendQueue[OutboundEnvelope])
          .viaMat(transport.outboundTestFlow(this))(Keep.both)
          .toMat(transport.outbound(this))(Keep.both)
          .run()(materializer)

      queueValue.inject(wrapper.queue)
      // replace with the materialized value, still same underlying queue
      queues(queueIndex) = queueValue
      queuesVisibility = true // volatile write for visibility of the queues array
      changeOutboundCompression = Vector(changeCompression)

      attachStreamRestart("Outbound message stream", OrdinaryQueueIndex, queueSize,
        completed, () ⇒ runOutboundOrdinaryMessagesStream())

    } else {
      val wrappers = (0 until outboundLanes).map { i ⇒
        val wrapper = getOrCreateQueueWrapper(OrdinaryQueueIndex + i, queueSize)
        queues(OrdinaryQueueIndex + i) = wrapper // use new underlying queue immediately for restarts
        queuesVisibility = true // volatile write for visibility of the queues array
        wrapper
      }.toVector

      val laneKillSwitch = KillSwitches.shared("outboundLaneKillSwitch")

      val lane = Source.fromGraph(new SendQueue[OutboundEnvelope])
        .via(laneKillSwitch.flow)
        .via(transport.outboundTestFlow(this))
        .viaMat(transport.outboundLane(this))(Keep.both)
        .watchTermination()(Keep.both)
        // recover to avoid error logging by MergeHub
        .recoverWithRetries(-1, { case _: Throwable ⇒ Source.empty })
        .mapMaterializedValue {
          case ((q, c), w) ⇒ (q, c, w)
        }

      val (mergeHub, aeronSinkCompleted) = MergeHub.source[EnvelopeBuffer]
        .via(laneKillSwitch.flow)
        .toMat(transport.aeronSink(this))(Keep.both).run()(materializer)

      val values: Vector[(SendQueue.QueueValue[OutboundEnvelope], Encoder.ChangeOutboundCompression, Future[Done])] =
        (0 until outboundLanes).map { _ ⇒
          lane.to(mergeHub).run()(materializer)
        }(collection.breakOut)

      val (queueValues, changeCompressionValues, laneCompletedValues) = values.unzip3

      import transport.system.dispatcher
      val completed = Future.sequence(laneCompletedValues).flatMap(_ ⇒ aeronSinkCompleted)

      // tear down all parts if one part fails or completes
      completed.onFailure {
        case reason: Throwable ⇒ laneKillSwitch.abort(reason)
      }
      (laneCompletedValues :+ aeronSinkCompleted).foreach(_.onSuccess { case _ ⇒ laneKillSwitch.shutdown() })

      queueValues.zip(wrappers).zipWithIndex.foreach {
        case ((q, w), i) ⇒
          q.inject(w.queue)
          queues(OrdinaryQueueIndex + i) = q // replace with the materialized value, still same underlying queue
      }
      queuesVisibility = true // volatile write for visibility of the queues array

      changeOutboundCompression = changeCompressionValues

      attachStreamRestart("Outbound message stream", OrdinaryQueueIndex, queueSize,
        completed, () ⇒ runOutboundOrdinaryMessagesStream())
    }
  }

  private def runOutboundLargeMessagesStream(): Unit = {
    val wrapper = getOrCreateQueueWrapper(LargeQueueIndex, largeQueueSize)
    queues(LargeQueueIndex) = wrapper // use new underlying queue immediately for restarts
    queuesVisibility = true // volatile write for visibility of the queues array

    val (queueValue, completed) = Source.fromGraph(new SendQueue[OutboundEnvelope])
      .via(transport.outboundTestFlow(this))
      .toMat(transport.outboundLarge(this))(Keep.both)
      .run()(materializer)

    queueValue.inject(wrapper.queue)
    // replace with the materialized value, still same underlying queue
    queues(LargeQueueIndex) = queueValue
    queuesVisibility = true // volatile write for visibility of the queues array
    attachStreamRestart("Outbound large message stream", LargeQueueIndex, largeQueueSize,
      completed, () ⇒ runOutboundLargeMessagesStream())
  }

  private def attachStreamRestart(streamName: String, queueIndex: Int, queueCapacity: Int,
                                  streamCompleted: Future[Done], restart: () ⇒ Unit): Unit = {

    def lazyRestart(): Unit = {
      changeOutboundCompression = Vector.empty
      if (queueIndex == ControlQueueIndex)
        _outboundControlIngress = OptionVal.None
      // LazyQueueWrapper will invoke the `restart` function when first message is offered
      queues(queueIndex) = LazyQueueWrapper(createQueue(queueCapacity), restart)
      queuesVisibility = true // volatile write for visibility of the queues array
    }

    implicit val ec = materializer.executionContext
    updateStreamCompletion(streamName, streamCompleted.recover { case _ ⇒ Done })
    streamCompleted.onFailure {
      case ArteryTransport.ShutdownSignal ⇒ // shutdown as expected
      case _: AeronTerminated             ⇒ // shutdown already in progress
      case cause if transport.isShutdown ⇒
        // don't restart after shutdown, but log some details so we notice
        log.error(cause, s"{} to {} failed after shutdown. {}", streamName, remoteAddress, cause.getMessage)
      case _: AbruptTerminationException ⇒ // ActorSystem shutdown
      case cause: GaveUpMessageException ⇒
        log.debug("{} to {} failed. Restarting it. {}", streamName, remoteAddress, cause.getMessage)
        // restart unconditionally, without counting restarts
        lazyRestart()
      case cause ⇒
        if (queueIndex == ControlQueueIndex) {
          cause match {
            case _: HandshakeTimeoutException ⇒ // ok, quarantine not possible without UID
            case _                            ⇒ quarantine("Outbound control stream restarted")
          }
        }

        if (restartCounter.restart()) {
          log.error(cause, "{} to {} failed. Restarting it. {}", streamName, remoteAddress, cause.getMessage)
          lazyRestart()
        } else {
          log.error(cause, s"{} to {} failed and restarted {} times within {} seconds. Terminating system. ${cause.getMessage}",
            streamName, remoteAddress, advancedSettings.OutboundMaxRestarts, advancedSettings.OutboundRestartTimeout.toSeconds)
          transport.system.terminate()
        }
    }
  }

  // set the future that completes when the current stream for a given name completes
  @tailrec
  private def updateStreamCompletion(streamName: String, streamCompleted: Future[Done]): Unit = {
    val prev = streamCompletions.get()
    if (!streamCompletions.compareAndSet(prev, prev + (streamName → streamCompleted))) {
      updateStreamCompletion(streamName, streamCompleted)
    }
  }

  /**
   * Exposed for orderly shutdown purposes, can not be trusted except for during shutdown as streams may restart.
   * Will complete successfully even if one of the stream completion futures failed
   */
  def streamsCompleted: Future[Done] = {
    implicit val ec = materializer.executionContext
    Future.sequence(streamCompletions.get().values).map(_ ⇒ Done)
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
