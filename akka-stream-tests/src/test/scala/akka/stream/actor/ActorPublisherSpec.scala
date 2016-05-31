/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.actor

import akka.actor.{ ActorRef, PoisonPill, Props }
import akka.stream.{ ClosedShape, ActorMaterializer, ActorMaterializerSettings, ActorAttributes }
import akka.stream.scaladsl._
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.testkit.TestEvent.Mute
import akka.testkit.{ AkkaSpec, EventFilter, ImplicitSender, TestProbe }
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.actor.Stash

object ActorPublisherSpec {

  val config =
    s"""
      my-dispatcher1 = $${akka.test.stream-dispatcher}
      my-dispatcher2 = $${akka.test.stream-dispatcher}
    """

  def testPublisherProps(probe: ActorRef, useTestDispatcher: Boolean = true): Props = {
    val p = Props(new TestPublisher(probe))
    if (useTestDispatcher) p.withDispatcher("akka.test.stream-dispatcher")
    else p
  }

  def testPublisherWithStashProps(probe: ActorRef, useTestDispatcher: Boolean = true): Props = {
    val p = Props(new TestPublisherWithStash(probe))
    if (useTestDispatcher) p.withDispatcher("akka.test.stream-dispatcher")
    else p
  }

  case class TotalDemand(elements: Long)
  case class Produce(elem: String)
  case class Err(reason: String)
  case class ErrThenStop(reason: String)
  case object Boom
  case object Complete
  case object CompleteThenStop
  case object ThreadName

  class TestPublisher(probe: ActorRef) extends ActorPublisher[String] {
    import akka.stream.actor.ActorPublisherMessage._

    def receive = {
      case Request(element)    ⇒ probe ! TotalDemand(totalDemand)
      case Produce(elem)       ⇒ onNext(elem)
      case Err(reason)         ⇒ onError(new RuntimeException(reason) with NoStackTrace)
      case ErrThenStop(reason) ⇒ onErrorThenStop(new RuntimeException(reason) with NoStackTrace)
      case Complete            ⇒ onComplete()
      case CompleteThenStop    ⇒ onCompleteThenStop()
      case Boom                ⇒ throw new RuntimeException("boom") with NoStackTrace
      case ThreadName          ⇒ probe ! Thread.currentThread.getName
    }
  }

  class TestPublisherWithStash(probe: ActorRef) extends TestPublisher(probe) with Stash {

    override def receive = stashing

    def stashing: Receive = {
      case "unstash" ⇒
        unstashAll()
        context.become(super.receive)
      case _ ⇒ stash()
    }

  }

  def senderProps: Props = Props[Sender].withDispatcher("akka.test.stream-dispatcher")

  class Sender extends ActorPublisher[Int] {
    import akka.stream.actor.ActorPublisherMessage._

    var buf = Vector.empty[Int]

    def receive = {
      case i: Int ⇒
        if (buf.isEmpty && totalDemand > 0)
          onNext(i)
        else {
          buf :+= i
          deliverBuf()
        }
      case Request(_) ⇒
        deliverBuf()
      case Cancel ⇒
        context.stop(self)
    }

    @tailrec
    final def deliverBuf(): Unit =
      if (totalDemand > 0) {
        if (totalDemand <= Int.MaxValue) {
          val (use, keep) = buf.splitAt(totalDemand.toInt)
          buf = keep
          use foreach onNext
        } else {
          val (use, keep) = buf.splitAt(Int.MaxValue)
          buf = keep
          use foreach onNext
          deliverBuf()
        }
      }
  }

  def timeoutingProps(probe: ActorRef, timeout: FiniteDuration): Props =
    Props(classOf[TimeoutingPublisher], probe, timeout).withDispatcher("akka.test.stream-dispatcher")

  class TimeoutingPublisher(probe: ActorRef, timeout: FiniteDuration) extends ActorPublisher[Int] {
    import akka.stream.actor.ActorPublisherMessage._
    import context.dispatcher

    override def subscriptionTimeout = timeout

    override def receive: Receive = {
      case Request(_) ⇒
        onNext(1)
      case SubscriptionTimeoutExceeded ⇒
        probe ! "timed-out"
        context.system.scheduler.scheduleOnce(timeout, probe, "cleaned-up")
        context.system.scheduler.scheduleOnce(timeout, self, PoisonPill)
    }
  }

  def receiverProps(probe: ActorRef): Props =
    Props(new Receiver(probe)).withDispatcher("akka.test.stream-dispatcher")

  class Receiver(probe: ActorRef) extends ActorSubscriber {
    import akka.stream.actor.ActorSubscriberMessage._

    override val requestStrategy = WatermarkRequestStrategy(10)

    def receive = {
      case OnNext(s: String) ⇒
        probe ! s
    }
  }

}

class ActorPublisherSpec extends AkkaSpec(ActorPublisherSpec.config) with ImplicitSender {

  import akka.stream.actor.ActorPublisherSpec._

  system.eventStream.publish(Mute(EventFilter[IllegalStateException]()))

