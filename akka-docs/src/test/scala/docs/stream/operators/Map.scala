package docs.stream.operators

//#imports
import akka.NotUsed
import akka.stream.scaladsl._

//#imports

object Map {

  //#map
  def mapExample: Source[String, NotUsed] = {
    val source = Source(1 to 10)
    source.map(elem => elem.toString)
  }
  //#map
}
