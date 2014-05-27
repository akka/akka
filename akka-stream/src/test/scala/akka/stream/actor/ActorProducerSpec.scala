/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.actor

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.actor.ActorConsumer.WatermarkRequestStrategy
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import akka.testkit.EventFilter
import akka.testkit.ImplicitSender
import akka.testkit.TestEvent.Mute
import akka.testkit.TestProbe

object ActorProducerSpec {

  def testProducerProps(probe: ActorRef): Props =
    Props(new TestProducer(probe)).withDispatcher("akka.test.stream-dispatcher")

  case class TotalDemand(elements: Long)
  case class Produce(elem: String)
  case class Err(reason: String)
  case object Boom
  case object Complete

  class TestProducer(probe: ActorRef) extends ActorProducer[String] {
    import ActorProducer._

    def receive = {
      case Request(element) ⇒ probe ! TotalDemand(totalDemand)
      case Produce(elem)    ⇒ onNext(elem)
      case Err(reason)      ⇒ onError(new RuntimeException(reason) with NoStackTrace)
      case Complete         ⇒ onComplete()
      case Boom             ⇒ throw new RuntimeException("boom") with NoStackTrace
    }
  }

  def senderProps: Props = Props[Sender].withDispatcher("akka.test.stream-dispatcher")

  class Sender extends ActorProducer[Int] {
    import ActorProducer.Cancel
    import ActorProducer.Request

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

    def deliverBuf(): Unit =
      if (totalDemand > 0) {
        val (use, keep) = buf.splitAt(totalDemand)
        buf = keep
        use foreach onNext
      }
  }

  def receiverProps(probe: ActorRef): Props =
    Props(new Receiver(probe)).withDispatcher("akka.test.stream-dispatcher")

  class Receiver(probe: ActorRef) extends ActorConsumer {
    import ActorConsumer._

    override val requestStrategy = WatermarkRequestStrategy(10)

    def receive = {
      case OnNext(s: String) ⇒
        probe ! s
    }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorProducerSpec extends AkkaSpec with ImplicitSender {
  import ActorProducerSpec._
  import ActorProducer._

  system.eventStream.publish(Mute(EventFilter[IllegalStateException]()))

  "An ActorProducer" must {

    "accumulate demand" in {
      val probe = TestProbe()
      val ref = system.actorOf(testProducerProps(probe.ref))
      val p = ActorProducer[String](ref)
      val c = StreamTestKit.consumerProbe[String]
      p.produceTo(c)
      val sub = c.expectSubscription
      sub.requestMore(2)
      probe.expectMsg(TotalDemand(2))
      sub.requestMore(3)
      probe.expectMsg(TotalDemand(5))
      sub.cancel()
    }

    "allow onNext up to requested elements, but not more" in {
      val probe = TestProbe()
      val ref = system.actorOf(testProducerProps(probe.ref))
      val p = ActorProducer[String](ref)
      val c = StreamTestKit.consumerProbe[String]
      p.produceTo(c)
      val sub = c.expectSubscription
      sub.requestMore(2)
      ref ! Produce("elem-1")
      ref ! Produce("elem-2")
      ref ! Produce("elem-3")
      c.expectNext("elem-1")
      c.expectNext("elem-2")
      c.expectNoMsg(300.millis)
      sub.cancel()
    }

    "signal error" in {
      val probe = TestProbe()
      val ref = system.actorOf(testProducerProps(probe.ref))
      val c = StreamTestKit.consumerProbe[String]
      ActorProducer[String](ref).produceTo(c)
      ref ! Err("wrong")
      c.expectSubscription
      c.expectError.getMessage should be("wrong")
    }

    "signal error before subscribe" in {
      val probe = TestProbe()
      val ref = system.actorOf(testProducerProps(probe.ref))
      ref ! Err("early err")
      val c = StreamTestKit.consumerProbe[String]
      ActorProducer[String](ref).produceTo(c)
      c.expectError.getMessage should be("early err")
    }

    "drop onNext elements after cancel" in {
      val probe = TestProbe()
      val ref = system.actorOf(testProducerProps(probe.ref))
      val p = ActorProducer[String](ref)
      val c = StreamTestKit.consumerProbe[String]
      p.produceTo(c)
      val sub = c.expectSubscription
      sub.requestMore(2)
      ref ! Produce("elem-1")
      sub.cancel()
      ref ! Produce("elem-2")
      c.expectNext("elem-1")
      c.expectNoMsg(300.millis)
      sub.cancel()
    }

    "remember requested after restart" in {
      val probe = TestProbe()
      val ref = system.actorOf(testProducerProps(probe.ref))
      val p = ActorProducer[String](ref)
      val c = StreamTestKit.consumerProbe[String]
      p.produceTo(c)
      val sub = c.expectSubscription
      sub.requestMore(3)
      probe.expectMsg(TotalDemand(3))
      ref ! Produce("elem-1")
      ref ! Boom
      ref ! Produce("elem-2")
      c.expectNext("elem-1")
      c.expectNext("elem-2")
      sub.requestMore(5)
      probe.expectMsg(TotalDemand(6))
      sub.cancel()
    }

    "signal onComplete" in {
      val probe = TestProbe()
      val ref = system.actorOf(testProducerProps(probe.ref))
      val c = StreamTestKit.consumerProbe[String]
      ActorProducer[String](ref).produceTo(c)
      val sub = c.expectSubscription
      sub.requestMore(3)
      ref ! Produce("elem-1")
      ref ! Complete
      c.expectNext("elem-1")
      c.expectComplete
    }

    "signal immediate onComplete" in {
      val probe = TestProbe()
      val ref = system.actorOf(testProducerProps(probe.ref))
      ref ! Complete
      val c = StreamTestKit.consumerProbe[String]
      ActorProducer[String](ref).produceTo(c)
      c.expectComplete
    }

    "only allow one subscriber" in {
      val probe = TestProbe()
      val ref = system.actorOf(testProducerProps(probe.ref))
      val c = StreamTestKit.consumerProbe[String]
      ActorProducer[String](ref).produceTo(c)
      val sub = c.expectSubscription
      val c2 = StreamTestKit.consumerProbe[String]
      ActorProducer[String](ref).produceTo(c2)
      c2.expectError.getClass should be(classOf[IllegalStateException])
    }

    "signal onCompete when actor is stopped" in {
      val probe = TestProbe()
      val ref = system.actorOf(testProducerProps(probe.ref))
      val c = StreamTestKit.consumerProbe[String]
      ActorProducer[String](ref).produceTo(c)
      val sub = c.expectSubscription
      ref ! PoisonPill
      c.expectComplete
    }

    "work together with Flow and ActorConsumer" in {
      val materializer = FlowMaterializer(MaterializerSettings(dispatcher = "akka.test.stream-dispatcher"))
      val probe = TestProbe()
      val snd = system.actorOf(senderProps)
      val rcv = system.actorOf(receiverProps(probe.ref))
      Flow(ActorProducer[Int](snd)).collect {
        case n if n % 2 == 0 ⇒ "elem-" + n
      }.produceTo(materializer, ActorConsumer(rcv))

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

}
