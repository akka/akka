/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.{ OutputStreamWriter, BufferedWriter, InputStreamReader, BufferedReader }
import java.net.InetSocketAddress
import akka.actor.{ ActorSystem, Props }
import akka.stream.io.SslTlsCipher.SessionNegotiation
import akka.stream.io.StreamTcp.{ OutgoingTcpConnection, TcpServerBinding }
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.testkit.TestProbe
import akka.util.ByteString
import java.security.{ KeyStore, SecureRandom }
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl._
import sun.rmi.transport.tcp.TCPConnection
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Sink

object SslTlsFlowSpec {

  import TcpHelper._

  def initSslContext(): SSLContext = {

    val password = "changeme"

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(getClass.getResourceAsStream("/keystore"), password.toCharArray)

    val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)
    trustStore.load(getClass.getResourceAsStream("/truststore"), password.toCharArray)

    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(keyStore, password.toCharArray)

    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(trustStore)

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)
    context
  }

  private val clientServerId = new AtomicInteger(0)

  def createClientCipher(context: SSLContext)(implicit system: ActorSystem): SslTlsCipher =
    createClientCipher(context, clientServerId.incrementAndGet())

  def createClientCipher(context: SSLContext, id: Int)(implicit system: ActorSystem): SslTlsCipher = {
    val cengine = context.createSSLEngine
    cengine.setEnabledCipherSuites(Array("TLS_RSA_WITH_AES_128_CBC_SHA"))
    cengine.setUseClientMode(true)

    val requester = TestProbe()
    system.actorOf(Props(classOf[SslTlsCipherActor], requester.ref, SessionNegotiation(cengine)), s"ssl-client-$id")
    requester.expectMsgType[SslTlsCipher]
  }

  def createServerCipher(context: SSLContext)(implicit system: ActorSystem): SslTlsCipher =
    createServerCipher(context, clientServerId.incrementAndGet())

  def createServerCipher(context: SSLContext, id: Int)(implicit system: ActorSystem): SslTlsCipher = {
    val sengine = context.createSSLEngine
    sengine.setEnabledCipherSuites(Array("TLS_RSA_WITH_AES_128_CBC_SHA"))
    sengine.setUseClientMode(false)

    val requester = TestProbe()
    system.actorOf(Props(classOf[SslTlsCipherActor], requester.ref, SessionNegotiation(sengine)), s"ssl-server-$id")
    requester.expectMsgType[SslTlsCipher]
  }

  def createClientServerCipherPair(implicit system: ActorSystem): (SslTlsCipher, SslTlsCipher) = {
    val context = initSslContext()
    val id = clientServerId.incrementAndGet()
    val ccipher = createClientCipher(context, id)
    val scipher = createServerCipher(context, id)

    (ccipher, scipher)
  }

  class JavaSslConnection(socket: SSLSocket) {
    private val reader = new BufferedReader(new InputStreamReader(socket.getInputStream))
    private val writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream))
    def readLn() = reader.readLine()
    def writeLn(msg: String) = {
      writer.write(msg + '\n')
      writer.flush()
    }
    def close(): Unit = socket.close()
  }

  def newJavaSslClientConnection(context: SSLContext, address: InetSocketAddress): JavaSslConnection =
    new JavaSslConnection(context.getSocketFactory.createSocket(address.getHostName, address.getPort).asInstanceOf[SSLSocket])

  class JavaSslServer(context: SSLContext) {
    val address = temporaryServerAddress
    private val serverSocket = context.getServerSocketFactory.createServerSocket(address.getPort).asInstanceOf[SSLServerSocket]
    def acceptOne() = new JavaSslConnection(serverSocket.accept().asInstanceOf[SSLSocket])
    def close(): Unit = serverSocket.close()
  }
}

class SslTlsFlowSpec extends AkkaSpec("akka.actor.default-mailbox.mailbox-type = akka.dispatch.UnboundedMailbox") with TcpHelper {
  import akka.stream.io.SslTlsFlowSpec._

  val duration = 5.seconds

  def concurrently(block1: ⇒ Unit, block2: ⇒ String): String = {
    import scala.concurrent.ExecutionContext.Implicits.global
    Await.result(Future.sequence(List(Future(block1), Future(block2))), duration)(1).asInstanceOf[String]
  }

