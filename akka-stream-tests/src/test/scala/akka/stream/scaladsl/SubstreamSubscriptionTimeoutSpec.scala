/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.{ ExtendedActorSystem, ActorIdentity, ActorRef, Identify }
import akka.stream.{ ActorFlowMaterializer, ActorFlowMaterializerSettings }
import akka.stream.impl.SubscriptionTimeoutException
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._

class SubstreamSubscriptionTimeoutSpec(conf: String) extends AkkaSpec(conf) {

  def this(subscriptionTimeout: FiniteDuration) {
    this(
      s"""
          |akka.stream.materializer {
          |  subscription-timeout {
          |    mode = cancel
          |
          |    timeout = ${subscriptionTimeout.toMillis}ms
          |  }
          |}""".stripMargin)
  }

  def this() {
    this(300.millis)
  }

  val settings = ActorFlowMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)

  implicit val dispatcher = system.dispatcher
  implicit val materializer = ActorFlowMaterializer(settings)

  "groupBy" must {

    "timeout and cancel substream publishers when no-one subscribes to them after some time (time them out)" in assertAllStagesStopped {
      val publisherProbe = TestPublisher.manualProbe[Int]()
      val publisher = Source(publisherProbe).groupBy(_ % 3).runWith(Sink.publisher)
      val subscriber = TestSubscriber.manualProbe[(Int, Source[Int, _])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)
      upstreamSubscription.sendNext(2)
      upstreamSubscription.sendNext(3)

      val (_, s1) = subscriber.expectNext()
      // should not break normal usage
      val s1SubscriberProbe = TestSubscriber.manualProbe[Int]()
      s1.runWith(Sink.publisher).subscribe(s1SubscriberProbe)
      val s1Subscription = s1SubscriberProbe.expectSubscription()
      s1Subscription.request(100)
      s1SubscriberProbe.expectNext(1)

      val (_, s2) = subscriber.expectNext()
      // should not break normal usage
      val s2SubscriberProbe = TestSubscriber.manualProbe[Int]()
      s2.runWith(Sink.publisher).subscribe(s2SubscriberProbe)
      val s2Subscription = s2SubscriberProbe.expectSubscription()
      s2Subscription.request(100)
      s2SubscriberProbe.expectNext(2)

      val (_, s3) = subscriber.expectNext()

      // sleep long enough for it to be cleaned up
      Thread.sleep(1000)

      val f = s3.runWith(Sink.head).recover { case _: SubscriptionTimeoutException ⇒ "expected" }
      Await.result(f, 300.millis) should equal("expected")

      upstreamSubscription.sendComplete()
    }

    "timeout and stop groupBy parent actor if none of the substreams are actually consumed" in assertAllStagesStopped {
      val publisherProbe = TestPublisher.manualProbe[Int]()
      val publisher = Source(publisherProbe).groupBy(_ % 2).runWith(Sink.publisher)
      val subscriber = TestSubscriber.manualProbe[(Int, Source[Int, _])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)
      upstreamSubscription.sendNext(2)
      upstreamSubscription.sendNext(3)
      upstreamSubscription.sendComplete()

      val (_, s1) = subscriber.expectNext()
      val (_, s2) = subscriber.expectNext()

      val groupByActor = watchGroupByActor(5) // update this number based on how many streams the test above has...

      // it should be terminated after none of it's substreams are used within the timeout
      expectTerminated(groupByActor, 1000.millis)
    }

    "not timeout and cancel substream publishers when they have been subscribed to" in {
      val publisherProbe = TestPublisher.manualProbe[Int]()
      val publisher = Source(publisherProbe).groupBy(_ % 2).runWith(Sink.publisher)
      val subscriber = TestSubscriber.manualProbe[(Int, Source[Int, _])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)
      upstreamSubscription.sendNext(2)

      val (_, s1) = subscriber.expectNext()
      // should not break normal usage
      val s1SubscriberProbe = TestSubscriber.manualProbe[Int]()
      s1.runWith(Sink.publisher).subscribe(s1SubscriberProbe)
      val s1Sub = s1SubscriberProbe.expectSubscription()
      s1Sub.request(1)
      s1SubscriberProbe.expectNext(1)

      val (_, s2) = subscriber.expectNext()
      // should not break normal usage
      val s2SubscriberProbe = TestSubscriber.manualProbe[Int]()
      s2.runWith(Sink.publisher).subscribe(s2SubscriberProbe)
      val s2Sub = s2SubscriberProbe.expectSubscription()

      // sleep long enough for tiemout to trigger if not cancelled
      Thread.sleep(1000)

      s2Sub.request(100)
      s2SubscriberProbe.expectNext(2)
      s1Sub.request(100)
      upstreamSubscription.sendNext(3)
      upstreamSubscription.sendNext(4)
      s1SubscriberProbe.expectNext(3)
      s2SubscriberProbe.expectNext(4)
    }
  }

  private def watchGroupByActor(flowNr: Int): ActorRef = {
    implicit val t = Timeout(300.millis)
    import akka.pattern.ask
    val path = s"/user/$$a/flow-${flowNr}-1-publisherSource-groupBy"
    val gropByPath = system.actorSelection(path)
    val groupByActor = try {
      Await.result((gropByPath ? Identify("")).mapTo[ActorIdentity], 300.millis).ref.get
    } catch {
      case ex: Exception ⇒
        alert(s"Unable to find groupBy actor by path: [$path], please adjust it's flowId, here's the current actor tree:\n" +
          system.asInstanceOf[ExtendedActorSystem].printTree)
        throw ex
    }
    watch(groupByActor)
  }

}
