/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.actor.Status.Failure
import akka.actor.{ Actor, ActorIdentity, ActorLogging, ActorRef, ActorSystem, ActorSystemImpl, Identify, Props }
import akka.pattern._
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.scaladsl._
import akka.stream._
import akka.testkit.{ AkkaSpec, ImplicitSender, SocketUtil, TestKit, TestProbe }
import akka.util.ByteString
import com.typesafe.config._

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.control.NoStackTrace

object StreamRefsSpec {

  object DataSourceActor {
    def props(probe: ActorRef): Props =
      Props(new DataSourceActor(probe))
        .withDispatcher("akka.test.stream-dispatcher")
  }

  class DataSourceActor(probe: ActorRef) extends Actor with ActorLogging {
    import context.dispatcher
    implicit val mat = ActorMaterializer()

    def receive = {
      case "give" ⇒
        /*
         * Here we're able to send a source to a remote recipient
         *
         * For them it's a Source; for us it is a Sink we run data "into"
         */
        val source: Source[String, NotUsed] = Source(List("hello", "world"))
        val ref: Future[SourceRef[String]] = source.runWith(StreamRefs.sourceRef())

        ref pipeTo sender()

      case "give-infinite" ⇒
        val source: Source[String, NotUsed] = Source.fromIterator(() ⇒ Iterator.from(1)).map("ping-" + _)
        val (r: NotUsed, ref: Future[SourceRef[String]]) = source.toMat(StreamRefs.sourceRef())(Keep.both).run()

        ref pipeTo sender()

      case "give-fail" ⇒
        val ref = Source.failed[String](new Exception("Booooom!") with NoStackTrace)
          .runWith(StreamRefs.sourceRef())

        ref pipeTo sender()

      case "give-complete-asap" ⇒
        val ref = Source.empty
          .runWith(StreamRefs.sourceRef())

        ref pipeTo sender()

      case "give-subscribe-timeout" ⇒
        val ref = Source.repeat("is anyone there?")
          .toMat(StreamRefs.sourceRef())(Keep.right) // attributes like this so they apply to the Sink.sourceRef
          .withAttributes(StreamRefAttributes.subscriptionTimeout(500.millis))
          .run()

        ref pipeTo sender()
      //      case "send-bulk" ⇒
      //        /*
      //         * Here we're able to send a source to a remote recipient
      //         * The source is a "bulk transfer one, in which we're ready to send a lot of data"
      //         *
      //         * For them it's a Source; for us it is a Sink we run data "into"
      //         */
      //        val source: Source[ByteString, NotUsed] = Source.single(ByteString("huge-file-"))
      //        val ref: SourceRef[ByteString] = source.runWith(SourceRef.bulkTransfer())
      //        sender() ! BulkSourceMsg(ref)

      case "receive" ⇒
        /*
         * We write out code, knowing that the other side will stream the data into it.
         *
         * For them it's a Sink; for us it's a Source.
         */
        val sink =
          StreamRefs.sinkRef[String]()
            .to(Sink.actorRef(probe, "<COMPLETE>"))
            .run()

        sink pipeTo sender()

      case "receive-subscribe-timeout" ⇒
        val sink = StreamRefs.sinkRef[String]()
          .withAttributes(StreamRefAttributes.subscriptionTimeout(500.millis))
          .to(Sink.actorRef(probe, "<COMPLETE>"))
          .run()

        sink pipeTo sender()

      case "receive-32" ⇒
        val (sink, driver) = StreamRefs.sinkRef[String]()
          .toMat(TestSink.probe(context.system))(Keep.both)
          .run()

        import context.dispatcher
        Future {
          driver.ensureSubscription()
          driver.request(2)
          driver.expectNext()
          driver.expectNext()
          driver.expectNoMessage(100.millis)
          driver.request(30)
          driver.expectNextN(30)

          "<COMPLETED>"
        } pipeTo probe

        sink pipeTo sender()

      //      case "receive-bulk" ⇒
      //        /*
      //         * We write out code, knowing that the other side will stream the data into it.
      //         * This will open a dedicated connection per transfer.
      //         *
      //         * For them it's a Sink; for us it's a Source.
      //         */
      //        val sink: SinkRef[ByteString] =
      //          SinkRef.bulkTransferSource()
      //            .to(Sink.actorRef(probe, "<COMPLETE>"))
      //            .run()
      //
      //
      //        sender() ! BulkSinkMsg(sink)
    }

  }

