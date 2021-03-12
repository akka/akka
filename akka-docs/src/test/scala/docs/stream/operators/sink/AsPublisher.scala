/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sink

import scala.concurrent.{ExecutionContextExecutor, Future}
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}

object AsPublisher {
  implicit val system: ActorSystem = ???
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  def asPublisherExample() = {
    //#asPublisher
    val source = Source(1 to 5)
    val publisherTrue = source.runWith(Sink.asPublisher(true))

    val resultFromFirstSubscriberTrue: Future[Int] = Source.fromPublisher(publisherTrue).runWith(Sink.fold(0)((acc, element) => acc + element))
    val resultFromSecondSubscriberTrue: Future[Int] = Source.fromPublisher(publisherTrue).runWith(Sink.fold(1)((acc, element) => acc * element))

    resultFromFirstSubscriberTrue.map(println) //15
    resultFromSecondSubscriberTrue.map(println) //120

    val publisherFalse = source.runWith(Sink.asPublisher(false))
    val resultFromFirstSubscriberFalse: Future[Int] = Source.fromPublisher(publisherFalse).runWith(Sink.fold(0)((acc, element) => acc + element))
    val resultFromSecondSubscriberFalse: Future[Int] = Source.fromPublisher(publisherFalse).runWith(Sink.fold(1)((acc, element) => acc * element))

    resultFromFirstSubscriberFalse.map(println) //15
    resultFromSecondSubscriberFalse.map(println) //No output, because the source was not able to subscribe to the publisher.
    //#asPublisher
  }
}
