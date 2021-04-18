/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.actor.{ Actor, ActorSystem, Props }
import akka.pattern.ask
import akka.stream.Attributes
import akka.testkit.TestKit
import akka.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpecLike

import scala.concurrent.duration._

class SourceFromPublisherSpec
    extends TestKit(ActorSystem("source-from-publisher-spec"))
    with AsyncWordSpecLike
    with Matchers {

  private val MaxElements = 1
  private val InitialElements = 1

  import system.dispatcher
  implicit private val timeout: Timeout = Timeout(5.second)
  case object GetSourceMessagesCounts
  case object IncrementSourceMessages
  class CounterActor extends Actor {
    private var sourceMessages = 0
    override def receive: Receive = {
      case IncrementSourceMessages => sourceMessages += 1
      case GetSourceMessagesCounts => sender() ! sourceMessages
    }
  }
  private val counterActor =
    system.actorOf(Props(new CounterActor).withDispatcher("akka.test.stream-dispatcher"), s"counter-actor")

  private val publisher =
    Source
      .fromIterator(() => Iterator.from(1))
      .addAttributes(Attributes.inputBuffer(1, 1))
      .takeWhile(_ <= 1000)
      .map { i =>
        counterActor ! IncrementSourceMessages
        i
      }
      .runWith(Sink.asPublisher(fanout = false))

  private val sourcePublisher =
    Source.fromPublisher(publisher).addAttributes(Attributes.inputBuffer(InitialElements, MaxElements))
  private val takeOneFlow = Flow[Int].take(1)

  "Source.fromPublisher" should {
    // https://github.com/akka/akka/issues/30076
    "consider 'inputBuffer' attributes in a correct way (#issue 30076)" in {
      val streamResult = sourcePublisher.via(takeOneFlow).runWith(Sink.ignore)
      val sourceExhibitedElements = streamResult.flatMap(_ => (counterActor ? GetSourceMessagesCounts).mapTo[Int])

      // batchRemaining will be 0 when MaxElements are 2 or 1, so it requests 1 more element. That is why I am adding 1 to MaxElements
      // In short when MaxElements are 1, 2 we get 1 more extra element otherwise we get MaxElements
      sourceExhibitedElements.map(_ should be <= (MaxElements + 1))
    }
  }
}
