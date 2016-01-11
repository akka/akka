/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.actor

import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.stream.scaladsl._
import akka.stream.ActorFlowMaterializer
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import akka.testkit.EventFilter
import akka.testkit.ImplicitSender
import akka.testkit.TestEvent.Mute
import akka.testkit.TestProbe

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object ActorPublisherSpec {

  def testPublisherProps(probe: ActorRef): Props =
    Props(new TestPublisher(probe)).withDispatcher("akka.test.stream-dispatcher")

  case class TotalDemand(elements: Long)
  case class Produce(elem: String)
  case class Err(reason: String)
  case object Boom
  case object Complete

  class TestPublisher(probe: ActorRef) extends ActorPublisher[String] {
    import akka.stream.actor.ActorPublisherMessage._

    def receive = {
      case Request(element) ⇒ probe ! TotalDemand(totalDemand)
      case Produce(elem)    ⇒ onNext(elem)
      case Err(reason)      ⇒ onError(new RuntimeException(reason) with NoStackTrace)
      case Complete         ⇒ onComplete()
      case Boom             ⇒ throw new RuntimeException("boom") with NoStackTrace
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

class ActorPublisherSpec extends AkkaSpec with ImplicitSender {

  import akka.stream.actor.ActorPublisherSpec._

  system.eventStream.publish(Mute(EventFilter[IllegalStateException]()))

  "An ActorPublisher" must {

    "accumulate demand" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val p = ActorPublisher[String](ref)
      val s = StreamTestKit.SubscriberProbe[String]()
      p.subscribe(s)
      val sub = s.expectSubscription
      sub.request(2)
      probe.expectMsg(TotalDemand(2))
      sub.request(3)
      probe.expectMsg(TotalDemand(5))
      sub.cancel()
    }

    "allow onNext up to requested elements, but not more" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val p = ActorPublisher[String](ref)
      val s = StreamTestKit.SubscriberProbe[String]()
      p.subscribe(s)
      val sub = s.expectSubscription
      sub.request(2)
      ref ! Produce("elem-1")
      ref ! Produce("elem-2")
      ref ! Produce("elem-3")
      s.expectNext("elem-1")
      s.expectNext("elem-2")
      s.expectNoMsg(300.millis)
      sub.cancel()
    }

    "signal error" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val s = StreamTestKit.SubscriberProbe[String]()
      ActorPublisher[String](ref).subscribe(s)
      ref ! Err("wrong")
      s.expectSubscription
      s.expectError.getMessage should be("wrong")
    }

    "signal error before subscribe" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      ref ! Err("early err")
      val s = StreamTestKit.SubscriberProbe[String]()
      ActorPublisher[String](ref).subscribe(s)
      s.expectSubscriptionAndError.getMessage should be("early err")
    }

    "drop onNext elements after cancel" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val p = ActorPublisher[String](ref)
      val s = StreamTestKit.SubscriberProbe[String]()
      p.subscribe(s)
      val sub = s.expectSubscription
      sub.request(2)
      ref ! Produce("elem-1")
      sub.cancel()
      ref ! Produce("elem-2")
      s.expectNext("elem-1")
      s.expectNoMsg(300.millis)
    }

    "remember requested after restart" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val p = ActorPublisher[String](ref)
      val s = StreamTestKit.SubscriberProbe[String]()
      p.subscribe(s)
      val sub = s.expectSubscription
      sub.request(3)
      probe.expectMsg(TotalDemand(3))
      ref ! Produce("elem-1")
      ref ! Boom
      ref ! Produce("elem-2")
      s.expectNext("elem-1")
      s.expectNext("elem-2")
      sub.request(5)
      probe.expectMsg(TotalDemand(6))
      sub.cancel()
    }

    "signal onComplete" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val s = StreamTestKit.SubscriberProbe[String]()
      ActorPublisher[String](ref).subscribe(s)
      val sub = s.expectSubscription
      sub.request(3)
      ref ! Produce("elem-1")
      ref ! Complete
      s.expectNext("elem-1")
      s.expectComplete
    }

    "signal immediate onComplete" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      ref ! Complete
      val s = StreamTestKit.SubscriberProbe[String]()
      ActorPublisher[String](ref).subscribe(s)
      s.expectSubscriptionAndComplete
    }

    "only allow one subscriber" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val s = StreamTestKit.SubscriberProbe[String]()
      ActorPublisher[String](ref).subscribe(s)
      s.expectSubscription
      val s2 = StreamTestKit.SubscriberProbe[String]()
      ActorPublisher[String](ref).subscribe(s2)
      s2.expectSubscriptionAndError.getClass should be(classOf[IllegalStateException])
    }

    "signal onCompete when actor is stopped" in {
      val probe = TestProbe()
      val ref = system.actorOf(testPublisherProps(probe.ref))
      val s = StreamTestKit.SubscriberProbe[String]()
      ActorPublisher[String](ref).subscribe(s)
      s.expectSubscription
      ref ! PoisonPill
      s.expectComplete
    }

    "work together with Flow and ActorSubscriber" in {
      implicit val materializer = ActorFlowMaterializer()
      val probe = TestProbe()

      val source = Source[Int](senderProps)
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

    "work in a FlowGraph" in {
      implicit val materializer = ActorFlowMaterializer()
      val probe1 = TestProbe()
      val probe2 = TestProbe()

      val senderRef1 = system.actorOf(senderProps)
      val source1 = Source(ActorPublisher[Int](senderRef1))

      val sink1 = Sink(ActorSubscriber[String](system.actorOf(receiverProps(probe1.ref))))
      val sink2: Sink[String, ActorRef] = Sink.actorSubscriber(receiverProps(probe2.ref))

      val senderRef2 = FlowGraph.closed(Source[Int](senderProps)) { implicit b ⇒
        source2 ⇒
          import FlowGraph.Implicits._

          val merge = b.add(Merge[Int](2))
          val bcast = b.add(Broadcast[String](2))

          source1 ~> merge.in(0)
          source2.outlet ~> merge.in(1)

          merge.out.map(_.toString) ~> bcast.in

          bcast.out(0).map(_ + "mark") ~> sink1
          bcast.out(1) ~> sink2
      }.run()

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
      implicit val materializer = ActorFlowMaterializer()
      val timeout = 150.millis
      val a = system.actorOf(timeoutingProps(testActor, timeout))
      val pub = ActorPublisher(a)

      // don't subscribe for `timeout` millis, so it will shut itself down
      expectMsg("timed-out")

      // now subscribers will already be rejected, while the actor could perform some clean-up
      val sub = StreamTestKit.SubscriberProbe()
      pub.subscribe(sub)
      sub.expectSubscriptionAndError()

      expectMsg("cleaned-up")
      // termination is tiggered by user code
      watch(a)
      expectTerminated(a)
    }

    "be able to define a subscription-timeout, which is cancelled by the first incoming Subscriber" in {
      implicit val materializer = ActorFlowMaterializer()
      val timeout = 500.millis
      val sub = StreamTestKit.SubscriberProbe[Int]()

      within(2 * timeout) {
        val pub = ActorPublisher(system.actorOf(timeoutingProps(testActor, timeout)))

        // subscribe right away, should cancel subscription-timeout
        pub.subscribe(sub)
        sub.expectSubscription()

        expectNoMsg()
      }
    }

  }

}
