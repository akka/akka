package akka.docs.actor

//#imports
import akka.actor.{ TypedActor, Props }
import akka.dispatch.{ Promise, Future, Await }
import akka.util.duration._
//#imports

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.testkit._

//#typed-actor-iface
trait Squarer {
  def squareDontCare(i: Int): Unit //fire-forget

  def square(i: Int): Future[Int] //non-blocking send-request-reply

  def squareNowPlease(i: Int): Option[Int] //blocking send-request-reply

  def squareNow(i: Int): Int //blocking send-request-reply
}
//#typed-actor-iface

//#typed-actor-impl
class SquarerImpl extends Squarer {
  import TypedActor.dispatcher //So we can create Promises

  def squareDontCare(i: Int): Unit = i * i //Nobody cares :(

  def square(i: Int): Future[Int] = Promise successful i * i

  def squareNowPlease(i: Int): Option[Int] = Some(i * i)

  def squareNow(i: Int): Int = i * i
}
//#typed-actor-impl

class TypedActorDocSpec extends AkkaSpec(Map("akka.loglevel" -> "INFO")) {

  "create a typed actor" in {
    //#typed-actor-create

    val mySquarer = TypedActor(system).typedActorOf[Squarer, SquarerImpl]()
    //#typed-actor-create

    //#typed-actor-calls
    mySquarer.squareDontCare(10)

    val fSquare = mySquarer.square(10) //A Future[Int]

    val oSquare = mySquarer.squareNowPlease(10) //Option[Int]

    val iSquare = mySquarer.squareNow(10) //Int
    //#typed-actor-calls

    Await.result(fSquare, 3 seconds) must be === 100

    oSquare must be === Some(100)

    iSquare must be === 100

    TypedActor(system).stop(mySquarer)
  }
}
