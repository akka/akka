package akka.streams.io

import akka.util.ByteString
import java.net.InetSocketAddress

import akka.actor._
import akka.io.{ IO, Tcp }
import akka.io.Tcp._

import rx.async.spi._
import rx.async.api.{ Consumer, Producer }

import akka.streams.io.TcpStream.IOStream

object TcpStream {
  type IOStream = (Producer[ByteString], Consumer[ByteString])
  type IOHandler = Producer[ByteString] ⇒ Producer[ByteString]

  def connect(address: InetSocketAddress)(implicit system: ActorSystem): IOStream = {
    val connection = system.actorOf(Props(classOf[OutboundTcpStreamActor], address)) // TODO: where should these actors go?
    (new TcpPublisher(connection), new TcpSubscriber(connection))
  }

  /*// alternative connect signature, that makes sure that there's always at least one handler defined
  def connectAndHandle(address: InetSocketAddress)(handler: IOHandler)(implicit system: ActorSystem): Unit = {
    import akka.streams.Combinators._
    val (in, out) = connect(address)
    handler(in).connect(out)
  }*/

  def listen(address: InetSocketAddress)(implicit system: ActorSystem): Producer[(InetSocketAddress, IOStream)] =
    new TcpListenStreamPublisher(system.actorOf(Props(classOf[TcpListenStreamActor], address)))

  /*// alternate listen signature
  def listenAndHandle(address: InetSocketAddress)(handler: InetSocketAddress ⇒ IOHandler)(implicit system: ActorSystem): Unit = {
    import system.dispatcher
    import akka.streams.Combinators._
    listen(address).foreach {
      case (peer, (in, out)) ⇒ handler(peer)(in).connect(out)
    }
  }*/
}

class TcpPublisher(actor: ActorRef) extends Publisher[ByteString] with Producer[ByteString] {
  def subscribe(consumer: Subscriber[ByteString]): Unit = actor ! TcpStreamActor.NewSubscriber(consumer)
  def getPublisher: Publisher[ByteString] = this
}

class TcpSubscription(actor: ActorRef) extends Subscription {
  def requestMore(elements: Int): Unit = actor ! RequestNext
  def cancel(): Unit = actor ! PoisonPill // TODO: Currently there is one subscriber
}

class TcpSubscriber(actor: ActorRef) extends Subscriber[ByteString] with Consumer[ByteString] {
  def onNext(element: ByteString): Unit = actor ! element
  def onComplete(): Unit = actor ! PoisonPill
  def onError(cause: Throwable): Unit = actor ! PoisonPill
  def onSubscribe(subscription: Subscription): Unit = actor ! NewSubscription(subscription)

  def getSubscriber: Subscriber[ByteString] = this
}

object TcpStreamActor {
  case class NewSubscriber(consumer: Subscriber[ByteString])
  case object UnSubscribe // TODO: No multiple subscribers yet
  case object WriteAck extends Tcp.Event
}

abstract class TcpStreamActor(address: InetSocketAddress) extends Actor {
  import TcpStreamActor._
  var subscriber: Subscriber[ByteString] = _
  var subscription: Subscription = _
  def connection: ActorRef

  val ready: Receive = {
    case data: ByteString    ⇒ connection ! Write(data, WriteAck)
    case WriteAck            ⇒ subscription.requestMore(1)
    case RequestNext         ⇒ connection ! ResumeReading
    case Received(data)      ⇒ subscriber.onNext(data)
    case f: CommandFailed    ⇒ context.stop(self)
    case c: ConnectionClosed ⇒ context.stop(self)
  }

  override def postStop(): Unit = {
    if (connection ne null) connection ! Close // TODO: implement half-close
    if (subscriber ne null) subscriber.onComplete()
    if (subscription ne null) subscription.cancel()
  }
}

class OutboundTcpStreamActor(address: InetSocketAddress) extends TcpStreamActor(address) {
  import TcpStreamActor._
  import context.system
  var connection: ActorRef = _

  override def preStart(): Unit = IO(Tcp) ! Connect(address, pullMode = true)

  def receive = preInit

  val preInit: Receive = {
    case _: CommandFailed ⇒
      subscriber.onError(new Exception("Whatever. It failed."))
      subscriber = null // Just to avoid onComplete in postStop
      context.stop(self)

    // TODO: tie-break just as in the inbound case to avoid deadlock
    case NewSubscriber(c) ⇒
      subscriber = c
      subscriber.onSubscribe(new TcpSubscription(self))
      startIfInitialized()
    case NewSubscription(p) ⇒
      subscription = p; startIfInitialized()
    case _: Connected ⇒ connection = sender; startIfInitialized()
  }

  def startIfInitialized(): Unit =
    if ((subscriber ne null) && (subscription ne null) && (connection ne null)) {
      connection ! Register(self)
      // Akka IO side is instantly implicitly pulling the next write
      subscription requestMore 1
      context.become(ready)
    }
}

case object RequestNext
case class NewSubscription(sub: Subscription)

object TcpListenStreamActor {
  case class NewSubscriber(subscriber: Subscriber[(InetSocketAddress, IOStream)])
}