  // -------------------------

  final case class SourceMsg(dataSource: SourceRef[String])
  final case class BulkSourceMsg(dataSource: SourceRef[ByteString])
  final case class SinkMsg(dataSink: SinkRef[String])
  final case class BulkSinkMsg(dataSink: SinkRef[ByteString])

  def config(): Config = {
    val address = SocketUtil.temporaryServerAddress()
    ConfigFactory.parseString(
      s"""
    akka {
      loglevel = INFO

      actor {
        provider = remote
        serialize-messages = off
      }

      remote.netty.tcp {
        port = ${address.getPort}
        hostname = "${address.getHostName}"
      }
    }
  """).withFallback(ConfigFactory.load())
  }
}

class StreamRefsSpec(config: Config) extends AkkaSpec(config) with ImplicitSender {
  import StreamRefsSpec._

  def this() {
    this(StreamRefsSpec.config())
  }

  val remoteSystem = ActorSystem("RemoteSystem", StreamRefsSpec.config())
  implicit val mat = ActorMaterializer()

  override protected def beforeTermination(): Unit =
    TestKit.shutdownActorSystem(remoteSystem)

  val p = TestProbe()

  // obtain the remoteActor ref via selection in order to use _real_ remoting in this test
  val remoteActor = {
    val it = remoteSystem.actorOf(DataSourceActor.props(p.ref), "remoteActor")
    val remoteAddress = remoteSystem.asInstanceOf[ActorSystemImpl].provider.getDefaultAddress
    system.actorSelection(it.path.toStringWithAddress(remoteAddress)) ! Identify("hi")
    expectMsgType[ActorIdentity].ref.get
  }

  "A SourceRef" must {

    "send messages via remoting" in {
      remoteActor ! "give"
      val sourceRef = expectMsgType[SourceRef[String]]

      sourceRef
        .runWith(Sink.actorRef(p.ref, "<COMPLETE>"))

      p.expectMsg("hello")
      p.expectMsg("world")
      p.expectMsg("<COMPLETE>")
    }

    "fail when remote source failed" in {
      remoteActor ! "give-fail"
      val sourceRef = expectMsgType[SourceRef[String]]

      sourceRef
        .runWith(Sink.actorRef(p.ref, "<COMPLETE>"))

      val f = p.expectMsgType[Failure]
      f.cause.getMessage should include("Remote stream (")
      // actor name here, for easier identification
      f.cause.getMessage should include("failed, reason: Booooom!")
    }

    "complete properly when remote source is empty" in {
      // this is a special case since it makes sure that the remote stage is still there when we connect to it

      remoteActor ! "give-complete-asap"
      val sourceRef = expectMsgType[SourceRef[String]]

      sourceRef
        .runWith(Sink.actorRef(p.ref, "<COMPLETE>"))

      p.expectMsg("<COMPLETE>")
    }

    "respect back-pressure from (implied by target Sink)" in {
      remoteActor ! "give-infinite"
      val sourceRef = expectMsgType[SourceRef[String]]

      val probe = sourceRef
        .runWith(TestSink.probe)

      probe.ensureSubscription()
      probe.expectNoMessage(100.millis)

      probe.request(1)
      probe.expectNext("ping-1")
      probe.expectNoMessage(100.millis)

      probe.request(20)
      probe.expectNextN((1 to 20).map(i ⇒ "ping-" + (i + 1)))
      probe.cancel()

      // since no demand anyway
      probe.expectNoMessage(100.millis)

      // should not cause more pulling, since we issued a cancel already
      probe.request(10)
      probe.expectNoMessage(100.millis)
    }

    "receive timeout if subscribing too late to the source ref" in {
      remoteActor ! "give-subscribe-timeout"
      val remoteSource: SourceRef[String] = expectMsgType[SourceRef[String]]

      // not materializing it, awaiting the timeout...
      Thread.sleep(800) // the timeout is 500ms

      val probe = remoteSource
        .runWith(TestSink.probe[String](system))

      //      val failure = p.expectMsgType[Failure]
      //      failure.cause.getMessage should include("[SourceRef-0] Remote side did not subscribe (materialize) handed out Sink reference")

      // the local "remote sink" should cancel, since it should notice the origin target actor is dead
      probe.ensureSubscription()
      val ex = probe.expectError()
      ex.getMessage should include("has terminated! Tearing down this side of the stream as well.")
    }

    // bug #24626
    "not receive subscription timeout when got subscribed" in {
      remoteActor ! "give-subscribe-timeout"
      val remoteSource: SourceRef[String] = expectMsgType[SourceRef[String]]
      // materialize directly and start consuming, timeout is 500ms
      remoteSource.throttle(1, 100.millis, 1, ThrottleMode.Shaping)
        .take(10) // 10 * 100 millis - way more than timeout for good measure
        .runWith(Sink.seq)
        .futureValue // this would fail if it timed out
    }
  }

