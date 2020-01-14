/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators

import akka.actor.ActorSystem
import akka.testkit.TestProbe

object SourceOperators {

  implicit val system: ActorSystem = ???

  def fromFuture = {
    //#sourceFromFuture

    import akka.actor.ActorSystem
    import akka.stream.scaladsl._
    import akka.{ Done, NotUsed }

    import scala.concurrent.Future

    val source: Source[Int, NotUsed] = Source.future(Future.successful(10))
    val sink: Sink[Int, Future[Done]] = Sink.foreach((i: Int) => println(i))

    val done: Future[Done] = source.runWith(sink) //10
    //#sourceFromFuture
  }

  def actorRef(): Unit = {
    //#actorRef

    import akka.actor.Status.Success
    import akka.actor.ActorRef
    import akka.stream.OverflowStrategy
    import akka.stream.CompletionStrategy
    import akka.stream.scaladsl._

    val bufferSize = 100

    val source: Source[Any, ActorRef] = Source.actorRef[Any](bufferSize, OverflowStrategy.dropHead)
    val actorRef: ActorRef = source.to(Sink.foreach(println)).run()

    actorRef ! "hello"
    actorRef ! "hello"

    // The stream completes successfully with the following message
    actorRef ! Success(CompletionStrategy.immediately)
    //#actorRef
  }

  def actorRefWithBackpressure(): Unit = {
    //#actorRefWithBackpressure

    import akka.actor.Status.Success
    import akka.actor.ActorRef
    import akka.stream.CompletionStrategy
    import akka.stream.scaladsl._

    val probe = TestProbe()

    val source: Source[Any, ActorRef] = Source.actorRefWithBackpressure[Any]("ack", {
      case _: Success => CompletionStrategy.immediately
    }, PartialFunction.empty)
    val actorRef: ActorRef = source.to(Sink.foreach(println)).run()

    probe.send(actorRef, "hello")
    probe.expectMsg("ack")
    probe.send(actorRef, "hello")
    probe.expectMsg("ack")

    // The stream completes successfully with the following message
    actorRef ! Success(())
    //#actorRefWithBackpressure
  }

  def maybe(): Unit = {
    //#maybe
    import akka.stream.scaladsl._
    import scala.concurrent.Promise

    val source = Source.maybe[Int].to(Sink.foreach(elem => println(elem)))

    val promise1: Promise[Option[Int]] = source.run()
    promise1.success(Some(1)) // prints 1

    // a new Promise is returned when the stream is materialized
    val promise2 = source.run()
    promise2.success(Some(2)) // prints 2
    //#maybe
  }
}
