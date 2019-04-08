package docs.stream.operators.sourceorflow
import akka.stream.scaladsl.Source

object Scan {
  def scanExample(): Unit = {
    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer

    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    //#scan
    val source = Source(1 to 5)
    source.scan(0)((acc, x) => acc + x).runForeach(println)
    // 0  (= 0)
    // 1  (= 0 + 1)
    // 3  (= 0 + 1 + 2)
    // 6  (= 0 + 1 + 2 + 3)
    // 10 (= 0 + 1 + 2 + 3 + 4)
    // 15 (= 0 + 1 + 2 + 3 + 4 + 5)
    //#scan
  }

}
