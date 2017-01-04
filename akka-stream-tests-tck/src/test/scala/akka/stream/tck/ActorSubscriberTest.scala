/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.tck

import akka.actor.Props
import akka.stream.actor.ActorSubscriber
import akka.stream.actor.OneByOneRequestStrategy
import akka.stream.actor.RequestStrategy
import org.reactivestreams.Subscriber

object ActorSubscriberOneByOneRequestTest {
  class StrategySubscriber(val requestStrategy: RequestStrategy) extends ActorSubscriber {

    override def receive: Receive = { case _ ⇒ }
  }
}

class ActorSubscriberOneByOneRequestTest extends AkkaSubscriberBlackboxVerification[Int] {
  import ActorSubscriberOneByOneRequestTest._

  override def createSubscriber(): Subscriber[Int] = {
    val props = Props(classOf[StrategySubscriber], OneByOneRequestStrategy)
    ActorSubscriber(system.actorOf(props.withDispatcher("akka.test.stream-dispatcher")))
  }

  override def createElement(element: Int): Int = element
}