  "An ActorPublisher" must {

    "accumulate demand" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val p = ActorPublisher[String](ref)
      val s = TestSubscriber.probe[String]()
      p.subscribe(s)
      s.request(2)
      probe.expectMsg(TotalDemand(2))
      s.request(3)
      probe.expectMsg(TotalDemand(5))
      s.cancel()
    }

    "allow onNext up to requested elements, but not more" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val p = ActorPublisher[String](ref)
      val s = TestSubscriber.probe[String]()
      p.subscribe(s)
      s.request(2)
      ref ! Produce("elem-1")
      ref ! Produce("elem-2")
      ref ! Produce("elem-3")
      s.expectNext("elem-1")
      s.expectNext("elem-2")
      s.expectNoMsg(300.millis)
      s.cancel()
    }

    "signal error" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val s = TestSubscriber.manualProbe[String]()
      ActorPublisher[String](ref).subscribe(s)
      ref ! Err("wrong")
      s.expectSubscription()
      s.expectError().getMessage should be("wrong")
    }

    "not terminate after signalling onError" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val s = TestSubscriber.manualProbe[String]()
      ActorPublisher[String](ref).subscribe(s)
      s.expectSubscription()
      probe.watch(ref)
      ref ! Err("wrong")
      s.expectError().getMessage should be("wrong")
      probe.expectNoMsg(200.millis)
    }

    "terminate after signalling onErrorThenStop" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val s = TestSubscriber.manualProbe[String]()
      ActorPublisher[String](ref).subscribe(s)
      s.expectSubscription()
      probe.watch(ref)
      ref ! ErrThenStop("wrong")
      s.expectError().getMessage should be("wrong")
      probe.expectTerminated(ref, 3.seconds)
    }

    "signal error before subscribe" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      ref ! Err("early err")
      val s = TestSubscriber.manualProbe[String]()
      ActorPublisher[String](ref).subscribe(s)
      s.expectSubscriptionAndError().getMessage should be("early err")
    }

    "drop onNext elements after cancel" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val p = ActorPublisher[String](ref)
      val s = TestSubscriber.probe[String]()
      p.subscribe(s)
      s.request(2)
      ref ! Produce("elem-1")
      s.cancel()
      ref ! Produce("elem-2")
      s.expectNext("elem-1")
      s.expectNoMsg(300.millis)
    }

    "remember requested after restart" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val p = ActorPublisher[String](ref)
      val s = TestSubscriber.probe[String]()
      p.subscribe(s)
      s.request(3)
      probe.expectMsg(TotalDemand(3))
      ref ! Produce("elem-1")
      ref ! Boom
      ref ! Produce("elem-2")
      s.expectNext("elem-1")
      s.expectNext("elem-2")
      s.request(5)
      probe.expectMsg(TotalDemand(6))
      s.cancel()
    }

    "signal onComplete" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val s = TestSubscriber.probe[String]()
      ActorPublisher[String](ref).subscribe(s)
      s.request(3)
      ref ! Produce("elem-1")
      ref ! Complete
      s.expectNext("elem-1")
      s.expectComplete()
    }

    "not terminate after signalling onComplete" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val s = TestSubscriber.manualProbe[String]()
      ActorPublisher[String](ref).subscribe(s)
      val sub = s.expectSubscription()
      sub.request(3)
      probe.expectMsg(TotalDemand(3))
      probe.watch(ref)
      ref ! Produce("elem-1")
      ref ! Complete
      s.expectNext("elem-1")
      s.expectComplete()
      probe.expectNoMsg(200.millis)
    }

    "terminate after signalling onCompleteThenStop" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val s = TestSubscriber.manualProbe[String]()
      ActorPublisher[String](ref).subscribe(s)
      val sub = s.expectSubscription()
      sub.request(3)
      probe.expectMsg(TotalDemand(3))
      probe.watch(ref)
      ref ! Produce("elem-1")
      ref ! CompleteThenStop
      s.expectNext("elem-1")
      s.expectComplete()
      probe.expectTerminated(ref, 3.seconds)
    }

    "signal immediate onComplete" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      ref ! Complete
      val s = TestSubscriber.manualProbe[String]()
      ActorPublisher[String](ref).subscribe(s)
      s.expectSubscriptionAndComplete()
    }

    "only allow one subscriber" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val s = TestSubscriber.manualProbe[String]()
      ActorPublisher[String](ref).subscribe(s)
      s.expectSubscription()
      val s2 = TestSubscriber.manualProbe[String]()
      ActorPublisher[String](ref).subscribe(s2)
      s2.expectSubscriptionAndError().getClass should be(classOf[IllegalStateException])
    }

    "signal onCompete when actor is stopped" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val s = TestSubscriber.manualProbe[String]()
      ActorPublisher[String](ref).subscribe(s)
      s.expectSubscription()
      ref ! PoisonPill
      s.expectComplete()
    }

    "work together with Flow and ActorSubscriber" in {
      implicit val materializer = ActorMaterializer()
      assertAllStagesStopped {
        val probe = TestProbe()

        val source: Source[Int, ActorRef] = Source.actorPublisher(senderProps)
        val sink: Sink[String, ActorRef] = Sink.actorSubscriber(receiverProps(probe.ref))

        val (snd, rcv) = source.collect {
          case n if n % 2 == 0 ⇒ "elem-" + n
        }.toMat(sink)(Keep.both).run()

        (1 to 3) foreach { snd ! _ }
        probe.expectMsg("elem-2")

        (4 to 500) foreach { n ⇒
          if (n % 19 == 0) Thread.sleep(50) // simulate bursts
          snd ! n
        }

        (4 to 500 by 2) foreach { n ⇒ probe.expectMsg("elem-" + n) }

        watch(snd)
        rcv ! PoisonPill
        expectTerminated(snd)
      }
    }

    "work in a GraphDSL" in {
      implicit val materializer = ActorMaterializer()
      val probe1 = TestProbe()
      val probe2 = TestProbe()

      val senderRef1 = system.actorOf(senderProps)
      val source1 = Source.fromPublisher(ActorPublisher[Int](senderRef1))

      val sink1 = Sink.fromSubscriber(ActorSubscriber[String](system.actorOf(receiverProps(probe1.ref))))
      val sink2: Sink[String, ActorRef] = Sink.actorSubscriber(receiverProps(probe2.ref))

      val senderRef2 = RunnableGraph.fromGraph(GraphDSL.create(Source.actorPublisher[Int](senderProps)) { implicit b ⇒ source2 ⇒
        import GraphDSL.Implicits._

        val merge = b.add(Merge[Int](2))
        val bcast = b.add(Broadcast[String](2))

        source1 ~> merge.in(0)
        source2.out ~> merge.in(1)

        merge.out.map(_.toString) ~> bcast.in

        bcast.out(0).map(_ + "mark") ~> sink1
        bcast.out(1) ~> sink2
        ClosedShape
      }).run()

      (0 to 10).foreach {
        senderRef1 ! _
        senderRef2 ! _
      }

      (0 to 10).foreach { msg ⇒
        probe1.expectMsg(msg.toString + "mark")
        probe2.expectMsg(msg.toString)
      }
    }

    "be able to define a subscription-timeout, after which it should shut down" in {
      implicit val materializer = ActorMaterializer()
      Utils.assertAllStagesStopped {
        val timeout = 150.millis
        val a = system.actorOf(timeoutingProps(testActor, timeout))
        val pub = ActorPublisher(a)

        // don't subscribe for `timeout` millis, so it will shut itself down
        expectMsg("timed-out")

        // now subscribers will already be rejected, while the actor could perform some clean-up
        val sub = TestSubscriber.manualProbe()
        pub.subscribe(sub)
        sub.expectSubscriptionAndError()

        expectMsg("cleaned-up")
        // termination is tiggered by user code
        watch(a)
        expectTerminated(a)
      }
    }

    "be able to define a subscription-timeout, which is cancelled by the first incoming Subscriber" in {
      implicit val materializer = ActorMaterializer()
      val timeout = 500.millis
      val sub = TestSubscriber.manualProbe[Int]()

      within(2 * timeout) {
        val pub = ActorPublisher(system.actorOf(timeoutingProps(testActor, timeout)))

        // subscribe right away, should cancel subscription-timeout
        pub.subscribe(sub)
        sub.expectSubscription()

        expectNoMsg()
      }
    }

    "use dispatcher from materializer settings" in {
      implicit val materializer = ActorMaterializer(
        ActorMaterializerSettings(system).withDispatcher("my-dispatcher1"))
      val s = TestSubscriber.manualProbe[String]()
      val ref = Source.actorPublisher(testPublisherProps(testActor, useTestDispatcher = false)).to(Sink.fromSubscriber(s)).run()
      ref ! ThreadName
      expectMsgType[String] should include("my-dispatcher1")
    }

    "use dispatcher from operation attributes" in {
      implicit val materializer = ActorMaterializer()
      val s = TestSubscriber.manualProbe[String]()
      val ref = Source.actorPublisher(testPublisherProps(testActor, useTestDispatcher = false))
        .withAttributes(ActorAttributes.dispatcher("my-dispatcher1"))
        .to(Sink.fromSubscriber(s)).run()
      ref ! ThreadName
      expectMsgType[String] should include("my-dispatcher1")
    }

    "use dispatcher from props" in {
      implicit val materializer = ActorMaterializer()
      val s = TestSubscriber.manualProbe[String]()
      val ref = Source.actorPublisher(testPublisherProps(testActor, useTestDispatcher = false).withDispatcher("my-dispatcher1"))
        .withAttributes(ActorAttributes.dispatcher("my-dispatcher2"))
        .to(Sink.fromSubscriber(s)).run()
      ref ! ThreadName
      expectMsgType[String] should include("my-dispatcher1")
    }

    "handle stash" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherWithStashProps(probe.ref))
      val p = ActorPublisher[String](ref)
      val s = TestSubscriber.probe[String]()
      p.subscribe(s)
      s.request(2)
      s.request(3)
      ref ! "unstash"
      probe.expectMsg(TotalDemand(5))
      probe.expectMsg(TotalDemand(5))
      s.request(4)
      probe.expectMsg(TotalDemand(9))
      s.cancel()
    }

  }

}
