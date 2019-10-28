package docs.stream.operators.sourceorflow

object Take {
  def takeExample(): Unit = {
    import akka.actor.ActorSystem
    import akka.stream.scaladsl.Source

    implicit val system: ActorSystem = ActorSystem()

    // #take
    Source(1 to 5).take(3).runForeach(println)
    // 1
    // 2
    // 3
    // #take
  }
}
