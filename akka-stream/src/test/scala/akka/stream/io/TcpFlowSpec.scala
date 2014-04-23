/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.testkit.TestProbe
import akka.io.{ Tcp, IO }
import java.nio.channels.ServerSocketChannel
import java.net.InetSocketAddress
import akka.stream.MaterializerSettings
import akka.stream.impl.ActorProcessor
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import org.reactivestreams.api.Processor
import akka.actor.{ Props, ActorRef, Actor }
import scala.collection.immutable.Queue
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import akka.stream.FlowMaterializer

object TcpFlowSpec {

  case class ClientWrite(bytes: ByteString)
  case class ClientRead(count: Int, readTo: ActorRef)
  case class ClientClose(cmd: Tcp.CloseCommand)

  case object WriteAck extends Tcp.Event

  def testClientProps(connection: ActorRef): Props = Props(new TestClient(connection))
  def testServerProps(address: InetSocketAddress, probe: ActorRef): Props = Props(new TestServer(address, probe))

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

}

class TcpFlowSpec extends AkkaSpec {
  import TcpFlowSpec._

  val settings = MaterializerSettings(
    initialInputBufferSize = 4,
    maximumInputBufferSize = 4,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2)

  val materializer = FlowMaterializer(settings)

  // FIXME: get it from TestUtil
  def temporaryServerAddress: InetSocketAddress = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("127.0.0.1", 0))
    val address = new InetSocketAddress("127.0.0.1", serverSocket.getLocalPort)
    serverSocket.close()
    address
  }

  class Server(val address: InetSocketAddress = temporaryServerAddress) {
    val serverProbe = TestProbe()
    val serverRef = system.actorOf(testServerProps(address, serverProbe.ref))
    serverProbe.expectMsgType[Tcp.Bound]

    def waitAccept(): ServerConnection = new ServerConnection(serverProbe.expectMsgType[ActorRef])
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

  class TcpReadProbe(tcpProcessor: Processor[ByteString, ByteString]) {
    val consumerProbe = StreamTestKit.consumerProbe[ByteString]()
    tcpProcessor.produceTo(consumerProbe)
    val tcpReadSubscription = consumerProbe.expectSubscription()

    def read(count: Int): ByteString = {
      var result = ByteString.empty
      while (result.size < count) {
        tcpReadSubscription.requestMore(1)
        result ++= consumerProbe.expectNext()
      }
      result
    }

    def close(): Unit = tcpReadSubscription.cancel()
  }

  class TcpWriteProbe(tcpProcessor: Processor[ByteString, ByteString]) {
    val producerProbe = StreamTestKit.producerProbe[ByteString]()
    producerProbe.produceTo(tcpProcessor)
    val tcpWriteSubscription = producerProbe.expectSubscription()
    var demand = 0

    def write(bytes: ByteString): Unit = {
      if (demand == 0) demand += tcpWriteSubscription.expectRequestMore()
      tcpWriteSubscription.sendNext(bytes)
      demand -= 1
    }

    def close(): Unit = tcpWriteSubscription.sendComplete()
  }

  def connect(server: Server): (Processor[ByteString, ByteString], ServerConnection) = {
    val tcpProbe = TestProbe()
    tcpProbe.send(IO(StreamTcp), StreamTcp.Connect(settings, server.address))
    val client = server.waitAccept()
    val outgoingConnection = tcpProbe.expectMsgType[StreamTcp.OutgoingTcpConnection]

    (outgoingConnection.processor, client)
  }

  def connect(serverAddress: InetSocketAddress): StreamTcp.OutgoingTcpConnection = {
    val connectProbe = TestProbe()
    connectProbe.send(IO(StreamTcp), StreamTcp.Connect(settings, serverAddress))
    connectProbe.expectMsgType[StreamTcp.OutgoingTcpConnection]
  }

  def bind(serverAddress: InetSocketAddress = temporaryServerAddress): StreamTcp.TcpServerBinding = {
    val bindProbe = TestProbe()
    bindProbe.send(IO(StreamTcp), StreamTcp.Bind(settings, serverAddress))
    bindProbe.expectMsgType[StreamTcp.TcpServerBinding]
  }

  def echoServer(serverAddress: InetSocketAddress = temporaryServerAddress): Future[Unit] =
    Flow(bind(serverAddress).connectionStream).foreach { conn ⇒
      conn.inputStream.produceTo(conn.outputStream)
    }.toFuture(materializer)

  "Outgoing TCP stream" must {

    "work in the happy case" in {
      val testData = ByteString(1, 2, 3, 4, 5)

      val server = new Server()

      val (tcpProcessor, serverConnection) = connect(server)

      val tcpReadProbe = new TcpReadProbe(tcpProcessor)
      val tcpWriteProbe = new TcpWriteProbe(tcpProcessor)

      serverConnection.write(testData)
      serverConnection.read(5)
      tcpReadProbe.read(5) should be(testData)
      tcpWriteProbe.write(testData)
      serverConnection.waitRead() should be(testData)

      tcpWriteProbe.close()
      tcpReadProbe.close()

      //client.read() should be(ByteString.empty)
      server.close()
    }

    "be able to write a sequence of ByteStrings" in {
      val server = new Server()
      val (tcpProcessor, serverConnection) = connect(server)

      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      serverConnection.read(256)
      Flow(tcpProcessor).consume(materializer)

      Flow(testInput).toProducer(materializer).produceTo(tcpProcessor)
      serverConnection.waitRead() should be(expectedOutput)

    }

    "be able to read a sequence of ByteStrings" in {
      val server = new Server()
      val (tcpProcessor, serverConnection) = connect(server)

      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      for (in ← testInput) serverConnection.write(in)
      new TcpWriteProbe(tcpProcessor) // Just register an idle upstream

      val resultFuture = Flow(tcpProcessor).fold(ByteString.empty)((acc, in) ⇒ acc ++ in).toFuture(materializer)
      serverConnection.confirmedClose()
      Await.result(resultFuture, 3.seconds) should be(expectedOutput)

    }

    "half close the connection when output stream is closed" in {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()
      val (tcpProcessor, serverConnection) = connect(server)

      val tcpWriteProbe = new TcpWriteProbe(tcpProcessor)
      val tcpReadProbe = new TcpReadProbe(tcpProcessor)

      tcpWriteProbe.close()
      // FIXME: expect PeerClosed on server
      serverConnection.write(testData)
      tcpReadProbe.read(5) should be(testData)
      serverConnection.confirmedClose()
      tcpReadProbe.consumerProbe.expectComplete()
    }

    "stop reading when the input stream is cancelled" in {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()
      val (tcpProcessor, serverConnection) = connect(server)

      val tcpWriteProbe = new TcpWriteProbe(tcpProcessor)
      val tcpReadProbe = new TcpReadProbe(tcpProcessor)

      tcpReadProbe.close()
      // FIXME: expect PeerClosed on server
      serverConnection.write(testData)
      tcpReadProbe.consumerProbe.expectNoMsg(1.second)
      serverConnection.read(5)
      tcpWriteProbe.write(testData)
      serverConnection.waitRead() should be(testData)
      tcpWriteProbe.close()
    }

    "keep write side open when remote half-closes" in {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()
      val (tcpProcessor, serverConnection) = connect(server)

      val tcpWriteProbe = new TcpWriteProbe(tcpProcessor)
      val tcpReadProbe = new TcpReadProbe(tcpProcessor)

      // FIXME: here (and above tests) add a chitChat() method ensuring this works even after prior communication
      // there should be a chitchat and non-chitchat version

      serverConnection.confirmedClose()
      tcpReadProbe.consumerProbe.expectComplete()

      serverConnection.read(5)
      tcpWriteProbe.write(testData)
      serverConnection.waitRead() should be(testData)

      tcpWriteProbe.close()
      // FIXME: expect closed event
    }

    "shut down both streams when connection is completely closed" in {
      // Client gets a PeerClosed event and does not know that the write side is also closed
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()
      val (tcpProcessor, serverConnection) = connect(server)

      val tcpWriteProbe = new TcpWriteProbe(tcpProcessor)
      val tcpReadProbe = new TcpReadProbe(tcpProcessor)

      serverConnection.abort()
      tcpReadProbe.consumerProbe.expectError()
      tcpWriteProbe.tcpWriteSubscription.expectCancellation()
    }

    "close the connection when input stream and oputput streams are closed" in {
      pending
    }

  }

  "TCP listen stream" must {

    "be able to implement echo" in {

      val serverAddress = temporaryServerAddress
      val server = echoServer(serverAddress)
      val conn = connect(serverAddress)

      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      Flow(testInput).toProducer(materializer).produceTo(conn.outputStream)
      val resultFuture = Flow(conn.inputStream).fold(ByteString.empty)((acc, in) ⇒ acc ++ in).toFuture(materializer)

      Await.result(resultFuture, 3.seconds) should be(expectedOutput)

    }

    "work with a chain of echoes" in {

      val serverAddress = temporaryServerAddress
      val server = echoServer(serverAddress)

      val conn1 = connect(serverAddress)
      val conn2 = connect(serverAddress)
      val conn3 = connect(serverAddress)

      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      Flow(testInput).toProducer(materializer).produceTo(conn1.outputStream)
      conn1.inputStream.produceTo(conn2.outputStream)
      conn2.inputStream.produceTo(conn3.outputStream)
      val resultFuture = Flow(conn3.inputStream).fold(ByteString.empty)((acc, in) ⇒ acc ++ in).toFuture(materializer)

      Await.result(resultFuture, 3.seconds) should be(expectedOutput)

    }

  }

}
