/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators

object SourceOperators {

  def fromFuture = {
    //#sourceFromFuture

    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer
    import akka.{ Done, NotUsed }
    import akka.stream.scaladsl._

    import scala.concurrent.Future

    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val source: Source[Int, NotUsed] = Source.fromFuture(Future.successful(10))
    val sink: Sink[Int, Future[Done]] = Sink.foreach((i: Int) ⇒ println(i))

    val done: Future[Done] = source.runWith(sink) //10
    //#sourceFromFuture
  }

  def actorRef = {
    //#sourceActorRefBufferSize
    import akka.stream.OverflowStrategy
    import akka.stream.scaladsl._
    import akka.actor.{ ActorRef, ActorSystem }
    import akka.stream.ActorMaterializer
    import akka.pattern.ask
    import akka.stream.{ BufferStatus, GetBufferStatus }
    import scala.concurrent.Future
    import concurrent.duration._
    import akka.util.Timeout

    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher
    implicit val timeout = Timeout(5.seconds)

    val ref: ActorRef = Source.actorRef(1000, OverflowStrategy.fail).to(Sink.ignore).run
    val ans: Future[BufferStatus] = (ref ? GetBufferStatus).mapTo[BufferStatus]
    ans.foreach(bs ⇒ println(s"Buffer status: ${bs.used}/${bs.capacity}"))
    //#sourceActorRefBufferSize
  }
}
