/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.io.{ FileOutputStream, File, IOException }
import java.net.{ URLClassLoader, ConnectException, InetSocketAddress, SocketException }
import java.nio.ByteBuffer
import java.nio.channels.{ SelectionKey, Selector, ServerSocketChannel, SocketChannel }
import java.nio.channels.spi.SelectorProvider
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration._
import scala.util.control.NonFatal
import org.scalatest.matchers._
import akka.io.Tcp._
import akka.io.SelectionHandler._
import akka.TestUtils
import TestUtils._
import akka.actor.{ ActorRef, PoisonPill, Terminated }
import akka.testkit.{ AkkaSpec, EventFilter, TestActorRef, TestProbe }
import akka.util.{ Helpers, ByteString }
import akka.actor.DeathPactException
import java.nio.channels.SelectionKey._
import akka.io.Inet.SocketOption

class TcpConnectionSpec extends AkkaSpec("akka.io.tcp.register-timeout = 500ms") {
  val serverAddress = temporaryServerAddress()

  // Helper to avoid Windows localization specific differences
  def ignoreIfWindows(): Unit = {
    if (Helpers.isWindows) {
      info("Detected Windows: ignoring check")
      pending
    }
  }

  lazy val ConnectionResetByPeerMessage: String = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.socket.bind(new InetSocketAddress("127.0.0.1", 0))

