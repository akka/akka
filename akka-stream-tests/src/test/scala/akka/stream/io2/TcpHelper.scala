/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io2

import java.io.Closeable
import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.stream.io2.StreamTcp.IncomingTcpConnection
import akka.stream.testkit.StreamTestKit
import akka.stream.MaterializerSettings
import akka.testkit.{ TestKitBase, TestProbe }
import akka.util.ByteString
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import org.reactivestreams.{ Subscriber, Publisher }
import scala.collection.immutable.Queue
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import akka.stream.scaladsl.Sink
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Flow

object TcpHelper {
  case class ClientWrite(bytes: ByteString)
  case class ClientRead(count: Int, readTo: ActorRef)
  case class ClientClose(cmd: Tcp.CloseCommand)

  case object WriteAck extends Tcp.Event

  def testClientProps(connection: ActorRef): Props =
    Props(new TestClient(connection)).withDispatcher("akka.test.stream-dispatcher")
  def testServerProps(address: InetSocketAddress, probe: ActorRef): Props =
    Props(new TestServer(address, probe)).withDispatcher("akka.test.stream-dispatcher")

  class TestClient(connection: ActorRef) extends Actor {
    connection ! Tcp.Register(self, keepOpenOnPeerClosed = true, useResumeWriting = false)

    var queuedWrites = Queue.empty[ByteString]
    var writePending = false

    var toRead = 0
    var readBuffer = ByteString.empty
    var readTo: ActorRef = context.system.deadLetters

    var closeAfterWrite: Option[Tcp.CloseCommand] = None

    // FIXME: various close scenarios
    def receive = {
      case ClientWrite(bytes) if !writePending ⇒
        writePending = true
        connection ! Tcp.Write(bytes, WriteAck)
      case ClientWrite(bytes) ⇒
        queuedWrites = queuedWrites.enqueue(bytes)
      case WriteAck if queuedWrites.nonEmpty ⇒
        val (next, remaining) = queuedWrites.dequeue
        queuedWrites = remaining
        connection ! Tcp.Write(next, WriteAck)
      case WriteAck ⇒
        writePending = false
        closeAfterWrite match {
          case Some(cmd) ⇒ connection ! cmd
          case None      ⇒
        }
      case ClientRead(count, requester) ⇒
        readTo = requester
        toRead = count
        connection ! Tcp.ResumeReading
      case Tcp.Received(bytes) ⇒
        readBuffer ++= bytes
        if (readBuffer.size >= toRead) {
          readTo ! readBuffer
          readBuffer = ByteString.empty
          toRead = 0
          readTo = context.system.deadLetters
        } else connection ! Tcp.ResumeReading

      case ClientClose(cmd) ⇒
        if (!writePending) connection ! cmd
        else closeAfterWrite = Some(cmd)
    }

  }

  case object ServerClose

  class TestServer(serverAddress: InetSocketAddress, probe: ActorRef) extends Actor {
    import context.system
    IO(Tcp) ! Tcp.Bind(self, serverAddress, pullMode = true)
    var listener: ActorRef = _

    def receive = {
      case b @ Tcp.Bound(_) ⇒
        listener = sender()
        listener ! Tcp.ResumeAccepting(1)
        probe ! b
      case Tcp.Connected(_, _) ⇒
        val handler = context.actorOf(testClientProps(sender()))
        listener ! Tcp.ResumeAccepting(1)
        probe ! handler
      case ServerClose ⇒
        listener ! Tcp.Unbind
        context.stop(self)
    }

  }

  // FIXME: get it from TestUtil
  def temporaryServerAddress: InetSocketAddress = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("127.0.0.1", 0))
    val address = new InetSocketAddress("127.0.0.1", serverSocket.getLocalPort)
    serverSocket.close()
    address
  }

  def concurrently[O1, O2](block1: ⇒ O1, block2: ⇒ O2): (O1, O2) = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val list = Await.result(Future.sequence(List(Future(block1), Future(block2))), 5.seconds)
    (list(0).asInstanceOf[O1], list(1).asInstanceOf[O2])
  }
}

