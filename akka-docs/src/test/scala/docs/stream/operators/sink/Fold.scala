package docs.stream.operators.sink
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Sink, Source}

import scala.concurrent.Future

object Fold {
  implicit val system: ActorSystem = ActorSystem("example")
def foldExample: Future[Int] ={

  //#fold
  Source(1 to 100)
    .runWith(Sink.fold(0)(_+_))
  //Future(Success(5050))
  //#fold
}
}
