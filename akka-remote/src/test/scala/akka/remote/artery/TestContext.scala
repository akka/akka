/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.remote.UniqueAddress
import akka.actor.Address
import scala.concurrent.Future
import akka.remote.artery.InboundReplyJunction.ReplySubject
import akka.remote.RemoteActorRef
import scala.concurrent.Promise
import akka.Done
import akka.remote.artery.InboundReplyJunction.ReplyObserver
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

private[akka] class TestInboundContext(
  override val localAddress: UniqueAddress,
  val replySubject: TestReplySubject = new TestReplySubject,
  replyDropRate: Double = 0.0) extends InboundContext {

  private val associations = new ConcurrentHashMap[Address, OutboundContext]

  def sendReply(to: Address, message: ControlMessage) = {
    if (ThreadLocalRandom.current().nextDouble() >= replyDropRate)
      replySubject.sendReply(InboundEnvelope(null, to, message, None))
  }

  def association(remoteAddress: Address): OutboundContext =
    associations.get(remoteAddress) match {
      case null ⇒
        val a = new TestOutboundContext(localAddress, remoteAddress, replySubject)
        associations.putIfAbsent(remoteAddress, a) match {
          case null     ⇒ a
          case existing ⇒ existing
        }
      case existing ⇒ existing
    }

  protected def createAssociation(remoteAddress: Address): OutboundContext =
    new TestOutboundContext(localAddress, remoteAddress, replySubject)
}

private[akka] class TestOutboundContext(
  override val localAddress: UniqueAddress,
  override val remoteAddress: Address,
  override val replySubject: TestReplySubject) extends OutboundContext {

  private val _uniqueRemoteAddress = Promise[UniqueAddress]()
  def uniqueRemoteAddress: Future[UniqueAddress] = _uniqueRemoteAddress.future
  def completeRemoteAddress(a: UniqueAddress): Unit = _uniqueRemoteAddress.trySuccess(a)

  // FIXME we should be able to Send without a recipient ActorRef
  def dummyRecipient: RemoteActorRef = null

}

private[akka] class TestReplySubject extends ReplySubject {

  private var replyObservers = new CopyOnWriteArrayList[ReplyObserver]

  override def attach(observer: ReplyObserver): Future[Done] = {
    replyObservers.add(observer)
    Future.successful(Done)
  }

  override def detach(observer: ReplyObserver): Unit = {
    replyObservers.remove(observer)
  }

  override def stopped: Future[Done] = Promise[Done]().future

  def sendReply(env: InboundEnvelope): Unit = {
    val iter = replyObservers.iterator()
    while (iter.hasNext())
      iter.next().reply(env)
  }
}
