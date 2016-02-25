/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.io

import akka.NotUsed
import akka.actor.{ ActorSystem, Kill }
import akka.io.Tcp._
import akka.stream.scaladsl.Tcp.IncomingConnection
import akka.stream.scaladsl.{ Flow, _ }
import akka.stream.testkit.TestUtils.temporaryServerAddress
import scala.util.control.NonFatal
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import akka.stream.{ ActorMaterializer, BindFailedException, StreamTcpException }
import akka.util.{ ByteString, Helpers }
import scala.collection.immutable
import scala.concurrent.{ Promise, Await }
import scala.concurrent.duration._
import java.net.BindException
import akka.testkit.EventFilter
import akka.testkit.AkkaSpec

class TcpSpec extends AkkaSpec("akka.stream.materializer.subscription-timeout.timeout = 2s") with TcpHelper {
  var demand = 0L

  "Outgoing TCP stream" must {

    "work in the happy case" in assertAllStagesStopped {
      val testData = ByteString(1, 2, 3, 4, 5)

      val server = new Server()

      val tcpReadProbe = new TcpReadProbe()
      val tcpWriteProbe = new TcpWriteProbe()
      Source.fromPublisher(tcpWriteProbe.publisherProbe).via(Tcp().outgoingConnection(server.address)).to(Sink.fromSubscriber(tcpReadProbe.subscriberProbe)).run()
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

      Source(testInput).via(Tcp().outgoingConnection(server.address)).to(Sink.ignore).run()

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
        Source.fromPublisher(idle.publisherProbe)
          .via(Tcp().outgoingConnection(server.address))
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
      Source.fromPublisher(tcpWriteProbe.publisherProbe).via(Tcp().outgoingConnection(server.address)).to(Sink.fromSubscriber(tcpReadProbe.subscriberProbe)).run()
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
      Source.fromPublisher(tcpWriteProbe.publisherProbe).via(Tcp().outgoingConnection(server.address)).to(Sink.fromSubscriber(tcpReadProbe.subscriberProbe)).run()
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
      Source.fromPublisher(tcpWriteProbe.publisherProbe).via(Tcp().outgoingConnection(server.address)).to(Sink.fromSubscriber(tcpReadProbe.subscriberProbe)).run()
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
      Source.fromPublisher(tcpWriteProbe.publisherProbe).via(Tcp().outgoingConnection(server.address)).to(Sink.fromSubscriber(tcpReadProbe.subscriberProbe)).run()
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
      Source.fromPublisher(tcpWriteProbe.publisherProbe).via(Tcp().outgoingConnection(server.address)).to(Sink.fromSubscriber(tcpReadProbe.subscriberProbe)).run()
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

      Source.fromPublisher(tcpWriteProbe.publisherProbe).via(Tcp().outgoingConnection(server.address)).to(Sink.fromSubscriber(tcpReadProbe.subscriberProbe)).run()
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

      Source.fromPublisher(tcpWriteProbe.publisherProbe).via(Tcp().outgoingConnection(server.address)).to(Sink.fromSubscriber(tcpReadProbe.subscriberProbe)).run()
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

      Source.fromPublisher(tcpWriteProbe.publisherProbe).via(Tcp().outgoingConnection(server.address)).to(Sink.fromSubscriber(tcpReadProbe.subscriberProbe)).run()
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
      val outgoingConnection = Tcp().outgoingConnection(server.address)

      val conn1F =
        Source.fromPublisher(tcpWriteProbe1.publisherProbe)
          .viaMat(outgoingConnection)(Keep.right)
          .to(Sink.fromSubscriber(tcpReadProbe1.subscriberProbe)).run()
      val serverConnection1 = server.waitAccept()
      val conn2F =
        Source.fromPublisher(tcpWriteProbe2.publisherProbe)
          .viaMat(outgoingConnection)(Keep.right)
          .to(Sink.fromSubscriber(tcpReadProbe2.subscriberProbe))
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

    "properly full-close if requested" in assertAllStagesStopped {
      val serverAddress = temporaryServerAddress()
      val writeButIgnoreRead: Flow[ByteString, ByteString, NotUsed] =
        Flow.fromSinkAndSourceMat(Sink.ignore, Source.single(ByteString("Early response")))(Keep.right)

      val binding =
        Await.result(
          Tcp().bind(serverAddress.getHostName, serverAddress.getPort, halfClose = false).toMat(Sink.foreach { conn ⇒
            conn.flow.join(writeButIgnoreRead).run()
          })(Keep.left).run(), 3.seconds)

      val (promise, result) = Source.maybe[ByteString]
        .via(Tcp().outgoingConnection(serverAddress.getHostName, serverAddress.getPort))
        .toMat(Sink.fold(ByteString.empty)(_ ++ _))(Keep.both)
        .run()

      Await.result(result, 3.seconds) should ===(ByteString("Early response"))

      promise.success(None) // close client upstream, no more data
      binding.unbind()
    }

    "Echo should work even if server is in full close mode" in {
      val serverAddress = temporaryServerAddress()

      val binding =
        Await.result(
          Tcp().bind(serverAddress.getHostName, serverAddress.getPort, halfClose = false).toMat(Sink.foreach { conn ⇒
            conn.flow.join(Flow[ByteString]).run()
          })(Keep.left).run(), 3.seconds)

      val result = Source(immutable.Iterable.fill(1000)(ByteString(0)))
        .via(Tcp().outgoingConnection(serverAddress, halfClose = true))
        .runFold(0)(_ + _.size)

      Await.result(result, 3.seconds) should ===(1000)

      binding.unbind()
    }

    "handle when connection actor terminates unexpectedly" in {
      val system2 = ActorSystem()
      import system2.dispatcher
      val mat2 = ActorMaterializer.create(system2)

      val serverAddress = temporaryServerAddress()
      val binding = Tcp(system2).bindAndHandle(Flow[ByteString], serverAddress.getHostName, serverAddress.getPort)(mat2)

      val result = Source.maybe[ByteString].via(Tcp(system2).outgoingConnection(serverAddress)).runFold(0)(_ + _.size)(mat2)

      // Getting rid of existing connection actors by using a blunt instrument
      system2.actorSelection(akka.io.Tcp(system2).getManager.path / "selectors" / s"$$a" / "*") ! Kill

      a[StreamTcpException] should be thrownBy
        Await.result(result, 3.seconds)

      binding.map(_.unbind()).recover { case NonFatal(_) ⇒ () } foreach (_ ⇒ system2.shutdown())
    }

  }

  "TCP listen stream" must {

    // Reusing handler
    val echoHandler = Sink.foreach[Tcp.IncomingConnection] { _.flow.join(Flow[ByteString]).run() }

    "be able to implement echo" in {
      val serverAddress = temporaryServerAddress()
      val (bindingFuture, echoServerFinish) =
        Tcp()
          .bind(serverAddress.getHostName, serverAddress.getPort) // TODO getHostString in Java7
          .toMat(echoHandler)(Keep.both)
          .run()

      // make sure that the server has bound to the socket
      val binding = Await.result(bindingFuture, 100.millis)

      val testInput = (0 to 255).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))
      val resultFuture =
        Source(testInput).via(Tcp().outgoingConnection(serverAddress)).runFold(ByteString.empty)((acc, in) ⇒ acc ++ in)

      Await.result(resultFuture, 3.seconds) should be(expectedOutput)
      Await.result(binding.unbind(), 3.seconds)
      Await.result(echoServerFinish, 1.second)
    }

