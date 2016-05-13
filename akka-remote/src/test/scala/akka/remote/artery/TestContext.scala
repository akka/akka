/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.remote.UniqueAddress
import akka.actor.Address
import scala.concurrent.Future
import akka.remote.artery.InboundControlJunction.ControlMessageSubject
import akka.remote.RemoteActorRef
import scala.concurrent.Promise
import akka.Done
import akka.remote.artery.InboundControlJunction.ControlMessageObserver
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import akka.actor.ActorRef

private[akka] class TestInboundContext(
  override val localAddress: UniqueAddress,
  val controlSubject: TestControlMessageSubject = new TestControlMessageSubject,
  replyDropRate: Double = 0.0) extends InboundContext {

  private val associations = new ConcurrentHashMap[Address, OutboundContext]

  def sendControl(to: Address, message: ControlMessage) = {
    if (ThreadLocalRandom.current().nextDouble() >= replyDropRate)
      controlSubject.sendControl(InboundEnvelope(null, to, message, None))
  }

  def association(remoteAddress: Address): OutboundContext =
    associations.get(remoteAddress) match {
      case null ⇒
        val a = new TestOutboundContext(localAddress, remoteAddress, controlSubject)
        associations.putIfAbsent(remoteAddress, a) match {
          case null     ⇒ a
          case existing ⇒ existing
        }
      case existing ⇒ existing
    }

  protected def createAssociation(remoteAddress: Address): OutboundContext =
    new TestOutboundContext(localAddress, remoteAddress, controlSubject)
}

private[akka] class TestOutboundContext(
  override val localAddress: UniqueAddress,
  override val remoteAddress: Address,
  override val controlSubject: TestControlMessageSubject) extends OutboundContext {

  private val _uniqueRemoteAddress = Promise[UniqueAddress]()
  def uniqueRemoteAddress: Future[UniqueAddress] = _uniqueRemoteAddress.future
  def completeRemoteAddress(a: UniqueAddress): Unit = _uniqueRemoteAddress.trySuccess(a)

  // FIXME we should be able to Send without a recipient ActorRef
  def dummyRecipient: RemoteActorRef = null

}

private[akka] class TestControlMessageSubject extends ControlMessageSubject {

  private var observers = new CopyOnWriteArrayList[ControlMessageObserver]

  override def attach(observer: ControlMessageObserver): Future[Done] = {
    observers.add(observer)
    Future.successful(Done)
  }

  override def detach(observer: ControlMessageObserver): Unit = {
    observers.remove(observer)
  }

  override def stopped: Future[Done] = Promise[Done]().future

  def sendControl(env: InboundEnvelope): Unit = {
    val iter = observers.iterator()
    while (iter.hasNext())
      iter.next().notify(env)
  }

}

private[akka] class ManualReplyInboundContext(
  replyProbe: ActorRef,
  localAddress: UniqueAddress,
  controlSubject: TestControlMessageSubject) extends TestInboundContext(localAddress, controlSubject) {

  private var lastReply: Option[(Address, ControlMessage)] = None

  override def sendControl(to: Address, message: ControlMessage) = {
    lastReply = Some((to, message))
    replyProbe ! message
  }

  def deliverLastReply(): Unit = {
    lastReply.foreach { case (to, message) ⇒ super.sendControl(to, message) }
    lastReply = None
  }
}