class TcpListenStreamPublisher(val actor: ActorRef)
  extends Publisher[(InetSocketAddress, IOStream)]
  with Producer[(InetSocketAddress, IOStream)] {

  def subscribe(subscriber: Subscriber[(InetSocketAddress, IOStream)]): Unit =
    actor ! TcpListenStreamActor.NewSubscriber(subscriber)

  def getPublisher: Publisher[(InetSocketAddress, IOStream)] = this
}

class TcpListenStreamSubscription(val actor: ActorRef) extends Subscription {
  def requestMore(elements: Int): Unit = actor ! RequestNext
  def cancel(): Unit = actor ! PoisonPill // TODO: since there is only one subscriber yet
}

class TcpListenStreamActor(address: InetSocketAddress) extends Actor {
  import TcpListenStreamActor._
  import context.system
  var pendingRequest = false
  // TODO: 1 element queue right now, should be a dropping queue OR this should be a synchronous Publisher that has
  // to be throttled lower down (sync->async conversion with dropping)
  var pendingConnect: (InetSocketAddress, ActorRef) = null
  var subscriber: Subscriber[(InetSocketAddress, IOStream)] = null

  def receive = {
    case NewSubscriber(c) ⇒
      subscriber = c
      subscriber.onSubscribe(new TcpListenStreamSubscription(self))
      IO(Tcp) ! Bind(self, address, pullMode = true)
      context.become(binding())
  }

  private def tryServe(): Unit =
    if (pendingRequest && (pendingConnect ne null)) {
      val (address, connection) = pendingConnect
      val streamActor = context.actorOf(Props(classOf[InboundTcpStreamActor], address, connection))
      subscriber onNext ((address, (new TcpPublisher(streamActor), new TcpSubscriber(streamActor))))
      pendingRequest = false
      pendingConnect = null
    }

  def binding(requested: Int = 0): Receive = {
    case Tcp.Bound(x) ⇒
      context.become(ready(sender()))
      (0 until requested).foreach(_ ⇒ self ! RequestNext)
    case RequestNext ⇒ context.become(binding(requested + 1))
  }

  def ready(listener: ActorRef): Receive = {
    case RequestNext ⇒
      listener ! Tcp.ResumeAccepting(1)
      pendingRequest = true

      tryServe()
    case Connected(clientAddress, _) ⇒
      if (pendingConnect ne null) context.stop(pendingConnect._2) // Pending queue is full, Need to kill previous unaccepted connection
      pendingConnect = (clientAddress, sender)
      tryServe()
  }

  override def postStop(): Unit = if (subscriber ne null) subscriber.onComplete()
}

class InboundTcpStreamActor(address: InetSocketAddress, val connection: ActorRef) extends Actor {
  import TcpStreamActor._

  def receive = preInit
  connection ! Register(self, keepOpenOnPeerClosed = true)

  val preInit: Receive = {
    case NewSubscriber(c) ⇒
      c.onSubscribe(new TcpSubscription(self))

      context.become(WaitingForSubscription(c))
    case NewSubscription(s) ⇒
      s requestMore 1
      context.become(WaitingForSubscriber(s))

  }
  def WaitingForSubscriber(subscription: Subscription): Receive = {
    case NewSubscriber(c) ⇒
      c.onSubscribe(new TcpSubscription(self))
      context.become(Running(subscription, c))

    case data: ByteString ⇒ connection ! Write(data, WriteAck)
    case WriteAck         ⇒ subscription.requestMore(1)
    case f: CommandFailed ⇒
      subscription.cancel()
      context.stop(self)
    case c: ConnectionClosed ⇒
      subscription.cancel()
      context.stop(self)
  }
  def WaitingForSubscription(subscriber: Subscriber[ByteString]): Receive = {
    case RequestNext    ⇒ connection ! ResumeReading
    case Received(data) ⇒ subscriber.onNext(data)
    case NewSubscription(s) ⇒
      s requestMore 1
      context.become(Running(s, subscriber))

    case f: CommandFailed ⇒
      subscriber.onError(new RuntimeException("Command failed: " + f.toString)); context.stop(self)
    case c: ConnectionClosed ⇒ subscriber.onComplete(); context.stop(self)
  }
  def Running(subscription: Subscription, subscriber: Subscriber[ByteString]): Receive = {
    case data: ByteString ⇒ connection ! Write(data, WriteAck)
    case WriteAck         ⇒ subscription.requestMore(1)

    case RequestNext      ⇒ connection ! ResumeReading
    case Received(data)   ⇒ subscriber.onNext(data)

    case f: CommandFailed ⇒
      subscription.cancel()
      subscriber.onError(new RuntimeException("Command failed: " + f.toString))
      context.stop(self)
    case PeerClosed ⇒
      subscriber.onComplete()
      context.become(Closing(subscription))
    case c: ConnectionClosed ⇒
      subscription.cancel()
      subscriber.onComplete()
      context.stop(self)
  }
  def Closing(subscription: Subscription): Receive = {
    case data: ByteString ⇒ connection ! Write(data, WriteAck)
    case WriteAck         ⇒ subscription.requestMore(1)

    case c: ConnectionClosed ⇒
      subscription.cancel()
      context.stop(self)
  }
}
