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

import akka.TestUtils
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.io._
import akka.remote.security.provider.AkkaProvider
import akka.testkit.{ TestProbe, AkkaSpec }
import akka.util.{ ByteString, Timeout }
import java.io.{ BufferedWriter, OutputStreamWriter, InputStreamReader, BufferedReader }
import java.net.{ InetSocketAddress, SocketException }
import java.security.{ KeyStore, SecureRandom }
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl._
import scala.concurrent.duration._
import akka.actor.{ Props, ActorLogging, Actor, ActorContext }

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
      val bindHandler = system.actorOf(Props(classOf[AkkaSslServer], this))
      val probe = TestProbe()
      probe.send(IO(Tcp), Tcp.Bind(bindHandler, serverAddress))
      probe.expectMsgType[Tcp.Bound]

      val client = new JavaSslClient(serverAddress)
      client.run()
      client.close()
    }

    "work between a akka client and a akka server" in {
      val serverAddress = TestUtils.temporaryServerAddress()
      val bindHandler = system.actorOf(Props(classOf[AkkaSslServer], this))
      val probe = TestProbe()
      probe.send(IO(Tcp), Tcp.Bind(bindHandler, serverAddress))
      probe.expectMsgType[Tcp.Bound]

      val client = new AkkaSslClient(serverAddress)
      client.run()
      client.close()
    }
  }

  val counter = new AtomicInteger

  class AkkaSslClient(address: InetSocketAddress) {

    val probe = TestProbe()
    probe.send(IO(Tcp), Tcp.Connect(address))

    val connected = probe.expectMsgType[Tcp.Connected]
    val connection = probe.sender

    val init = new TcpPipelineHandler.Init(
      new StringByteStringAdapter >>
        new DelimiterFraming(maxSize = 1024, delimiter = ByteString('\n'), includeDelimiter = true) >>
        new TcpReadWriteAdapter[HasLogging] >>
        new SslTlsSupport(sslEngine(connected.remoteAddress, client = true))) {
      override def makeContext(actorContext: ActorContext): HasLogging = new HasLogging {
        override def getLogger = system.log
      }
    }

    import init._

    val handler = system.actorOf(TcpPipelineHandler(init, connection, probe.ref),
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
      probe.send(handler, Tcp.Close)
      probe.expectMsgType[Tcp.Event] match {
        case _: Tcp.ConnectionClosed ⇒ true
      }
      TestUtils.verifyActorTermination(handler)
    }

  }

  //#server
  class AkkaSslServer extends Actor with ActorLogging {

    import Tcp.Connected

    def receive: Receive = {
      case Connected(remote, _) ⇒
        val init =
          new TcpPipelineHandler.Init(
            new StringByteStringAdapter >>
              new DelimiterFraming(maxSize = 1024, delimiter = ByteString('\n'), includeDelimiter = true) >>
              new TcpReadWriteAdapter[HasLogging] >>
              new SslTlsSupport(sslEngine(remote, client = false))) {
            override def makeContext(actorContext: ActorContext): HasLogging =
              new HasLogging {
                override def getLogger = log
              }
          }
        import init._

        val connection = sender
        val handler = system.actorOf(
          TcpPipelineHandler(init, sender, self), "server" + counter.incrementAndGet())

        connection ! Tcp.Register(handler)

        context become {
          case Event(data) ⇒
            val input = data.dropRight(1)
            log.debug("akka-io Server received {} from {}", input, sender)
            val response = serverResponse(input)
            sender ! Command(response)
            log.debug("akka-io Server sent: {}", response.dropRight(1))
        }
    }
  }
  //#server

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
      readLine() === "3"
      write("12+24")
      readLine() === "36"
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