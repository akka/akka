/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import scala.annotation.tailrec

import java.nio.channels.{ Selector, SelectionKey, SocketChannel, ServerSocketChannel }
import java.nio.ByteBuffer
import java.nio.channels.spi.SelectorProvider
import java.io.IOException
import java.net._
import scala.collection.immutable
import scala.concurrent.duration._
import scala.util.control.NonFatal
import akka.actor.{ PoisonPill, ActorRef, Terminated }
import akka.testkit.{ TestProbe, TestActorRef, AkkaSpec }
import akka.util.ByteString
import TestUtils._
import TcpSelector._
import Tcp._

class TcpConnectionSpec extends AkkaSpec("akka.io.tcp.register-timeout = 500ms") {
  val serverAddress = temporaryServerAddress()

  "An outgoing connection" must {
    // common behavior

    "set socket options before connecting" in withLocalServer() { localServer ⇒
      val userHandler = TestProbe()
      val selector = TestProbe()
      val connectionActor =
        createConnectionActor(options = Vector(SO.ReuseAddress(true)))(selector.ref, userHandler.ref)
      val clientChannel = connectionActor.underlyingActor.channel
      clientChannel.socket.getReuseAddress must be(true)
    }

    "set socket options after connecting" in withLocalServer() { localServer ⇒
      val userHandler = TestProbe()
      val selector = TestProbe()
      val connectionActor =
        createConnectionActor(options = Vector(SO.KeepAlive(true)))(selector.ref, userHandler.ref)
      val clientChannel = connectionActor.underlyingActor.channel
      clientChannel.socket.getKeepAlive must be(false) // only set after connection is established
      selector.send(connectionActor, ChannelConnectable)
      clientChannel.socket.getKeepAlive must be(true)
    }

    "send incoming data to the connection handler" in withEstablishedConnection() { setup ⇒
      import setup._
      serverSideChannel.write(ByteBuffer.wrap("testdata".getBytes("ASCII")))

      expectReceivedString("testdata")

      // have two packets in flight before the selector notices
      serverSideChannel.write(ByteBuffer.wrap("testdata2".getBytes("ASCII")))
      serverSideChannel.write(ByteBuffer.wrap("testdata3".getBytes("ASCII")))

      expectReceivedString("testdata2testdata3")
    }

    "write data to network (and acknowledge)" in withEstablishedConnection() { setup ⇒
      import setup._

      object Ack
      val writer = TestProbe()

      // directly acknowledge an empty write
      writer.send(connectionActor, Write(ByteString.empty, Ack))
      writer.expectMsg(Ack)

      // reply to write commander with Ack
      val ackedWrite = Write(ByteString("testdata"), Ack)
      val buffer = ByteBuffer.allocate(100)
      serverSideChannel.read(buffer) must be(0)
      writer.send(connectionActor, ackedWrite)
      writer.expectMsg(Ack)
      serverSideChannel.read(buffer) must be(8)
      buffer.flip()

      // not reply to write commander for writes without Ack
      val unackedWrite = Write(ByteString("morestuff!"))
      buffer.clear()
      serverSideChannel.read(buffer) must be(0)
      writer.send(connectionActor, unackedWrite)
      writer.expectNoMsg(500.millis)
      serverSideChannel.read(buffer) must be(10)
      buffer.flip()
      ByteString(buffer).take(10).decodeString("ASCII") must be("morestuff!")
    }

    "stop writing in cases of backpressure and resume afterwards" in
      withEstablishedConnection(setSmallRcvBuffer) { setup ⇒
        import setup._
        object Ack1
        object Ack2

        clientSideChannel.socket.setSendBufferSize(1024)

        val writer = TestProbe()

        // producing backpressure by sending much more than currently fits into
        // our send buffer
        val firstWrite = writeCmd(Ack1)

        // try to write the buffer but since the SO_SNDBUF is too small
        // it will have to keep the rest of the piece and send it
        // when possible
        writer.send(connectionActor, firstWrite)
        selector.expectMsg(WriteInterest)

        // send another write which should fail immediately
        // because we don't store more than one piece in flight
        val secondWrite = writeCmd(Ack2)
        writer.send(connectionActor, secondWrite)
        writer.expectMsg(CommandFailed(secondWrite))

        // reject even empty writes
        writer.send(connectionActor, Write.Empty)
        writer.expectMsg(CommandFailed(Write.Empty))

        // there will be immediately more space in the send buffer because
        // some data will have been sent by now, so we assume we can write
        // again, but still it can't write everything
        selector.send(connectionActor, ChannelWritable)

        // both buffers should now be filled so no more writing
        // is possible
        pullFromServerSide(TestSize)
        writer.expectMsg(Ack1)
      }

    "respect StopReading and ResumeReading" in withEstablishedConnection() { setup ⇒
      import setup._
      connectionHandler.send(connectionActor, StopReading)

      // the selector interprets StopReading to deregister interest
      // for reading
      selector.expectMsg(StopReading)
      connectionHandler.send(connectionActor, ResumeReading)
      selector.expectMsg(ReadInterest)
    }

    "close the connection and reply with `Closed` upon reception of a `Close` command" in withEstablishedConnection(setSmallRcvBuffer) { setup ⇒
      import setup._

      // we should test here that a pending write command is properly finished first
      object Ack
      // set an artificially small send buffer size so that the write is queued
      // inside the connection actor
      clientSideChannel.socket.setSendBufferSize(1024)

      // we send a write and a close command directly afterwards
      connectionHandler.send(connectionActor, writeCmd(Ack))
      val closeCommander = TestProbe()
      closeCommander.send(connectionActor, Close)

      pullFromServerSide(TestSize)
      connectionHandler.expectMsg(Ack)
      connectionHandler.expectMsg(Closed)
      closeCommander.expectMsg(Closed)
      assertThisConnectionActorTerminated()

      checkFor(serverSelectionKey, SelectionKey.OP_READ, 2000)

      val buffer = ByteBuffer.allocate(1)
      serverSideChannel.read(buffer) must be(-1)
    }

    "send only one `Closed` event to the handler, if the handler commanded the Close" in withEstablishedConnection() { setup ⇒
      import setup._

      connectionHandler.send(connectionActor, Close)
      connectionHandler.expectMsg(Closed)
      connectionHandler.expectNoMsg(500.millis)
    }

    "abort the connection and reply with `Aborted` upong reception of an `Abort` command" in withEstablishedConnection() { setup ⇒
      import setup._

      connectionHandler.send(connectionActor, Abort)
      connectionHandler.expectMsg(Aborted)

      assertThisConnectionActorTerminated()

      val buffer = ByteBuffer.allocate(1)
      val thrown = evaluating { serverSideChannel.read(buffer) } must produce[IOException]
      thrown.getMessage must be("Connection reset by peer")
    }

    "close the connection and reply with `ConfirmedClosed` upong reception of an `ConfirmedClose` command" in withEstablishedConnection(setSmallRcvBuffer) { setup ⇒
      import setup._

      // we should test here that a pending write command is properly finished first
      object Ack
      // set an artificially small send buffer size so that the write is queued
      // inside the connection actor
      clientSideChannel.socket.setSendBufferSize(1024)

      // we send a write and a close command directly afterwards
      connectionHandler.send(connectionActor, writeCmd(Ack))
      connectionHandler.send(connectionActor, ConfirmedClose)

      connectionHandler.expectNoMsg(100.millis)
      pullFromServerSide(TestSize)
      connectionHandler.expectMsg(Ack)

      selector.send(connectionActor, ChannelReadable)
      connectionHandler.expectNoMsg(100.millis) // not yet

      val buffer = ByteBuffer.allocate(1)
      checkFor(serverSelectionKey, SelectionKey.OP_READ, 2000)
      serverSideChannel.read(buffer) must be(-1)
      serverSideChannel.close()

      selector.send(connectionActor, ChannelReadable)
      connectionHandler.expectMsg(ConfirmedClosed)

      assertThisConnectionActorTerminated()
    }

    "report when peer closed the connection" in withEstablishedConnection() { setup ⇒
      import setup._

      serverSideChannel.close()
      selector.send(connectionActor, ChannelReadable)
      connectionHandler.expectMsg(PeerClosed)

      assertThisConnectionActorTerminated()
    }
    "report when peer aborted the connection" in withEstablishedConnection() { setup ⇒
      import setup._

      abortClose(serverSideChannel)
      selector.send(connectionActor, ChannelReadable)
      connectionHandler.expectMsgType[ErrorClose].cause must be("Connection reset by peer")
      // wait a while
      connectionHandler.expectNoMsg(200.millis)

      assertThisConnectionActorTerminated()
    }
    "report when peer closed the connection when trying to write" in withEstablishedConnection() { setup ⇒
      import setup._

      val writer = TestProbe()

      abortClose(serverSideChannel)
      writer.send(connectionActor, Write(ByteString("testdata")))
      // bother writer and handler should get the message
      writer.expectMsgType[ErrorClose]
      connectionHandler.expectMsgType[ErrorClose]

      assertThisConnectionActorTerminated()
    }

    // error conditions
    "report failed connection attempt while not accepted" in withUnacceptedConnection() { setup ⇒
      import setup._
      // close instead of accept
      localServer.close()

      selector.send(connectionActor, ChannelConnectable)
      userHandler.expectMsgType[ErrorClose].cause must be("Connection reset by peer")

      verifyActorTermination(connectionActor)
    }

    val UnboundAddress = temporaryServerAddress()
    "report failed connection attempt when target is unreachable" in
      withUnacceptedConnection(connectionActorCons = createConnectionActor(serverAddress = UnboundAddress)) { setup ⇒
        import setup._

        val sel = SelectorProvider.provider().openSelector()
        val key = clientSideChannel.register(sel, SelectionKey.OP_CONNECT | SelectionKey.OP_READ)
        sel.select(200)

        key.isConnectable must be(true)
        selector.send(connectionActor, ChannelConnectable)
        userHandler.expectMsgType[ErrorClose].cause must be("Connection refused")

        verifyActorTermination(connectionActor)
      }

    "time out when Connected isn't answered with Register" in withUnacceptedConnection() { setup ⇒
      import setup._

      localServer.accept()
      selector.send(connectionActor, ChannelConnectable)
      userHandler.expectMsg(Connected(serverAddress, clientSideChannel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress]))

      verifyActorTermination(connectionActor)
    }

