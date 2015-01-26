/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.io.Tcp._

import akka.stream.BindFailedException

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.util.ByteString
import akka.stream.scaladsl.Flow
import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
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
          .runFold(ByteString.empty)((acc, in) ⇒ acc ++ in)
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

      serverConnection.expectClosed(ConfirmedClosed)
      serverConnection.expectTerminated()
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
      serverConnection.expectTerminated()
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

      // Need a write on the server side to detect the close event
      serverConnection.write(testData)
      serverConnection.write(testData)
      serverConnection.expectClosed(_.isErrorClosed)
      serverConnection.expectTerminated()
    }

    "work when client closes write, then client closes read" in {
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

      // Need a write on the server side to detect the close event
      serverConnection.write(testData)
      serverConnection.write(testData)
      serverConnection.expectClosed(_.isErrorClosed)
      serverConnection.expectTerminated()
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
      serverConnection.expectTerminated()
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
      serverConnection.expectTerminated()
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
      serverConnection.expectTerminated()
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

      serverConnection.expectTerminated()
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
        Source(testInput).via(StreamTcp().outgoingConnection(serverAddress).flow).runFold(ByteString.empty)((acc, in) ⇒ acc ++ in)

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
          .runFold(ByteString.empty)((acc, in) ⇒ acc ++ in)

      Await.result(resultFuture, 5.seconds) should be(expectedOutput)
      Await.result(binding.unbind(echoServerMM), 3.seconds)
      Await.result(echoServerFinish, 1.second)
    }

    "bind and unbind correctly" in {
      val address = temporaryServerAddress()
      val binding = StreamTcp(system).bind(address)
      val probe1 = StreamTestKit.SubscriberProbe[StreamTcp.IncomingConnection]()
      val mm1 = binding.connections.to(Sink(probe1)).run()
      probe1.expectSubscription()

      // Bind succeeded, we have a local address
      Await.result(binding.localAddress(mm1), 1.second)

      val probe2 = StreamTestKit.SubscriberProbe[StreamTcp.IncomingConnection]()
      val mm2 = binding.connections.to(Sink(probe2)).run()
      probe2.expectErrorOrSubscriptionFollowedByError(BindFailedException)

      val probe3 = StreamTestKit.SubscriberProbe[StreamTcp.IncomingConnection]()
      val mm3 = binding.connections.to(Sink(probe3)).run()
      probe3.expectErrorOrSubscriptionFollowedByError()

      // The unbind should NOT fail even though the bind failed.
      Await.result(binding.unbind(mm2), 1.second)
      Await.result(binding.unbind(mm3), 1.second)

      an[BindFailedException] shouldBe thrownBy { Await.result(binding.localAddress(mm2), 1.second) }
      an[BindFailedException] shouldBe thrownBy { Await.result(binding.localAddress(mm3), 1.second) }

      // Now unbind first
      Await.result(binding.unbind(mm1), 1.second)
      probe1.expectComplete()

      val probe4 = StreamTestKit.SubscriberProbe[StreamTcp.IncomingConnection]()
      val mm4 = binding.connections.to(Sink(probe4)).run()
      probe4.expectSubscription()

      // Bind succeeded, we have a local address
      Await.result(binding.localAddress(mm4), 1.second)
      // clean up
      Await.result(binding.unbind(mm4), 1.second)
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