    "work with a chain of echoes" in {
      val serverAddress = temporaryServerAddress()
      val (bindingFuture, echoServerFinish) =
        Tcp()
          .bind(serverAddress.getHostName, serverAddress.getPort) // TODO getHostString in Java7
          .toMat(echoHandler)(Keep.both)
          .run()

      // make sure that the server has bound to the socket
      val binding = Await.result(bindingFuture, 100.millis)

      val echoConnection = Tcp().outgoingConnection(serverAddress)

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

    "bind and unbind correctly" in EventFilter[BindException](occurrences = 2).intercept {
      if (Helpers.isWindows) {
        info("On Windows unbinding is not immediate")
        pending
      }
      val address = temporaryServerAddress()
      val probe1 = TestSubscriber.manualProbe[Tcp.IncomingConnection]()
      val bind = Tcp(system).bind(address.getHostName, address.getPort) // TODO getHostString in Java7
      // Bind succeeded, we have a local address
      val binding1 = Await.result(bind.to(Sink.fromSubscriber(probe1)).run(), 3.second)

      probe1.expectSubscription()

      val probe2 = TestSubscriber.manualProbe[Tcp.IncomingConnection]()
      val binding2F = bind.to(Sink.fromSubscriber(probe2)).run()
      probe2.expectSubscriptionAndError(BindFailedException)

      val probe3 = TestSubscriber.manualProbe[Tcp.IncomingConnection]()
      val binding3F = bind.to(Sink.fromSubscriber(probe3)).run()
      probe3.expectSubscriptionAndError()

      a[BindFailedException] shouldBe thrownBy { Await.result(binding2F, 1.second) }
      a[BindFailedException] shouldBe thrownBy { Await.result(binding3F, 1.second) }

      // Now unbind first
      Await.result(binding1.unbind(), 1.second)
      probe1.expectComplete()

      val probe4 = TestSubscriber.manualProbe[Tcp.IncomingConnection]()
      // Bind succeeded, we have a local address
      val binding4 = Await.result(bind.to(Sink.fromSubscriber(probe4)).run(), 3.second)
      probe4.expectSubscription()

      // clean up
      Await.result(binding4.unbind(), 1.second)
    }

    "not shut down connections after the connection stream cancelled" in assertAllStagesStopped {
      val address = temporaryServerAddress()
      Tcp().bind(address.getHostName, address.getPort).take(1).runForeach { tcp ⇒
        Thread.sleep(1000) // we're testing here to see if it survives such race
        tcp.flow.join(Flow[ByteString]).run()
      }

      val total = Source(immutable.Iterable.fill(1000)(ByteString(0)))
        .via(Tcp().outgoingConnection(address))
        .runFold(0)(_ + _.size)

      Await.result(total, 3.seconds) should ===(1000)
    }

    "shut down properly even if some accepted connection Flows have not been subscribed to" in assertAllStagesStopped {
      val address = temporaryServerAddress()
      val firstClientConnected = Promise[Unit]()
      val takeTwoAndDropSecond = Flow[IncomingConnection].map(conn ⇒ {
        firstClientConnected.trySuccess(())
        conn
      }).grouped(2).take(1).map(_.head)
      Tcp().bind(address.getHostName, address.getPort)
        .via(takeTwoAndDropSecond)
        .runForeach(_.flow.join(Flow[ByteString]).run())

      val folder = Source(immutable.Iterable.fill(100)(ByteString(0)))
        .via(Tcp().outgoingConnection(address))
        .fold(0)(_ + _.size).toMat(Sink.head)(Keep.right)

      val total = folder.run()

      awaitAssert(firstClientConnected.future, 2.seconds)
      val rejected = folder.run()

      Await.result(total, 10.seconds) should ===(100)

      a[StreamTcpException] should be thrownBy {
        Await.result(rejected, 5.seconds) should ===(100)
      }
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