    try {
      val clientSocket = SocketChannel.open(new InetSocketAddress("127.0.0.1", serverSocket.socket().getLocalPort))
      val clientSocketOnServer = acceptServerSideConnection(serverSocket)
      clientSocketOnServer.socket.setSoLinger(true, 0)
      clientSocketOnServer.close()
      clientSocket.read(ByteBuffer.allocate(1))
      null
    } catch {
      case NonFatal(e) ⇒ e.getMessage
    }
  }

  lazy val ConnectionRefusedMessagePrefix: String = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.socket.bind(new InetSocketAddress("127.0.0.1", 0))

    try {
      serverSocket.close()
      val clientSocket = SocketChannel.open(new InetSocketAddress("127.0.0.1", serverSocket.socket().getLocalPort))
      clientSocket.finishConnect()
      clientSocket.write(ByteBuffer.allocate(1))
      null
    } catch {
      case NonFatal(e) ⇒ e.getMessage.substring(0, 15)
    }
  }

  "An outgoing connection" must {
    info("Connection reset by peer message expected is " + ConnectionResetByPeerMessage)
    info("Connection refused message prefix expected is " + ConnectionRefusedMessagePrefix)
    // common behavior

    "set socket options before connecting" in withLocalServer() { localServer ⇒
      val userHandler = TestProbe()
      val selector = TestProbe()
      val connectionActor =
        createConnectionActor(options = Vector(Inet.SO.ReuseAddress(true)))(selector.ref, userHandler.ref)
      val clientChannel = connectionActor.underlyingActor.channel
      clientChannel.socket.getReuseAddress must be(true)
    }

    "set socket options after connecting" ignore withLocalServer() { localServer ⇒
      // Workaround for systems where SO_KEEPALIVE is true by default
      val userHandler = TestProbe()
      val selector = TestProbe()
      val connectionActor =
        createConnectionActor(options = Vector(SO.KeepAlive(false)))(selector.ref, userHandler.ref)
      val clientChannel = connectionActor.underlyingActor.channel
      clientChannel.socket.getKeepAlive must be(true) // only set after connection is established
      EventFilter.warning(pattern = "registration timeout", occurrences = 1) intercept {
        selector.send(connectionActor, ChannelConnectable)
        clientChannel.socket.getKeepAlive must be(false)
      }
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

    "forward incoming data as Received messages instantly as long as more data is available" in withEstablishedConnection(
      clientSocketOptions = List(Inet.SO.ReceiveBufferSize(1000000)) // to make sure enough data gets through
      ) { setup ⇒
        import setup._

        val bufferSize = Tcp(system).Settings.DirectBufferSize
        val DataSize = bufferSize + 1500
        val bigData = new Array[Byte](DataSize)
        val buffer = ByteBuffer.wrap(bigData)

        serverSideChannel.socket.setSendBufferSize(150000)
        val wrote = serverSideChannel.write(buffer)
        wrote must be(DataSize)

        expectNoMsg(1000.millis) // data should have been transferred fully by now

        selector.send(connectionActor, ChannelReadable)

        connectionHandler.expectMsgType[Received].data.length must be(bufferSize)
        connectionHandler.expectMsgType[Received].data.length must be(1500)
      }

    "receive data directly when the connection is established" in withUnacceptedConnection() { unregisteredSetup ⇒
      import unregisteredSetup._

      val serverSideChannel = acceptServerSideConnection(localServer)

      serverSideChannel.write(ByteBuffer.wrap("immediatedata".getBytes("ASCII")))
      serverSideChannel.configureBlocking(false)

      selector.send(connectionActor, ChannelConnectable)
      userHandler.expectMsg(Connected(serverAddress, clientSideChannel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress]))

      // we unrealistically register the selector here so that we can observe
      // the ordering between Received and ReadInterest
      userHandler.send(connectionActor, Register(selector.ref))
      selector.expectMsgType[Received].data.decodeString("ASCII") must be("immediatedata")
      selector.expectMsg(ReadInterest)
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

    "write data after not acknowledged data" in withEstablishedConnection() { setup ⇒
      import setup._

      object Ack
      val writer = TestProbe()
      writer.send(connectionActor, Write(ByteString(42.toByte)))
      writer.expectNoMsg(500.millis)

      writer.send(connectionActor, Write(ByteString.empty, Ack))
      writer.expectMsg(Ack)
    }

    "write file to network" in withEstablishedConnection() { setup ⇒
      import setup._

      // hacky: we need a file for testing purposes, so try to get the biggest one from our own classpath
      val testFile =
        classOf[TcpConnectionSpec].getClassLoader.asInstanceOf[URLClassLoader]
          .getURLs
          .filter(_.getProtocol == "file")
          .map(url ⇒ new File(url.toURI))
          .filter(_.exists)
          .sortBy(-_.length)
          .head

      // maximum of 100 MB
      val size = math.min(testFile.length(), 100000000).toInt

      object Ack
      val writer = TestProbe()
      writer.send(connectionActor, WriteFile(testFile.getAbsolutePath, 0, size, Ack))
      pullFromServerSide(size, 1000000)
      writer.expectMsg(Ack)
    }

    /*
     * Disabled on Windows: http://support.microsoft.com/kb/214397
     *
     * "To optimize performance at the application layer, Winsock copies data buffers from application send calls
     * to a Winsock kernel buffer. Then, the stack uses its own heuristics (such as Nagle algorithm) to determine
     * when to actually put the packet on the wire. You can change the amount of Winsock kernel buffer allocated to
     * the socket using the SO_SNDBUF option (it is 8K by default). If necessary, Winsock can buffer significantly more
     * than the SO_SNDBUF buffer size. In most cases, the send completion in the application only indicates the data
     * buffer in an application send call is copied to the Winsock kernel buffer and does not indicate that the data
     * has hit the network medium. The only exception is when you disable the Winsock buffering by setting
     * SO_SNDBUF to 0."
     */
    "stop writing in cases of backpressure and resume afterwards" in
      withEstablishedConnection(clientSocketOptions = List(SO.ReceiveBufferSize(1000000))) { setup ⇒
        info("Currently ignored as SO_SNDBUF is usually a lower bound on the send buffer so the test fails as no real " +
          "backpressure present.")
        pending
        ignoreIfWindows()
        import setup._
        object Ack1
        object Ack2

        clientSideChannel.socket.setSendBufferSize(1024)

        awaitCond(clientSideChannel.socket.getSendBufferSize == 1024)

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
      selector.expectMsg(DisableReadInterest)
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

      serverSelectionKey must be(selectedAs(SelectionKey.OP_READ, 2.seconds))

      val buffer = ByteBuffer.allocate(1)
      serverSideChannel.read(buffer) must be(-1)
    }

    "send only one `Closed` event to the handler, if the handler commanded the Close" in withEstablishedConnection() { setup ⇒
      import setup._

      connectionHandler.send(connectionActor, Close)
      connectionHandler.expectMsg(Closed)
      connectionHandler.expectNoMsg(500.millis)
    }

    "abort the connection and reply with `Aborted` upon reception of an `Abort` command" in withEstablishedConnection() { setup ⇒
      import setup._

      connectionHandler.send(connectionActor, Abort)
      connectionHandler.expectMsg(Aborted)

      assertThisConnectionActorTerminated()

      val buffer = ByteBuffer.allocate(1)
      val thrown = evaluating { serverSideChannel.read(buffer) } must produce[IOException]
      thrown.getMessage must be(ConnectionResetByPeerMessage)
    }

    /*
     * Partly disabled on Windows: http://support.microsoft.com/kb/214397
     *
     * "To optimize performance at the application layer, Winsock copies data buffers from application send calls
     * to a Winsock kernel buffer. Then, the stack uses its own heuristics (such as Nagle algorithm) to determine
     * when to actually put the packet on the wire. You can change the amount of Winsock kernel buffer allocated to
     * the socket using the SO_SNDBUF option (it is 8K by default). If necessary, Winsock can buffer significantly more
     * than the SO_SNDBUF buffer size. In most cases, the send completion in the application only indicates the data
     * buffer in an application send call is copied to the Winsock kernel buffer and does not indicate that the data
     * has hit the network medium. The only exception is when you disable the Winsock buffering by setting
     * SO_SNDBUF to 0."
     */
    "close the connection and reply with `ConfirmedClosed` upon reception of an `ConfirmedClose` command (simplified)" in withEstablishedConnection(setSmallRcvBuffer) { setup ⇒
      import setup._

      // we should test here that a pending write command is properly finished first
      object Ack
      // set an artificially small send buffer size so that the write is queued
      // inside the connection actor
      clientSideChannel.socket.setSendBufferSize(1024)

      // we send a write and a close command directly afterwards
      connectionHandler.send(connectionActor, writeCmd(Ack))
      connectionHandler.send(connectionActor, ConfirmedClose)

      pullFromServerSide(TestSize)
      connectionHandler.expectMsg(Ack)

      selector.send(connectionActor, ChannelReadable)

      val buffer = ByteBuffer.allocate(1)
      serverSelectionKey must be(selectedAs(SelectionKey.OP_READ, 2.seconds))
      serverSideChannel.read(buffer) must be(-1)

      closeServerSideAndWaitForClientReadable()

      selector.send(connectionActor, ChannelReadable)
      connectionHandler.expectMsg(ConfirmedClosed)

      assertThisConnectionActorTerminated()
    }

    "close the connection and reply with `ConfirmedClosed` upon reception of an `ConfirmedClose` command" in withEstablishedConnection(setSmallRcvBuffer) { setup ⇒
      ignoreIfWindows()
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
      serverSelectionKey must be(selectedAs(SelectionKey.OP_READ, 2.seconds))
      serverSideChannel.read(buffer) must be(-1)

      closeServerSideAndWaitForClientReadable()

      selector.send(connectionActor, ChannelReadable)
      connectionHandler.expectMsg(ConfirmedClosed)

      assertThisConnectionActorTerminated()
    }

    "report when peer closed the connection" in withEstablishedConnection() { setup ⇒
      import setup._

      closeServerSideAndWaitForClientReadable()

      selector.send(connectionActor, ChannelReadable)
      connectionHandler.expectMsg(PeerClosed)

      assertThisConnectionActorTerminated()
    }
    "report when peer closed the connection but allow further writes and acknowledge normal close" in withEstablishedConnection(keepOpenOnPeerClosed = true) { setup ⇒
      import setup._

      closeServerSideAndWaitForClientReadable(fullClose = false) // send EOF (fin) from the server side

      selector.send(connectionActor, ChannelReadable)
      connectionHandler.expectMsg(PeerClosed)
      object Ack
      connectionHandler.send(connectionActor, writeCmd(Ack))
      pullFromServerSide(TestSize)
      connectionHandler.expectMsg(Ack)
      connectionHandler.send(connectionActor, Close)
      connectionHandler.expectMsg(Closed)

      assertThisConnectionActorTerminated()
    }
    "report when peer closed the connection but allow further writes and acknowledge confirmed close" in withEstablishedConnection(keepOpenOnPeerClosed = true) { setup ⇒
      import setup._

      closeServerSideAndWaitForClientReadable(fullClose = false) // send EOF (fin) from the server side

      selector.send(connectionActor, ChannelReadable)
      connectionHandler.expectMsg(PeerClosed)
      object Ack
      connectionHandler.send(connectionActor, writeCmd(Ack))
      pullFromServerSide(TestSize)
      connectionHandler.expectMsg(Ack)
      connectionHandler.send(connectionActor, ConfirmedClose)
      connectionHandler.expectMsg(ConfirmedClosed)

      assertThisConnectionActorTerminated()
    }

    "report when peer aborted the connection" in withEstablishedConnection() { setup ⇒
      import setup._

      EventFilter[IOException](occurrences = 1) intercept {
        abortClose(serverSideChannel)
        selector.send(connectionActor, ChannelReadable)
        val err = connectionHandler.expectMsgType[ErrorClosed]
        err.cause must be(ConnectionResetByPeerMessage)
      }
      // wait a while
      connectionHandler.expectNoMsg(200.millis)

      assertThisConnectionActorTerminated()
    }

    "report when peer closed the connection when trying to write" in withEstablishedConnection() { setup ⇒
      import setup._

      val writer = TestProbe()

      abortClose(serverSideChannel)
      EventFilter[IOException](occurrences = 1) intercept {
        writer.send(connectionActor, Write(ByteString("testdata")))
        // bother writer and handler should get the message
        writer.expectMsgType[ErrorClosed]
      }
      connectionHandler.expectMsgType[ErrorClosed]

      assertThisConnectionActorTerminated()
    }

    // This tets is disabled on windows, as the assumption that not calling accept on a server socket means that
    // no TCP level connection has been established with the client does not hold.
    "report failed connection attempt while not accepted" in withUnacceptedConnection() { setup ⇒
      import setup._
      ignoreIfWindows

      // close instead of accept
      localServer.close()

      EventFilter[SocketException](occurrences = 1) intercept {
        selector.send(connectionActor, ChannelConnectable)
        userHandler.expectMsg(CommandFailed(Connect(serverAddress)))
      }

      verifyActorTermination(connectionActor)
    }

    val UnboundAddress = temporaryServerAddress()

    "report failed connection attempt when target is unreachable" in
      withUnacceptedConnection(connectionActorCons = createConnectionActor(serverAddress = UnboundAddress)) { setup ⇒
        import setup._

        val sel = SelectorProvider.provider().openSelector()
        val key = clientSideChannel.register(sel, SelectionKey.OP_CONNECT | SelectionKey.OP_READ)
        // This timeout should be large enough to work on Windows
        sel.select(3000)

        key.isConnectable must be(true)
        EventFilter[ConnectException](occurrences = 1) intercept {
          selector.send(connectionActor, ChannelConnectable)
          userHandler.expectMsg(CommandFailed(Connect(UnboundAddress)))
        }

        verifyActorTermination(connectionActor)
      }

    "time out when Connected isn't answered with Register" in withUnacceptedConnection() { setup ⇒
      import setup._

      localServer.accept()

      EventFilter.warning(pattern = "registration timeout", occurrences = 1) intercept {
        selector.send(connectionActor, ChannelConnectable)
        userHandler.expectMsg(Connected(serverAddress, clientSideChannel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress]))

        verifyActorTermination(connectionActor)
      }
    }

    "close the connection when user handler dies while connecting" in withUnacceptedConnection() { setup ⇒
      import setup._

      EventFilter[DeathPactException](occurrences = 1) intercept {
        userHandler.ref ! PoisonPill

        verifyActorTermination(connectionActor)
      }
    }

    "close the connection when connection handler dies while connected" in withEstablishedConnection() { setup ⇒
      import setup._
      watch(connectionHandler.ref)
      watch(connectionActor)
      EventFilter[DeathPactException](occurrences = 1) intercept {
        system.stop(connectionHandler.ref)
        val deaths = Set(expectMsgType[Terminated].actor, expectMsgType[Terminated].actor)
        deaths must be(Set(connectionHandler.ref, connectionActor))
      }
    }
  }

  def acceptServerSideConnection(localServer: ServerSocketChannel): SocketChannel = {
    @volatile var serverSideChannel: SocketChannel = null
    awaitCond {
      serverSideChannel = localServer.accept()
      serverSideChannel != null
    }
    serverSideChannel
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

    def closeServerSideAndWaitForClientReadable(fullClose: Boolean = true): Unit = {
      if (fullClose) serverSideChannel.close() else serverSideChannel.socket.shutdownOutput()
      checkFor(clientSelectionKey, SelectionKey.OP_READ, 3.seconds.toMillis.toInt) must be(true)
    }

    def registerChannel(channel: SocketChannel, name: String): SelectionKey = {
      val res = channel.register(nioSelector, 0)
      res.attach(name)
      res
    }

    def checkFor(key: SelectionKey, interest: Int, millis: Int = 100): Boolean =
      if (key.isValid) {
        key.interestOps(interest)
        nioSelector.selectedKeys().clear()
        val ret = nioSelector.select(millis)
        key.interestOps(0)

        ret > 0 && nioSelector.selectedKeys().contains(key) && key.isValid &&
          (key.readyOps() & interest) != 0
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
        throw new AssertionError("Pulling took too many loops,  remaining data: " + remaining)
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

    def selectedAs(interest: Int, duration: Duration): BeMatcher[SelectionKey] =
      new BeMatcher[SelectionKey] {
        def apply(key: SelectionKey) =
          MatchResult(
            checkFor(key, interest, duration.toMillis.toInt),
            "%s key was not selected for %s after %s" format (key.attachment(), interestsDesc(interest), duration),
            "%s key was selected for %s after %s" format (key.attachment(), interestsDesc(interest), duration))
      }

    val interestsNames = Seq(
      SelectionKey.OP_ACCEPT -> "accepting",
      SelectionKey.OP_CONNECT -> "connecting",
      SelectionKey.OP_READ -> "reading",
      SelectionKey.OP_WRITE -> "writing")
    def interestsDesc(interests: Int): String =
      interestsNames.filter(i ⇒ (i._1 & interests) != 0).map(_._2).mkString(", ")
  }
  private[io] def withUnacceptedConnection(
    setServerSocketOptions: ServerSocketChannel ⇒ Unit = _ ⇒ (),
    connectionActorCons: (ActorRef, ActorRef) ⇒ TestActorRef[TcpOutgoingConnection] = createConnectionActor())(body: UnacceptedSetup ⇒ Any): Unit =

    withLocalServer(setServerSocketOptions) { localServer ⇒
      val userHandler = TestProbe()
      val selector = TestProbe()
      val connectionActor = connectionActorCons(selector.ref, userHandler.ref)
      val clientSideChannel = connectionActor.underlyingActor.channel

      selector.expectMsg(RegisterChannel(clientSideChannel, OP_CONNECT))

      body {
        UnacceptedSetup(
          localServer,
          userHandler,
          selector,
          connectionActor,
          clientSideChannel)
      }
    }
  def withEstablishedConnection(
    setServerSocketOptions: ServerSocketChannel ⇒ Unit = _ ⇒ (),
    clientSocketOptions: immutable.Seq[SocketOption] = Nil,
    keepOpenOnPeerClosed: Boolean = false)(body: RegisteredSetup ⇒ Any): Unit = withUnacceptedConnection(setServerSocketOptions, createConnectionActor(options = clientSocketOptions)) { unregisteredSetup ⇒
    import unregisteredSetup._

    val serverSideChannel = acceptServerSideConnection(localServer)
    serverSideChannel.configureBlocking(false)

    serverSideChannel must not be (null)
    selector.send(connectionActor, ChannelConnectable)
    userHandler.expectMsg(Connected(serverAddress, clientSideChannel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress]))

    val connectionHandler = TestProbe()
    userHandler.send(connectionActor, Register(connectionHandler.ref, keepOpenOnPeerClosed))
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
    options: immutable.Seq[SocketOption] = Nil)(
      _selector: ActorRef,
      commander: ActorRef): TestActorRef[TcpOutgoingConnection] = {

    val ref = TestActorRef(
      new TcpOutgoingConnection(Tcp(system), commander, Connect(serverAddress, localAddress, options)) {
        override def postRestart(reason: Throwable) {
          // ensure we never restart
          context.stop(self)
        }
        override def selector = _selector
      })

    ref ! ChannelRegistered
    ref
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
