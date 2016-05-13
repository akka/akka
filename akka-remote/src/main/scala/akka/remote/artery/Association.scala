/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.Promise
import scala.util.Success
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.RootActorPath
import akka.dispatch.sysmsg.SystemMessage
import akka.event.Logging
import akka.remote.EndpointManager.Send
import akka.remote.RemoteActorRef
import akka.remote.UniqueAddress
import akka.remote.artery.InboundControlJunction.ControlMessageSubject
import akka.remote.artery.OutboundControlJunction.OutboundControlIngress
import akka.stream.Materializer
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.util.Unsafe
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

  @volatile private[this] var queue: SourceQueueWithComplete[Send] = _
  @volatile private[this] var controlQueue: SourceQueueWithComplete[Send] = _
  @volatile private[this] var _outboundControlIngress: OutboundControlIngress = _
  private val materializing = new CountDownLatch(1)

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
  private[this] var _sharedStateDoNotCallMeDirectly: AssociationState =
    new AssociationState(incarnation = 1, uniqueRemoteAddressPromise = Promise())

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
    current.uniqueRemoteAddress.value match {
      case Some(Success(`peer`)) ⇒ // our value
      case _ ⇒
        val newState = new AssociationState(incarnation = current.incarnation + 1, Promise.successful(peer))
        if (swapState(current, newState)) {
          current.uniqueRemoteAddress.value match {
            case Some(Success(old)) ⇒
              log.debug("Incarnation {} of association to [{}] with new UID [{}] (old UID [{}])",
                newState.incarnation, peer.address, peer.uid, old.uid)
              quarantine(Some(old.uid))
            case _ ⇒ // Failed, nothing to do
          }
          // if swap failed someone else completed before us, and that is fine
        }
    }
  }

  // OutboundContext
  override def sendControl(message: ControlMessage): Unit =
    outboundControlIngress.sendControlMessage(message)

  def send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef): Unit = {
    // TODO: lookup subchannel
    // FIXME: Use a different envelope than the old Send, but make sure the new is handled by deadLetters properly
    message match {
      case _: SystemMessage ⇒
        implicit val ec = materializer.executionContext
        controlQueue.offer(Send(message, senderOption, recipient, None)).onFailure {
          case e ⇒
            // FIXME proper error handling, and quarantining
            println(s"# System message dropped, due to $e") // FIXME
        }
      case _ ⇒
        queue.offer(Send(message, senderOption, recipient, None))
    }
  }

  // FIXME we should be able to Send without a recipient ActorRef
  override val dummyRecipient: RemoteActorRef =
    transport.provider.resolveActorRef(RootActorPath(remoteAddress) / "system" / "dummy").asInstanceOf[RemoteActorRef]

  def quarantine(uid: Option[Int]): Unit = {
    // FIXME implement
    log.error("Association to [{}] with UID [{}] is irrecoverably failed. Quarantining address.",
      remoteAddress, uid.getOrElse("unknown"))
  }

  // Idempotent
  def associate(): Unit = {
    // FIXME detect and handle stream failure, e.g. handshake timeout

    // it's important to materialize the outboundControl stream first,
    // so that outboundControlIngress is ready when stages for all streams start
    if (controlQueue eq null) {
      val (q, control) = Source.queue(256, OverflowStrategy.dropBuffer)
        .toMat(transport.outboundControl(this))(Keep.both)
        .run()(materializer)
      controlQueue = q
      _outboundControlIngress = control
      // stage in the control stream may access the outboundControlIngress before returned here
      // using CountDownLatch to make sure that materialization is completed before accessing outboundControlIngress
      materializing.countDown()

      queue = Source.queue(256, OverflowStrategy.dropBuffer)
        .to(transport.outbound(this)).run()(materializer)
    }
  }
}
