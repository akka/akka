/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.io.Tcp._

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.util.ByteString
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.scaladsl._
import akka.stream.testkit.TestUtils.temporaryServerAddress

class StreamTcpSpec extends AkkaSpec with TcpHelper {
  import akka.stream.io.TcpHelper._
  var demand = 0L

  "Outgoing TCP stream" must {

    "work in the happy case" in {
      val testData = ByteString(1, 2, 3, 4, 5)

      val server = new Server()

      val tcpReadProbe = new TcpReadProbe()
      val tcpWriteProbe = new TcpWriteProbe()
      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address).flow).to(Sink(tcpReadProbe.subscriberProbe)).run()
      val serverConnection = server.waitAccept()

      validateServerClientCommunication(testData, serverConnection, tcpReadProbe, tcpWriteProbe)

      tcpWriteProbe.close()
      tcpReadProbe.close()

      server.close()
    }

    "be able to write a sequence of ByteStrings" in {
      val server = new Server()
      val testInput = (0 to 255).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      Source(testInput).via(StreamTcp().outgoingConnection(server.address).flow).to(Sink.ignore).run()

      val serverConnection = server.waitAccept()
      serverConnection.read(256)
      serverConnection.waitRead() should be(expectedOutput)
    }

    "be able to read a sequence of ByteStrings" in {
      val server = new Server()
      val testInput = (0 to 255).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      val idle = new TcpWriteProbe() // Just register an idle upstream
      val resultFuture =
        Source(idle.publisherProbe)
          .via(StreamTcp().outgoingConnection(server.address).flow)
          .fold(ByteString.empty)((acc, in) ⇒ acc ++ in)
      val serverConnection = server.waitAccept()

      for (in ← testInput) {
        serverConnection.write(in)
      }

      serverConnection.confirmedClose()
      Await.result(resultFuture, 3.seconds) should be(expectedOutput)

    }

    "work when client closes write, then remote closes write" in {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()
      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address).flow).to(Sink(tcpReadProbe.subscriberProbe)).run()
      val serverConnection = server.waitAccept()

      // Client can still write
      tcpWriteProbe.write(testData)
      serverConnection.read(5)
      serverConnection.waitRead() should be(testData)

      // Close client side write
      tcpWriteProbe.close()
      serverConnection.expectClosed(PeerClosed)

      // Server can still write
      serverConnection.write(testData)
      tcpReadProbe.read(5) should be(testData)

      // Close server side write
      serverConnection.confirmedClose()
      tcpReadProbe.subscriberProbe.expectComplete()
    }

    "work when remote closes write, then client closes write" in {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()
      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address).flow).to(Sink(tcpReadProbe.subscriberProbe)).run()
      val serverConnection = server.waitAccept()

      // Server can still write
      serverConnection.write(testData)
      tcpReadProbe.read(5) should be(testData)

      // Close server side write
      serverConnection.confirmedClose()
      tcpReadProbe.subscriberProbe.expectComplete()

      // Client can still write
      tcpWriteProbe.write(testData)
      serverConnection.read(5)
      serverConnection.waitRead() should be(testData)

      // Close client side write
      tcpWriteProbe.close()
      serverConnection.expectClosed(ConfirmedClosed)
    }

    "work when client closes read, then client closes write" in {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()
      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address).flow).to(Sink(tcpReadProbe.subscriberProbe)).run()
      val serverConnection = server.waitAccept()

      // Server can still write
      serverConnection.write(testData)
      tcpReadProbe.read(5) should be(testData)

      // Close client side read
      tcpReadProbe.tcpReadSubscription.cancel()

      // Client can still write
      tcpWriteProbe.write(testData)
      serverConnection.read(5)
      serverConnection.waitRead() should be(testData)

      // Close client side write
      tcpWriteProbe.close()
      // FIXME: Why not Closed?
      serverConnection.expectClosed(PeerClosed)
    }

    "work when client closes write, then client closes read" in {
      // FIXME: https://github.com/akka/akka/issues/16723
      // This test relies on being able to send a Close tothe TCP actor in the state after ConfirmClose
      // Until the above ticket is fixed, we can only send Abort here, but that results in RST packets, which is
      // an ErrorClosed on the remote side
      pending
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()
      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address).flow).to(Sink(tcpReadProbe.subscriberProbe)).run()
      val serverConnection = server.waitAccept()

      // Client can still write
      tcpWriteProbe.write(testData)
      serverConnection.read(5)
      serverConnection.waitRead() should be(testData)

      // Close client side write
      tcpWriteProbe.close()
      serverConnection.expectClosed(PeerClosed)

      // Server can still write
      serverConnection.write(testData)
      tcpReadProbe.read(5) should be(testData)

      // Close client side read
      tcpReadProbe.tcpReadSubscription.cancel()

      serverConnection.expectClosed(ConfirmedClosed)
    }

    "work when client closes read, then server closes write, then client closes write" in {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()
      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address).flow).to(Sink(tcpReadProbe.subscriberProbe)).run()
      val serverConnection = server.waitAccept()

      // Server can still write
      serverConnection.write(testData)
      tcpReadProbe.read(5) should be(testData)

      // Close client side read
      tcpReadProbe.tcpReadSubscription.cancel()

      // Client can still write
      tcpWriteProbe.write(testData)
      serverConnection.read(5)
      serverConnection.waitRead() should be(testData)

      serverConnection.confirmedClose()

      // Close client side write
      tcpWriteProbe.close()
      serverConnection.expectClosed(ConfirmedClosed)
    }

    "shut everything down if client signals error" in {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()

      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address).flow).to(Sink(tcpReadProbe.subscriberProbe)).run()
      val serverConnection = server.waitAccept()

      // Server can still write
      serverConnection.write(testData)
      tcpReadProbe.read(5) should be(testData)

      // Client can still write
      tcpWriteProbe.write(testData)
      serverConnection.read(5)
      serverConnection.waitRead() should be(testData)

      // Cause error
      tcpWriteProbe.tcpWriteSubscription.sendError(new IllegalStateException("test"))

      tcpReadProbe.subscriberProbe.expectError()
      serverConnection.expectClosed(_.isErrorClosed)
    }

    "shut everything down if client signals error after remote has closed write" in {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()

      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address).flow).to(Sink(tcpReadProbe.subscriberProbe)).run()
      val serverConnection = server.waitAccept()

      // Server can still write
      serverConnection.write(testData)
      tcpReadProbe.read(5) should be(testData)

      // Close remote side write
      serverConnection.confirmedClose()
      tcpReadProbe.subscriberProbe.expectComplete()

      // Client can still write
      tcpWriteProbe.write(testData)
      serverConnection.read(5)
      serverConnection.waitRead() should be(testData)

      tcpWriteProbe.tcpWriteSubscription.sendError(new IllegalStateException("test"))
      serverConnection.expectClosed(_.isErrorClosed)
    }

    "shut down both streams when connection is aborted remotely" in {
      // Client gets a PeerClosed event and does not know that the write side is also closed
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()

      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address).flow).to(Sink(tcpReadProbe.subscriberProbe)).run()
      val serverConnection = server.waitAccept()

      serverConnection.abort()
      tcpReadProbe.subscriberProbe.expectErrorOrSubscriptionFollowedByError()
      tcpWriteProbe.tcpWriteSubscription.expectCancellation()
    }

    "materialize correctly when used in multiple flows" in {
      val testData = ByteString(1, 2, 3, 4, 5)

      val server = new Server()

      val tcpReadProbe1 = new TcpReadProbe()
      val tcpWriteProbe1 = new TcpWriteProbe()
      val tcpReadProbe2 = new TcpReadProbe()
      val tcpWriteProbe2 = new TcpWriteProbe()
      val outgoingConnection = StreamTcp().outgoingConnection(server.address)

      val mm1 = Source(tcpWriteProbe1.publisherProbe).via(outgoingConnection.flow).to(Sink(tcpReadProbe1.subscriberProbe)).run()
      val serverConnection1 = server.waitAccept()
      val mm2 = Source(tcpWriteProbe2.publisherProbe).via(outgoingConnection.flow).to(Sink(tcpReadProbe2.subscriberProbe)).run()
      val serverConnection2 = server.waitAccept()

      validateServerClientCommunication(testData, serverConnection1, tcpReadProbe1, tcpWriteProbe1)
      validateServerClientCommunication(testData, serverConnection2, tcpReadProbe2, tcpWriteProbe2)
      // Since we have already communicated over the connections we can have short timeouts for the futures
      outgoingConnection.remoteAddress.getPort should be(server.address.getPort)
      val localAddress1 = Await.result(outgoingConnection.localAddress(mm1), 100.millis)
      val localAddress2 = Await.result(outgoingConnection.localAddress(mm2), 100.millis)
      localAddress1.getPort should not be localAddress2.getPort

      tcpWriteProbe1.close()
      tcpReadProbe1.close()

      server.close()
    }

  }

  "TCP listen stream" must {

    // Reusing handler
    val echoHandler = ForeachSink[StreamTcp.IncomingConnection] { _ handleWith Flow[ByteString] }

    "be able to implement echo" in {
      val serverAddress = temporaryServerAddress()
      val binding = StreamTcp().bind(serverAddress)
      val echoServerMM = binding.connections.to(echoHandler).run()

      val echoServerFinish = echoServerMM.get(echoHandler)

      // make sure that the server has bound to the socket
      Await.result(binding.localAddress(echoServerMM), 3.seconds)

      val testInput = (0 to 255).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))
      val resultFuture =
        Source(testInput).via(StreamTcp().outgoingConnection(serverAddress).flow).fold(ByteString.empty)((acc, in) ⇒ acc ++ in)

      Await.result(resultFuture, 3.seconds) should be(expectedOutput)
      Await.result(binding.unbind(echoServerMM), 3.seconds)
      Await.result(echoServerFinish, 1.second)
    }

    "work with a chain of echoes" in {
      val serverAddress = temporaryServerAddress()
      val binding = StreamTcp(system).bind(serverAddress)
      val echoServerMM = binding.connections.to(echoHandler).run()

      val echoServerFinish = echoServerMM.get(echoHandler)

      // make sure that the server has bound to the socket
      Await.result(binding.localAddress(echoServerMM), 3.seconds)

      val echoConnection = StreamTcp().outgoingConnection(serverAddress).flow

      val testInput = (0 to 255).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      val resultFuture =
        Source(testInput)
          .via(echoConnection) // The echoConnection is reusable
          .via(echoConnection)
          .via(echoConnection)
          .via(echoConnection)
          .fold(ByteString.empty)((acc, in) ⇒ acc ++ in)

      Await.result(resultFuture, 5.seconds) should be(expectedOutput)
      Await.result(binding.unbind(echoServerMM), 3.seconds)
      Await.result(echoServerFinish, 1.second)
    }

  }

  def validateServerClientCommunication(testData: ByteString,
                                        serverConnection: ServerConnection,
                                        readProbe: TcpReadProbe,
                                        writeProbe: TcpWriteProbe): Unit = {
    serverConnection.write(testData)
    serverConnection.read(5)
    readProbe.read(5) should be(testData)
    writeProbe.write(testData)
    serverConnection.waitRead() should be(testData)
  }
}
