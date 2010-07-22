package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

import Actor._
import org.multiverse.api.latches.StandardLatch
import scalaz._
import concurrent.{Strategy, Promise}
import java.lang.String
import Scalaz._
import se.scalablesolutions.akka.config.ScalaConfig.{LifeCycle, Permanent}
import se.scalablesolutions.akka.dispatch.{Future, FutureTimeoutException}
import java.util.concurrent.{Executors, TimeUnit}

class ScalazSpec extends JUnitSuite {
  val actorRef = actorOf(new Actor {
    protected def receive = {
      case "one" => self.reply(1)
      case "error" => TimeUnit.SECONDS.sleep(10)
    }
  }).start

  @Test def callActorAndGetValidation = {

    implicit val executorService = Executors.newFixedThreadPool(2)
    import Strategy.Executor

    val promiseError: Promise[ValidationNEL[String, Option[Int]]] = promise(callActor("error"))
    val promiseOne: Promise[ValidationNEL[String, Option[Int]]] = promise(callActor("error"))

    //[X]Promise[X]  <-- Applicative / Monad
    //[X]Option[X]  <-- Applicative / Monad
    //[X]Validation[String, X]  <-- Applicative / Monad  iff Semigroup[String]
    //[X]Promise[Option[X]] <-- Applicative

    val pvi: Promise[ValidationNEL[String, Option[Int]]] = (promiseError |@| promiseOne) {
      (v1, v2) =>
        (v1 |@| v2) {
          (o1, o2) =>
            (o1 |@| o2) { _ + _}
        }
    }

    println(pvi.get)

    val listOptionInt: List[Option[Int]] = List.fill(5)(1.some)
    //    val listOptionInt = (listOptionInt.comp) map (_ * 2)

    println(listOptionInt.sequence[Option, Int])

  }

  def callActor(msg: String): ValidationNEL[String, Option[Int]] = {
    try {
      val future: Future[Int] = (actorRef !!! msg)
      future.await
      future.exception match {
        case Some((_, t)) => t.getMessage.failNel
        case None => future.result.successNel
      }
    } catch {
      case e: FutureTimeoutException => e.getMessage.failNel
    }
  }
}