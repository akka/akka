/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import scala.annotation.tailrec

import java.nio.channels.{ SelectionKey, SocketChannel, ServerSocketChannel }
import java.nio.ByteBuffer
import java.nio.channels.spi.SelectorProvider
import java.io.IOException
import java.net._
import scala.collection.immutable
import scala.concurrent.duration._
import scala.util.control.NonFatal
import akka.actor.{ ActorRef, Props, Actor, Terminated }
import akka.testkit.{ TestProbe, TestActorRef, AkkaSpec }
import akka.util.ByteString
import Tcp._
import java.util.concurrent.CountDownLatch

class TcpConnectionSpec extends AkkaSpec("akka.io.tcp.register-timeout = 500ms") {
  val port = 45679
  val localhost = InetAddress.getLocalHost
  val serverAddress = new InetSocketAddress(localhost, port)

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

    "send incoming data to user" in withEstablishedConnection() { setup ⇒
      import setup._
      serverSideChannel.write(ByteBuffer.wrap("testdata".getBytes("ASCII")))
      // emulate selector behavior
      selector.send(connectionActor, ChannelReadable)
      connectionHandler.expectMsgType[Received].data.decodeString("ASCII") must be("testdata")
      // have two packets in flight before the selector notices
      serverSideChannel.write(ByteBuffer.wrap("testdata2".getBytes("ASCII")))
      serverSideChannel.write(ByteBuffer.wrap("testdata3".getBytes("ASCII")))
      selector.send(connectionActor, ChannelReadable)
      connectionHandler.expectMsgType[Received].data.decodeString("ASCII") must be("testdata2testdata3")
    }

    "write data to network (and acknowledge)" in withEstablishedConnection() { setup ⇒
      import setup._
      serverSideChannel.configureBlocking(false)

      object Ack
      val writer = TestProbe()

      // directly acknowledge an empty write
      writer.send(connectionActor, Write(ByteString.empty, Ack))
      writer.expectMsg(Ack)

      val write = Write(ByteString("testdata"), Ack)
      val buffer = ByteBuffer.allocate(100)
      serverSideChannel.read(buffer) must be(0)

      writer.send(connectionActor, write)
      // make sure the writer gets the ack
      writer.expectMsg(Ack)
      serverSideChannel.read(buffer) must be(8)
      buffer.flip()
      ByteString(buffer).take(8).decodeString("ASCII") must be("testdata")
    }

    "stop writing in cases of backpressure and resume afterwards" in
      withEstablishedConnection(setSmallRcvBuffer) { setup ⇒
        import setup._
        object Ack1
        object Ack2

        //serverSideChannel.configureBlocking(false)
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

    "close the connection" in withEstablishedConnection(setSmallRcvBuffer) { setup ⇒
      import setup._

      // we should test here that a pending write command is properly finished first
      object Ack
      // set an artificially small send buffer size so that the write is queued
      // inside the connection actor
      clientSideChannel.socket.setSendBufferSize(1024)

      // we send a write and a close command directly afterwards
      connectionHandler.send(connectionActor, writeCmd(Ack))
      connectionHandler.send(connectionActor, Close)

      pullFromServerSide(TestSize)
      connectionHandler.expectMsg(Ack)
      connectionHandler.expectMsg(Closed)
      assertThisConnectionActorTerminated()

      val buffer = ByteBuffer.allocate(1)
      serverSideChannel.read(buffer) must be(-1)
    }

    "abort the connection" in withEstablishedConnection() { setup ⇒
      import setup._

      connectionHandler.send(connectionActor, Abort)
      connectionHandler.expectMsg(Aborted)

      assertThisConnectionActorTerminated()

      val buffer = ByteBuffer.allocate(1)
      val thrown = evaluating { serverSideChannel.read(buffer) } must produce[IOException]
      thrown.getMessage must be("Connection reset by peer")
    }

    "close the connection and confirm" in withEstablishedConnection(setSmallRcvBuffer) { setup ⇒
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

      assertActorTerminated(connectionActor)
    }

    val UnknownAddress = new InetSocketAddress("127.0.0.1", 63186)
    "report failed connection attempt when target is unreachable" in
      withUnacceptedConnection(connectionActorCons = createConnectionActor(serverAddress = UnknownAddress)) { setup ⇒
        import setup._

        val sel = SelectorProvider.provider().openSelector()
        val key = clientSideChannel.register(sel, SelectionKey.OP_CONNECT | SelectionKey.OP_READ)
        sel.select(200)

        key.isConnectable must be(true)
        selector.send(connectionActor, ChannelConnectable)
        userHandler.expectMsgType[ErrorClose].cause must be("Connection refused")

        assertActorTerminated(connectionActor)
      }

    "time out when Connected isn't answered with Register" in withUnacceptedConnection() { setup ⇒
      import setup._

      localServer.accept()
      selector.send(connectionActor, ChannelConnectable)
      userHandler.expectMsg(Connected(serverAddress, clientSideChannel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress]))

      assertActorTerminated(connectionActor)
    }

    "close the connection when user handler dies while connecting" in withUnacceptedConnection() { setup ⇒
      import setup._

      // simulate death of userHandler test probe
      userHandler.send(connectionActor, akka.actor.Terminated(userHandler.ref)(false, false))

      assertActorTerminated(connectionActor)
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

    val buffer = ByteBuffer.allocate(TestSize)
    @tailrec final def pullFromServerSide(remaining: Int): Unit =
      if (remaining > 0) {
        if (selector.msgAvailable) {
          selector.expectMsg(WriteInterest)
          selector.send(connectionActor, ChannelWritable)
        }
        buffer.clear()
        val read = serverSideChannel.read(buffer)
        if (read == 0)
          throw new IllegalStateException("Didn't make any progress")
        else if (read == -1)
          throw new IllegalStateException("Connection was closed unexpectedly with remaining bytes " + remaining)

        pullFromServerSide(remaining - read)
      }

    def assertThisConnectionActorTerminated(): Unit = {
      assertActorTerminated(connectionActor)
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
      selector: ActorRef,
      commander: ActorRef): TestActorRef[TcpOutgoingConnection] = {

    TestActorRef(
      new TcpOutgoingConnection(selector, commander, serverAddress, localAddress, options) {
        override def postRestart(reason: Throwable) {
          // ensure we never restart
          context.stop(self)
        }
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
  def assertActorTerminated(connectionActor: TestActorRef[TcpOutgoingConnection]): Unit = {
    val watcher = TestProbe()
    watcher.watch(connectionActor)
    watcher.expectMsgType[Terminated].actor must be(connectionActor)
  }
}
