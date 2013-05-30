/**
 * Copyright (C) 2013 Typesafe Inc. <http://www.typesafe.com>
 */

// adapted from
// https://github.com/spray/spray/blob/eef5c4f54a0cadaf9e98298faf5b337f9adc04bb/spray-io-tests/src/test/scala/spray/io/SslTlsSupportSpec.scala
// original copyright notice follows:

/*
 * Copyright (C) 2011-2013 spray.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.io.ssl

import java.io.{ BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter }
import java.net.{ InetSocketAddress, SocketException }
import java.security.{ KeyStore, SecureRandom }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import akka.TestUtils
import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Terminated }
import akka.event.{ Logging, LoggingAdapter }
import akka.io.{ BackpressureBuffer, DelimiterFraming, IO, SslTlsSupport, StringByteStringAdapter, Tcp }
import akka.io.TcpPipelineHandler
import akka.io.TcpPipelineHandler.{ Init, Management, WithinActorContext }
import akka.io.TcpReadWriteAdapter
import akka.remote.security.provider.AkkaProvider
import akka.testkit.{ AkkaSpec, TestProbe }
import akka.util.{ ByteString, Timeout }
import javax.net.ssl.{ KeyManagerFactory, SSLContext, SSLServerSocket, SSLSocket, TrustManagerFactory }
import akka.actor.Deploy

// TODO move this into akka-actor once AkkaProvider for SecureRandom does not have external dependencies
class SslTlsSupportSpec extends AkkaSpec {

  implicit val timeOut: Timeout = 1.second

  val sslContext = SslTlsSupportSpec.createSslContext("/keystore", "/truststore", "changeme")

  "The SslTlsSupport" should {

    "work between a Java client and a Java server" in {
      val server = new JavaSslServer
      val client = new JavaSslClient(server.address)
      client.run()
      client.close()
      server.close()
    }

    "work between a akka client and a Java server" in {
      val server = new JavaSslServer
      val client = new AkkaSslClient(server.address)
      client.run()
      client.close()
      server.close()
    }

    "work between a Java client and a akka server" in {
      val serverAddress = TestUtils.temporaryServerAddress()
      val probe = TestProbe()
      val bindHandler = probe.watch(system.actorOf(Props(new AkkaSslServer(serverAddress)).withDeploy(Deploy.local), "server1"))
      expectMsg(Tcp.Bound)

      val client = new JavaSslClient(serverAddress)
      client.run()
      client.close()
      probe.expectTerminated(bindHandler)
    }

    "work between a akka client and a akka server" in {
      val serverAddress = TestUtils.temporaryServerAddress()
      val probe = TestProbe()
      val bindHandler = probe.watch(system.actorOf(Props(new AkkaSslServer(serverAddress)).withDeploy(Deploy.local), "server2"))
      expectMsg(Tcp.Bound)

      val client = new AkkaSslClient(serverAddress)
      client.run()
      client.close()
      probe.expectTerminated(bindHandler)
    }
  }

  val counter = new AtomicInteger

  class AkkaSslClient(address: InetSocketAddress) {

    val probe = TestProbe()
    probe.send(IO(Tcp), Tcp.Connect(address))

    val connected = probe.expectMsgType[Tcp.Connected]
    val connection = probe.sender

    val init = TcpPipelineHandler.withLogger(system.log,
      new StringByteStringAdapter >>
        new DelimiterFraming(maxSize = 1024, delimiter = ByteString('\n'), includeDelimiter = true) >>
        new TcpReadWriteAdapter >>
        new SslTlsSupport(sslEngine(connected.remoteAddress, client = true)))

    import init._

    val handler = system.actorOf(TcpPipelineHandler.props(init, connection, probe.ref).withDeploy(Deploy.local),
      "client" + counter.incrementAndGet())
    probe.send(connection, Tcp.Register(handler))

    def run() {
      probe.send(handler, Command("3+4\n"))
      probe.expectMsg(Event("7\n"))
      probe.send(handler, Command("20+22\n"))
      probe.expectMsg(Event("42\n"))
      probe.send(handler, Command("12+24\n11+1"))
      Thread.sleep(1000) // Exercise framing by waiting at a mid-frame point
      probe.send(handler, Command("1\n0+0\n"))
      probe.expectMsg(Event("36\n"))
      probe.expectMsg(Event("22\n"))
      probe.expectMsg(Event("0\n"))
    }

    def close() {
      probe.send(handler, Management(Tcp.Close))
      probe.expectMsgType[Tcp.ConnectionClosed]
      TestUtils.verifyActorTermination(handler)
    }

  }

  //#server
  class AkkaSslServer(local: InetSocketAddress) extends Actor with ActorLogging {

    import Tcp._

    implicit def system = context.system
    IO(Tcp) ! Bind(self, local)

    def receive: Receive = {
      case _: Bound ⇒
        context.become(bound(sender))
        //#server
        testActor ! Bound
      //#server
    }

    def bound(listener: ActorRef): Receive = {
      case Connected(remote, _) ⇒
        val init = TcpPipelineHandler.withLogger(log,
          new StringByteStringAdapter("utf-8") >>
            new DelimiterFraming(maxSize = 1024, delimiter = ByteString('\n'),
              includeDelimiter = true) >>
            new TcpReadWriteAdapter >>
            new SslTlsSupport(sslEngine(remote, client = false)) >>
            new BackpressureBuffer(lowBytes = 100, highBytes = 1000, maxBytes = 1000000))

        val connection = sender
        val handler = context.actorOf(Props(new AkkaSslHandler(init)).withDeploy(Deploy.local))
        //#server
        context watch handler
        //#server
        val pipeline = context.actorOf(TcpPipelineHandler.props(
          init, sender, handler).withDeploy(Deploy.local))

        connection ! Tcp.Register(pipeline)
      //#server
      case _: Terminated ⇒
        listener ! Unbind
        context.become {
          case Unbound ⇒ context stop self
        }
      //#server
    }
  }
  //#server

  //#handler
  class AkkaSslHandler(init: Init[WithinActorContext, String, String])
    extends Actor with ActorLogging {

    def receive = {
      case init.Event(data) ⇒
        val input = data.dropRight(1)
        log.debug("akka-io Server received {} from {}", input, sender)
        val response = serverResponse(input)
        sender ! init.Command(response)
        log.debug("akka-io Server sent: {}", response.dropRight(1))
      case _: Tcp.ConnectionClosed ⇒ context.stop(self)
    }
  }
  //#handler

  class JavaSslServer extends Thread {
    val log: LoggingAdapter = Logging(system, getClass)
    val address = TestUtils.temporaryServerAddress()
    private val serverSocket =
      sslContext.getServerSocketFactory.createServerSocket(address.getPort).asInstanceOf[SSLServerSocket]
    @volatile private var socket: SSLSocket = _
    start()

    def close() {
      serverSocket.close()
      if (socket != null) socket.close()
    }

    override def run() {
      try {
        socket = serverSocket.accept().asInstanceOf[SSLSocket]
        val (reader, writer) = readerAndWriter(socket)
        while (true) {
          val line = reader.readLine()
          log.debug("SSLServerSocket Server received: {}", line)
          if (line == null) throw new SocketException("closed")
          val result = serverResponse(line)
          writer.write(result)
          writer.flush()
          log.debug("SSLServerSocket Server sent: {}", result.dropRight(1))
        }
      } catch {
        case _: SocketException ⇒ // expected during shutdown
      } finally close()
    }
  }

  class JavaSslClient(address: InetSocketAddress) {
    val socket = sslContext.getSocketFactory.createSocket(address.getHostName, address.getPort).asInstanceOf[SSLSocket]
    val (reader, writer) = readerAndWriter(socket)
    val log: LoggingAdapter = Logging(system, getClass)

    def run() {
      write("1+2")
      readLine() must be === "3"
      write("12+24")
      readLine() must be === "36"
    }

    def write(string: String) {
      writer.write(string + "\n")
      writer.flush()
      log.debug("SSLSocket Client sent: {}", string)
    }

    def readLine() = {
      val string = reader.readLine()
      log.debug("SSLSocket Client received: {}", string)
      string
    }

    def close() { socket.close() }
  }

  def readerAndWriter(socket: SSLSocket) = {
    val reader = new BufferedReader(new InputStreamReader(socket.getInputStream))
    val writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream))
    reader -> writer
  }

  def serverResponse(input: String): String = input.split('+').map(_.toInt).reduceLeft(_ + _).toString + '\n'

  def sslEngine(address: InetSocketAddress, client: Boolean) = {
    val engine = sslContext.createSSLEngine(address.getHostName, address.getPort)
    engine.setUseClientMode(client)
    engine
  }

}

object SslTlsSupportSpec {

  def createSslContext(keyStoreResource: String, trustStoreResource: String, password: String): SSLContext = {
    val keyStore = KeyStore.getInstance("jks")
    keyStore.load(getClass.getResourceAsStream(keyStoreResource), password.toCharArray)
    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(keyStore, password.toCharArray)
    val trustStore = KeyStore.getInstance("jks")
    trustStore.load(getClass.getResourceAsStream(trustStoreResource), password.toCharArray())
    val trustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    trustManagerFactory.init(trustStore)
    val context = SSLContext.getInstance("SSL")
    val rng = SecureRandom.getInstance("AES128CounterSecureRNG", AkkaProvider)
    rng.nextInt() // if it stalls then it stalls here
    context.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, rng)
    context
  }

}