  def replyFirstLineInUpperCase(scipher: SslTlsCipher): Unit = {
    val ssessionf = Source(scipher.sessionInbound).runWith(Sink.head)
    val ssession = Await.result(ssessionf, duration)
    val sdata = ssession.data
    Source(sdata).map(bs ⇒ ByteString(bs.decodeString("utf-8").split('\n').head.toUpperCase + '\n')).
      runWith(Sink(scipher.plainTextOutbound))
  }

  def replyFirstLineInUpperCase(clientConnection: JavaSslConnection): Unit = {
    clientConnection.writeLn(clientConnection.readLn().toUpperCase())
  }

  def sendLineAndReceiveResponse(ccipher: SslTlsCipher, message: String): String = {
    val csessionf = Source(ccipher.sessionInbound).runWith(Sink.head)
    Source(List(ByteString(message + '\n'))).runWith(Sink(ccipher.plainTextOutbound))
    val csession = Await.result(csessionf, duration)
    val cdata = csession.data
    Await.result(Source(cdata).map(_.decodeString("utf-8").split('\n').head).runWith(Sink.head), duration)
  }

  def sendLineAndReceiveResponse(connection: JavaSslConnection, message: String): String = {
    connection.writeLn(message)
    val result = connection.readLn()
    result
  }

  def sendBackAndForthAndValidateReply(ccipher: SslTlsCipher, scipher: SslTlsCipher, message: String): Unit = {
    val result = concurrently(replyFirstLineInUpperCase(scipher), sendLineAndReceiveResponse(ccipher, message))
    result should be(message.toUpperCase)
  }

  def connectIncomingConnection(serverBinding: TcpServerBinding, scipher: SslTlsCipher): Unit = {
    // connect the incoming tcp stream to the server cipher
    Source(serverBinding.connectionStream).foreach {
      case StreamTcp.IncomingTcpConnection(remoteAddress, inputStream, outputStream) ⇒
        scipher.cipherTextOutbound.subscribe(outputStream)
        inputStream.subscribe(scipher.cipherTextInbound)
    }
  }

  def connectOutgoingConnection(clientConnection: OutgoingTcpConnection, ccipher: SslTlsCipher): Unit = {
    // connect the outgoing tcp stream to the client cipher
    ccipher.cipherTextOutbound.subscribe(clientConnection.outputStream)
    clientConnection.inputStream.subscribe(ccipher.cipherTextInbound)
  }
  // Only here for SSL debug convenience
  //System.setProperty("javax.net.debug", "all")

  "SslTls Cipher" must {

    "work on a simple stream" in {
      val (ccipher, scipher) = createClientServerCipherPair(system)

      // connect two ciphers directly
      ccipher.cipherTextOutbound.subscribe(scipher.cipherTextInbound)
      scipher.cipherTextOutbound.subscribe(ccipher.cipherTextInbound)

      sendBackAndForthAndValidateReply(ccipher, scipher, "I'm the simple stream client!")
    }

    "work on a simple stream over TCP" in {
      val (ccipher, scipher) = createClientServerCipherPair(system)

      val serverBinding = bind()
      val clientConnection = connect(serverBinding.localAddress)

      connectIncomingConnection(serverBinding, scipher)

      connectOutgoingConnection(clientConnection, ccipher)

      sendBackAndForthAndValidateReply(ccipher, scipher, "I'm the TCP stream client!")
    }

    "work on a simple stream over TCP between java client and stream server" in {
      val message = "I'm the TCP java client and stream server"
      val context = initSslContext()
      val scipher = createServerCipher(context)
      val serverBinding = bind()

      connectIncomingConnection(serverBinding, scipher)

      val clientConnection = newJavaSslClientConnection(context, serverBinding.localAddress)

      val result = concurrently(replyFirstLineInUpperCase(scipher), sendLineAndReceiveResponse(clientConnection, message))
      clientConnection.close()
      result should be(message.toUpperCase)
    }

    "work on a simple stream over TCP between stream client and java server" in {
      val message = "I'm the TCP stream client and java server"
      val context = initSslContext()
      val ccipher = createClientCipher(context)

      val server = new JavaSslServer(context)
      val clientConnection = connect(server.address)
      val serverConnection = server.acceptOne()

      connectOutgoingConnection(clientConnection, ccipher)

      val result = concurrently(replyFirstLineInUpperCase(serverConnection), sendLineAndReceiveResponse(ccipher, message))
      serverConnection.close()
      result should be(message.toUpperCase)
    }
  }
}