  "A SinkRef" must {

    "receive elements via remoting" in {

      remoteActor ! "receive"
      val remoteSink: SinkRef[String] = expectMsgType[SinkRef[String]]

      Source("hello" :: "world" :: Nil)
        .to(remoteSink)
        .run()

      p.expectMsg("hello")
      p.expectMsg("world")
      p.expectMsg("<COMPLETE>")
    }

    "fail origin if remote Sink gets a failure" in {

      remoteActor ! "receive"
      val remoteSink: SinkRef[String] = expectMsgType[SinkRef[String]]

      val remoteFailureMessage = "Booom!"
      Source.failed(new Exception(remoteFailureMessage))
        .to(remoteSink)
        .run()

      val f = p.expectMsgType[akka.actor.Status.Failure]
      f.cause.getMessage should include(s"Remote stream (")
      // actor name ere, for easier identification
      f.cause.getMessage should include(s"failed, reason: $remoteFailureMessage")
    }

    "receive hundreds of elements via remoting" in {
      remoteActor ! "receive"
      val remoteSink: SinkRef[String] = expectMsgType[SinkRef[String]]

      val msgs = (1 to 100).toList.map(i ⇒ s"payload-$i")

      Source(msgs)
        .runWith(remoteSink)

      msgs.foreach(t ⇒ p.expectMsg(t))
      p.expectMsg("<COMPLETE>")
    }

    "receive timeout if subscribing too late to the sink ref" in {
      remoteActor ! "receive-subscribe-timeout"
      val remoteSink: SinkRef[String] = expectMsgType[SinkRef[String]]

      // not materializing it, awaiting the timeout...
      Thread.sleep(800) // the timeout is 500ms

      val probe = TestSource.probe[String](system)
        .to(remoteSink)
        .run()

      val failure = p.expectMsgType[Failure]
      failure.cause.getMessage should include("Remote side did not subscribe (materialize) handed out Sink reference")

      // the local "remote sink" should cancel, since it should notice the origin target actor is dead
      probe.expectCancellation()
    }

    // bug #24626
    "now receive timeout if subscribing is already done to the sink ref" in {
      remoteActor ! "receive-subscribe-timeout"
      val remoteSink: SinkRef[String] = expectMsgType[SinkRef[String]]
      Source.repeat("whatever")
        .throttle(1, 100.millis, 1, ThrottleMode.Shaping)
        .take(10) // the timeout is 500ms, so this makes sure we run more time than that
        .runWith(remoteSink)

      (0 to 9).foreach { _ ⇒
        p.expectMsg("whatever")
      }
      p.expectMsg("<COMPLETE>")
    }

    "respect back -pressure from (implied by origin Sink)" in {
      remoteActor ! "receive-32"
      val sinkRef = expectMsgType[SinkRef[String]]

      Source.repeat("hello") runWith sinkRef

      // if we get this message, it means no checks in the request/expect semantics were broken, good!
      p.expectMsg("<COMPLETED>")
    }

    "not allow materializing multiple times" in {
      remoteActor ! "receive"
      val sinkRef = expectMsgType[SinkRef[String]]

      val p1: TestPublisher.Probe[String] = TestSource.probe[String].to(sinkRef).run()
      val p2: TestPublisher.Probe[String] = TestSource.probe[String].to(sinkRef).run()

      p1.ensureSubscription()
      val req = p1.expectRequest()

      // will be cancelled immediately, since it's 2nd:
      p2.ensureSubscription()
      p2.expectCancellation()
    }

  }

}