    "close the connection when user handler dies while connecting" in withUnacceptedConnection() { setup ⇒
      import setup._

      userHandler.ref ! PoisonPill

      verifyActorTermination(connectionActor)
    }

    "close the connection when connection handler dies while connected" in withEstablishedConnection() { setup ⇒
      import setup._
      watch(connectionHandler.ref)
      watch(connectionActor)
      system.stop(connectionHandler.ref)
      expectMsgType[Terminated].actor must be(connectionHandler.ref)
      expectMsgType[Terminated].actor must be(connectionActor)
    }
  }

  def withLocalServer(setServerSocketOptions: ServerSocketChannel ⇒ Unit = _ ⇒ ())(body: ServerSocketChannel ⇒ Any): Unit = {
    val localServer = ServerSocketChannel.open()
    try {
      setServerSocketOptions(localServer)
      localServer.socket.bind(serverAddress)
      localServer.configureBlocking(false)
      body(localServer)
    } finally localServer.close()
  }

  case class UnacceptedSetup(
    localServer: ServerSocketChannel,
    userHandler: TestProbe,
    selector: TestProbe,
    connectionActor: TestActorRef[TcpOutgoingConnection],
    clientSideChannel: SocketChannel)
  case class RegisteredSetup(
    unregisteredSetup: UnacceptedSetup,
    connectionHandler: TestProbe,
    serverSideChannel: SocketChannel) {
    def userHandler: TestProbe = unregisteredSetup.userHandler
    def selector: TestProbe = unregisteredSetup.selector
    def connectionActor: TestActorRef[TcpOutgoingConnection] = unregisteredSetup.connectionActor
    def clientSideChannel: SocketChannel = unregisteredSetup.clientSideChannel

    val nioSelector = SelectorProvider.provider().openSelector()

    val clientSelectionKey = registerChannel(clientSideChannel, "client")
    val serverSelectionKey = registerChannel(serverSideChannel, "server")

    def registerChannel(channel: SocketChannel, name: String): SelectionKey = {
      val res = channel.register(nioSelector, 0)
      res.attach(name)
      res
    }

    def checkFor(key: SelectionKey, interest: Int, millis: Int = 100): Boolean =
      if (key.isValid) {
        if ((key.readyOps() & interest) != 0) true
        else {
          key.interestOps(interest)
          val ret = nioSelector.select(millis)
          key.interestOps(0)

          ret > 0 && nioSelector.selectedKeys().contains(key) && key.isValid &&
            (key.readyOps() & interest) != 0
        }
      } else false

    def openSelectorFor(channel: SocketChannel, interests: Int): (Selector, SelectionKey) = {
      val sel = SelectorProvider.provider().openSelector()
      val key = channel.register(sel, interests)
      (sel, key)
    }

    val buffer = ByteBuffer.allocate(TestSize)

    /**
     * Tries to simultaneously act on client and server side to read from the server
     * all pending data from the client.
     */
    @tailrec final def pullFromServerSide(remaining: Int, remainingTries: Int = 1000): Unit =
      if (remainingTries <= 0)
        throw new AssertionError("Pulling took too many loops")
      else if (remaining > 0) {
        if (selector.msgAvailable) {
          selector.expectMsg(WriteInterest)
          clientSelectionKey.interestOps(SelectionKey.OP_WRITE)
        }

        serverSelectionKey.interestOps(SelectionKey.OP_READ)
        nioSelector.select(10)
        if (nioSelector.selectedKeys().contains(clientSelectionKey)) {
          clientSelectionKey.interestOps(0)
          selector.send(connectionActor, ChannelWritable)
        }

        val read =
          if (nioSelector.selectedKeys().contains(serverSelectionKey)) tryReading()
          else 0

        nioSelector.selectedKeys().clear()

        pullFromServerSide(remaining - read, remainingTries - 1)
      }

    private def tryReading(): Int = {
      buffer.clear()
      val read = serverSideChannel.read(buffer)

      if (read == 0)
        throw new IllegalStateException("Made no progress")
      else if (read == -1)
        throw new IllegalStateException("Connection was closed unexpectedly with remaining bytes " + remaining)
      else read
    }

    @tailrec final def expectReceivedString(data: String): Unit = {
      data.length must be > 0

      selector.send(connectionActor, ChannelReadable)

      val gotReceived = connectionHandler.expectMsgType[Received]
      val receivedString = gotReceived.data.decodeString("ASCII")
      data.startsWith(receivedString) must be(true)
      if (receivedString.length < data.length)
        expectReceivedString(data.drop(receivedString.length))
    }

    def assertThisConnectionActorTerminated(): Unit = {
      verifyActorTermination(connectionActor)
      clientSideChannel must not be ('open)
    }
  }
  def withUnacceptedConnection(
    setServerSocketOptions: ServerSocketChannel ⇒ Unit = _ ⇒ (),
    connectionActorCons: (ActorRef, ActorRef) ⇒ TestActorRef[TcpOutgoingConnection] = createConnectionActor())(body: UnacceptedSetup ⇒ Any): Unit =

    withLocalServer(setServerSocketOptions) { localServer ⇒
      val userHandler = TestProbe()
      val selector = TestProbe()
      val connectionActor = connectionActorCons(selector.ref, userHandler.ref)
      val clientSideChannel = connectionActor.underlyingActor.channel

      selector.expectMsg(RegisterOutgoingConnection(clientSideChannel))

      body {
        UnacceptedSetup(
          localServer,
          userHandler,
          selector,
          connectionActor,
          clientSideChannel)
      }
    }
  def withEstablishedConnection(setServerSocketOptions: ServerSocketChannel ⇒ Unit = _ ⇒ ())(body: RegisteredSetup ⇒ Any): Unit = withUnacceptedConnection(setServerSocketOptions) { unregisteredSetup ⇒
    import unregisteredSetup._

    localServer.configureBlocking(true)
    val serverSideChannel = localServer.accept()
    serverSideChannel.configureBlocking(false)

    serverSideChannel must not be (null)
    selector.send(connectionActor, ChannelConnectable)
    userHandler.expectMsg(Connected(serverAddress, clientSideChannel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress]))

    val connectionHandler = TestProbe()
    userHandler.send(connectionActor, Register(connectionHandler.ref))
    selector.expectMsg(ReadInterest)

    body {
      RegisteredSetup(
        unregisteredSetup,
        connectionHandler,
        serverSideChannel)
    }
  }

  val TestSize = 10000

  def writeCmd(ack: AnyRef) =
    Write(ByteString(Array.fill[Byte](TestSize)(0)), ack)

  def setSmallRcvBuffer(channel: ServerSocketChannel): Unit =
    channel.socket.setReceiveBufferSize(1024)

  def createConnectionActor(
    serverAddress: InetSocketAddress = serverAddress,
    localAddress: Option[InetSocketAddress] = None,
    options: immutable.Seq[Tcp.SocketOption] = Nil)(
      _selector: ActorRef,
      commander: ActorRef): TestActorRef[TcpOutgoingConnection] = {

    TestActorRef(
      new TcpOutgoingConnection(Tcp(system), commander, serverAddress, localAddress, options) {
        override def postRestart(reason: Throwable) {
          // ensure we never restart
          context.stop(self)
        }
        override def selector = _selector
      })
  }

  def abortClose(channel: SocketChannel): Unit = {
    try channel.socket.setSoLinger(true, 0) // causes the following close() to send TCP RST
    catch {
      case NonFatal(e) ⇒
        // setSoLinger can fail due to http://bugs.sun.com/view_bug.do?bug_id=6799574
        // (also affected: OS/X Java 1.6.0_37)
        log.debug("setSoLinger(true, 0) failed with {}", e)
    }
    channel.close()
  }

  def abort(channel: SocketChannel) {
    channel.socket.setSoLinger(true, 0)
    channel.close()
  }
}
