/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.tck

import akka.actor.Props
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.Request
import akka.stream.tck.ActorPublisherTest.TestPublisher
import org.reactivestreams.Publisher

object ActorPublisherTest {

  case object Produce
  case object Loop
  case object Complete

  class TestPublisher(allElements: Long) extends ActorPublisher[Int] {

    val source: Iterator[Int] = (if (allElements == Long.MaxValue) 1 to Int.MaxValue else 0 until allElements.toInt).toIterator

    override def receive: Receive = {
      case Request(elements) ⇒
        loopDemand()

      case Produce if totalDemand > 0 && !isCompleted && source.hasNext ⇒ onNext(source.next())
      case Produce if !isCompleted && !source.hasNext ⇒ onComplete()
      case Produce if isCompleted ⇒ // no-op
      case _ ⇒ // no-op
    }

    def loopDemand() {
      val loopUntil = math.min(100, totalDemand)
      1 to loopUntil.toInt foreach { _ ⇒ self ! Produce }
      if (loopUntil > 100) self ! Loop
    }
  }

}

class ActorPublisherTest extends AkkaPublisherVerification[Int] {

  override def createPublisher(elements: Long): Publisher[Int] = {
    val ref = system.actorOf(Props(classOf[TestPublisher], elements).withDispatcher("akka.test.stream-dispatcher"))

    ActorPublisher(ref)
  }
}
