/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream

//#imports
import akka.stream._
import akka.stream.scaladsl._
//#imports
import akka.{ NotUsed, Done }
import akka.actor.ActorSystem
import akka.util.ByteString

import org.scalatest._
import org.scalatest.concurrent._
import scala.concurrent._
import scala.concurrent.duration._
import java.io.File

class QuickStartDocSpec extends WordSpec with BeforeAndAfterAll with ScalaFutures {
  implicit val patience = PatienceConfig(5.seconds)

  //#create-materializer
  implicit val system = ActorSystem("QuickStart")
  implicit val materializer = ActorMaterializer()
  //#create-materializer

  override def afterAll(): Unit = {
    system.terminate()
  }

  def println(any: Any) = () // silence printing stuff

  "demonstrate Source" in {
    //#create-source
    val source: Source[Int, NotUsed] = Source(1 to 100)
    //#create-source

    //#run-source
    source.runForeach(i => println(i))(materializer)
    //#run-source

    //#transform-source
    val factorials = source.scan(BigInt(1))((acc, next) => acc * next)

    val result: Future[IOResult] =
      factorials
        .map(num => ByteString(s"$num\n"))
        .runWith(FileIO.toFile(new File("factorials.txt")))
    //#transform-source

    //#use-transformed-sink
    factorials.map(_.toString).runWith(lineSink("factorial2.txt"))
    //#use-transformed-sink

    //#add-streams
    val done: Future[Done] =
      factorials
        .zipWith(Source(0 to 100))((num, idx) => s"$idx! = $num")
        .throttle(1, 1.second, 1, ThrottleMode.shaping)
        //#add-streams
        .take(3)
        //#add-streams
        .runForeach(println)
    //#add-streams

    done.futureValue
  }

  //#transform-sink
  def lineSink(filename: String): Sink[String, Future[IOResult]] =
    Flow[String]
      .map(s => ByteString(s + "\n"))
      .toMat(FileIO.toFile(new File(filename)))(Keep.right)
  //#transform-sink

}
