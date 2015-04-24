/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.stream.scaladsl.StreamTcp.OutgoingConnection

import scala.concurrent.{ Future, Await }
import akka.io.Tcp._

import akka.stream.BindFailedException

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.util.ByteString
import akka.stream.scaladsl.Flow
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.stream.scaladsl._
import akka.stream.testkit.TestUtils.temporaryServerAddress

class StreamTcpSpec extends AkkaSpec with TcpHelper {
  import akka.stream.io.TcpHelper._
  var demand = 0L

  "Outgoing TCP stream" must {

    "work in the happy case" in assertAllStagesStopped {
      val testData = ByteString(1, 2, 3, 4, 5)

      val server = new Server()

      val tcpReadProbe = new TcpReadProbe()
      val tcpWriteProbe = new TcpWriteProbe()
      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address)).to(Sink(tcpReadProbe.subscriberProbe)).run()
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

      Source(testInput).via(StreamTcp().outgoingConnection(server.address)).to(Sink.ignore).run()

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
          .via(StreamTcp().outgoingConnection(server.address))
          .runFold(ByteString.empty)((acc, in) ⇒ acc ++ in)
      val serverConnection = server.waitAccept()

      for (in ← testInput) {
        serverConnection.write(in)
      }

      serverConnection.confirmedClose()
      Await.result(resultFuture, 3.seconds) should be(expectedOutput)

    }

    "work when client closes write, then remote closes write" in assertAllStagesStopped {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()
      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address)).to(Sink(tcpReadProbe.subscriberProbe)).run()
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

    "work when remote closes write, then client closes write" in assertAllStagesStopped {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()
      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address)).to(Sink(tcpReadProbe.subscriberProbe)).run()
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

    "work when client closes read, then client closes write" in assertAllStagesStopped {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()
      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address)).to(Sink(tcpReadProbe.subscriberProbe)).run()
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
      awaitAssert({
        serverConnection.write(testData)
        serverConnection.expectClosed(_.isErrorClosed, 500.millis)
      }, max = 5.seconds)
      serverConnection.expectTerminated()
    }

    "work when client closes write, then client closes read" in assertAllStagesStopped {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()
      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address)).to(Sink(tcpReadProbe.subscriberProbe)).run()
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
      awaitAssert({
        serverConnection.write(testData)
        serverConnection.expectClosed(_.isErrorClosed, 500.millis)
      }, max = 5.seconds)
      serverConnection.expectTerminated()
    }

    "work when client closes read, then server closes write, then client closes write" in assertAllStagesStopped {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()
      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address)).to(Sink(tcpReadProbe.subscriberProbe)).run()
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

    "shut everything down if client signals error" in assertAllStagesStopped {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()

      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address)).to(Sink(tcpReadProbe.subscriberProbe)).run()
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

    "shut everything down if client signals error after remote has closed write" in assertAllStagesStopped {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()

      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address)).to(Sink(tcpReadProbe.subscriberProbe)).run()
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

    "shut down both streams when connection is aborted remotely" in assertAllStagesStopped {
      // Client gets a PeerClosed event and does not know that the write side is also closed
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()

      Source(tcpWriteProbe.publisherProbe).via(StreamTcp().outgoingConnection(server.address)).to(Sink(tcpReadProbe.subscriberProbe)).run()
      val serverConnection = server.waitAccept()

      serverConnection.abort()
      tcpReadProbe.subscriberProbe.expectSubscriptionAndError()
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

      val conn1F =
        Source(tcpWriteProbe1.publisherProbe)
          .viaMat(outgoingConnection)(Keep.right)
          .to(Sink(tcpReadProbe1.subscriberProbe)).run()
      val serverConnection1 = server.waitAccept()
      val conn2F =
        Source(tcpWriteProbe2.publisherProbe)
          .viaMat(outgoingConnection)(Keep.right)
          .to(Sink(tcpReadProbe2.subscriberProbe))
          .run()
      val serverConnection2 = server.waitAccept()

      validateServerClientCommunication(testData, serverConnection1, tcpReadProbe1, tcpWriteProbe1)
      validateServerClientCommunication(testData, serverConnection2, tcpReadProbe2, tcpWriteProbe2)

      val conn1 = Await.result(conn1F, 1.seconds)
      val conn2 = Await.result(conn2F, 1.seconds)

      // Since we have already communicated over the connections we can have short timeouts for the futures
      conn1.remoteAddress.getPort should be(server.address.getPort)
      conn2.remoteAddress.getPort should be(server.address.getPort)
      conn1.localAddress.getPort should not be conn2.localAddress.getPort

      tcpWriteProbe1.close()
      tcpReadProbe1.close()

      server.close()
    }

  }

  "TCP listen stream" must {

    // Reusing handler
    val echoHandler = Sink.foreach[StreamTcp.IncomingConnection] { _.flow.join(Flow[ByteString]).run() }

    "be able to implement echo" in {
      val serverAddress = temporaryServerAddress()
      val (bindingFuture, echoServerFinish) =
        StreamTcp()
          .bind(serverAddress)
          .toMat(echoHandler)(Keep.both)
          .run()

      // make sure that the server has bound to the socket
      val binding = Await.result(bindingFuture, 100.millis)

      val testInput = (0 to 255).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))
      val resultFuture =
        Source(testInput).via(StreamTcp().outgoingConnection(serverAddress)).runFold(ByteString.empty)((acc, in) ⇒ acc ++ in)

      Await.result(resultFuture, 3.seconds) should be(expectedOutput)
      Await.result(binding.unbind(), 3.seconds)
      Await.result(echoServerFinish, 1.second)
    }

    "work with a chain of echoes" in {
      val serverAddress = temporaryServerAddress()
      val (bindingFuture, echoServerFinish) =
        StreamTcp()
          .bind(serverAddress)
          .toMat(echoHandler)(Keep.both)
          .run()

      // make sure that the server has bound to the socket
      val binding = Await.result(bindingFuture, 100.millis)

      val echoConnection = StreamTcp().outgoingConnection(serverAddress)

      val testInput = (0 to 255).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      val resultFuture =
        Source(testInput)
          .via(echoConnection) // The echoConnection is reusable
          .via(echoConnection)
          .via(echoConnection)
          .via(echoConnection)
          .runFold(ByteString.empty)((acc, in) ⇒ acc ++ in)

      Await.result(resultFuture, 3.seconds) should be(expectedOutput)
      Await.result(binding.unbind(), 3.seconds)
      Await.result(echoServerFinish, 1.second)
    }

    "bind and unbind correctly" in {
      val address = temporaryServerAddress()
      val probe1 = TestSubscriber.manualProbe[StreamTcp.IncomingConnection]()
      val bind = StreamTcp(system).bind(address)
      // Bind succeeded, we have a local address
      val binding1 = Await.result(bind.to(Sink(probe1)).run(), 3.second)

      probe1.expectSubscription()

      val probe2 = TestSubscriber.manualProbe[StreamTcp.IncomingConnection]()
      val binding2F = bind.to(Sink(probe2)).run()
      probe2.expectSubscriptionAndError(BindFailedException)

      val probe3 = TestSubscriber.manualProbe[StreamTcp.IncomingConnection]()
      val binding3F = bind.to(Sink(probe3)).run()
      probe3.expectSubscriptionAndError()

      an[BindFailedException] shouldBe thrownBy { Await.result(binding2F, 1.second) }
      an[BindFailedException] shouldBe thrownBy { Await.result(binding3F, 1.second) }

      // Now unbind first
      Await.result(binding1.unbind(), 1.second)
      probe1.expectComplete()

      val probe4 = TestSubscriber.manualProbe[StreamTcp.IncomingConnection]()
      // Bind succeeded, we have a local address
      val binding4 = Await.result(bind.to(Sink(probe4)).run(), 3.second)
      probe4.expectSubscription()

      // clean up
      Await.result(binding4.unbind(), 1.second)
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