trait TcpHelper { this: TestKitBase ⇒
  import TcpHelper._

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 4, maxSize = 4)
    .withFanOutBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = FlowMaterializer(settings)

  class Server(val address: InetSocketAddress = temporaryServerAddress) {
    val serverProbe = TestProbe()
    val serverRef = system.actorOf(testServerProps(address, serverProbe.ref))
    serverProbe.expectMsgType[Tcp.Bound]

    def waitAccept(): ServerConnection = {
      new ServerConnection(serverProbe.expectMsgType[ActorRef])
    }
    def close(): Unit = serverRef ! ServerClose
  }

  class ServerConnection(val connectionActor: ActorRef) {
    val connectionProbe = TestProbe()
    def write(bytes: ByteString): Unit = connectionActor ! ClientWrite(bytes)

    def read(count: Int): Unit = connectionActor ! ClientRead(count, connectionProbe.ref)

    def waitRead(): ByteString = connectionProbe.expectMsgType[ByteString]
    def confirmedClose(): Unit = connectionActor ! ClientClose(Tcp.ConfirmedClose)
    def close(): Unit = connectionActor ! ClientClose(Tcp.Close)
    def abort(): Unit = connectionActor ! ClientClose(Tcp.Abort)
  }

  class TcpReadProbe(tcpPublisher: Publisher[ByteString]) {
    val subscriberProbe = StreamTestKit.SubscriberProbe[ByteString]()
    tcpPublisher.subscribe(subscriberProbe)
    val tcpReadSubscription = subscriberProbe.expectSubscription()

    def read(count: Int): ByteString = {
      var result = ByteString.empty
      while (result.size < count) {
        tcpReadSubscription.request(1)
        result ++= subscriberProbe.expectNext()
      }
      result
    }

    def close(): Unit = tcpReadSubscription.cancel()
  }

  class TcpWriteProbe(tcpSubscriber: Subscriber[ByteString]) {
    val publisherProbe = StreamTestKit.PublisherProbe[ByteString]()
    publisherProbe.subscribe(tcpSubscriber)
    val tcpWriteSubscription = publisherProbe.expectSubscription()
    var demand = 0L

    def write(bytes: ByteString): Unit = {
      if (demand == 0) demand += tcpWriteSubscription.expectRequest()
      tcpWriteSubscription.sendNext(bytes)
      demand -= 1
    }

    def close(): Unit = tcpWriteSubscription.sendComplete()
  }

  class EchoServer(termination: Future[Unit], closeable: Closeable) extends Closeable {
    def close(): Unit = {
      closeable.close()
    }

    def awaitTermination(atMost: Duration): Unit = {
      Await.result(termination, atMost)
    }

    def terminationFuture: Future[Unit] = termination
  }

  def connect(server: Server): (Subscriber[ByteString], Publisher[ByteString], ServerConnection) = {
    val (client, (subscriber, publisher)) = concurrently(server.waitAccept(), connect(server.address))
    (subscriber, publisher, client)
  }

  def connect(serverAddress: InetSocketAddress): (Subscriber[ByteString], Publisher[ByteString]) = {
    val tcpProbe = TestProbe()
    val outbound = Source.subscriber[ByteString]
    val inbound = Sink.publisher[ByteString]
    tcpProbe.send(IO(StreamTcp), StreamTcp.Connect(outbound, inbound, serverAddress))

    val outgoingConnection = tcpProbe.expectMsgType[StreamTcp.OutgoingTcpConnection]

    (outgoingConnection.outbound.get(outbound), outgoingConnection.inbound.get(inbound))
  }

  def bind(connectionHandler: Sink[StreamTcp.IncomingTcpConnection],
           serverAddress: InetSocketAddress = temporaryServerAddress): StreamTcp.TcpServerBinding = {
    val bindProbe = TestProbe()
    bindProbe.send(IO(StreamTcp), StreamTcp.Bind(connectionHandler, serverAddress))
    bindProbe.expectMsgType[StreamTcp.TcpServerBinding]
  }

  def echoServer(serverAddress: InetSocketAddress = temporaryServerAddress): EchoServer = {
    val foreachSink = Sink.foreach[IncomingTcpConnection] { conn ⇒
      conn.inbound.to(conn.outbound).run()
    }
    val binding = bind(Flow[IncomingTcpConnection].to(foreachSink), serverAddress)
    new EchoServer(binding.connection.get(foreachSink), binding)
  }
}
