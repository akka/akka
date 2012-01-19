/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.docs.transactor

import akka.actor._
import akka.transactor._
import akka.util.duration._
import akka.util.Timeout
import akka.testkit._
import scala.concurrent.stm._

object CoordinatedExample {
  //#coordinated-example
  import akka.actor._
  import akka.transactor._
  import scala.concurrent.stm._

  case class Increment(friend: Option[ActorRef] = None)
  case object GetCount

  class Counter extends Actor {
    val count = Ref(0)

    def receive = {
      case coordinated @ Coordinated(Increment(friend)) ⇒ {
        friend foreach (_ ! coordinated(Increment()))
        coordinated atomic { implicit t ⇒
          count transform (_ + 1)
        }
      }
      case GetCount ⇒ sender ! count.single.get
    }
  }
  //#coordinated-example
}

object CoordinatedApi {
  case object Message

  class Coordinator extends Actor {
    //#receive-coordinated
    def receive = {
      case coordinated @ Coordinated(Message) ⇒ {
        //#coordinated-atomic
        coordinated atomic { implicit t ⇒
          // do something in the coordinated transaction ...
        }
        //#coordinated-atomic
      }
    }
    //#receive-coordinated
  }
}

object CounterExample {
  //#counter-example
  import akka.transactor._
  import scala.concurrent.stm._

  case object Increment

  class Counter extends Transactor {
    val count = Ref(0)

    def atomically = implicit txn ⇒ {
      case Increment ⇒ count transform (_ + 1)
    }
  }
  //#counter-example
}

object FriendlyCounterExample {
  //#friendly-counter-example
  import akka.actor._
  import akka.transactor._
  import scala.concurrent.stm._

  case object Increment

  class FriendlyCounter(friend: ActorRef) extends Transactor {
    val count = Ref(0)

    override def coordinate = {
      case Increment ⇒ include(friend)
    }

    def atomically = implicit txn ⇒ {
      case Increment ⇒ count transform (_ + 1)
    }
  }
  //#friendly-counter-example

  class Friend extends Transactor {
    val count = Ref(0)

    def atomically = implicit txn ⇒ {
      case Increment ⇒ count transform (_ + 1)
    }
  }
}

// Only checked for compilation
object TransactorCoordinate {
  case object Message
  case object SomeMessage
  case object SomeOtherMessage
  case object OtherMessage
  case object Message1
  case object Message2

  class TestCoordinateInclude(actor1: ActorRef, actor2: ActorRef, actor3: ActorRef) extends Transactor {
    //#coordinate-include
    override def coordinate = {
      case Message ⇒ include(actor1, actor2, actor3)
    }
    //#coordinate-include

    def atomically = txn ⇒ doNothing
  }

  class TestCoordinateSendTo(someActor: ActorRef, actor1: ActorRef, actor2: ActorRef) extends Transactor {
    //#coordinate-sendto
    override def coordinate = {
      case SomeMessage  ⇒ sendTo(someActor -> SomeOtherMessage)
      case OtherMessage ⇒ sendTo(actor1 -> Message1, actor2 -> Message2)
    }
    //#coordinate-sendto

    def atomically = txn ⇒ doNothing
  }
}

class TransactorDocSpec extends AkkaSpec {

  "coordinated example" in {
    import CoordinatedExample._

    //#run-coordinated-example
    import akka.dispatch.Await
    import akka.util.duration._
    import akka.util.Timeout

    val system = ActorSystem("app")

    val counter1 = system.actorOf(Props[Counter], name = "counter1")
    val counter2 = system.actorOf(Props[Counter], name = "counter2")

    implicit val timeout = Timeout(5 seconds)

    counter1 ! Coordinated(Increment(Some(counter2)))

    val count = Await.result(counter1 ? GetCount, timeout.duration)

    // count == 1
    //#run-coordinated-example

    count must be === 1

    system.shutdown()
  }

  "coordinated api" in {
    import CoordinatedApi._

    //#implicit-timeout
    import akka.util.duration._
    import akka.util.Timeout

    implicit val timeout = Timeout(5 seconds)
    //#implicit-timeout

    //#create-coordinated
    val coordinated = Coordinated()
    //#create-coordinated

    val system = ActorSystem("coordinated")
    val actor = system.actorOf(Props[Coordinator], name = "coordinator")

    //#send-coordinated
    actor ! Coordinated(Message)
    //#send-coordinated

    //#include-coordinated
    actor ! coordinated(Message)
    //#include-coordinated

    coordinated.await()

    system.shutdown()
  }

  "counter transactor" in {
    import CounterExample._

    val system = ActorSystem("transactors")

    lazy val underlyingCounter = new Counter
    val counter = system.actorOf(Props(underlyingCounter), name = "counter")
    val coordinated = Coordinated()(Timeout(5 seconds))
    counter ! coordinated(Increment)
    coordinated.await()

    underlyingCounter.count.single.get must be === 1

    system.shutdown()
  }

  "friendly counter transactor" in {
    import FriendlyCounterExample._

    val system = ActorSystem("transactors")

    lazy val underlyingFriend = new Friend
    val friend = system.actorOf(Props(underlyingFriend), name = "friend")

    lazy val underlyingFriendlyCounter = new FriendlyCounter(friend)
    val friendlyCounter = system.actorOf(Props(underlyingFriendlyCounter), name = "friendly")

    val coordinated = Coordinated()(Timeout(5 seconds))
    friendlyCounter ! coordinated(Increment)
    coordinated.await()

    underlyingFriendlyCounter.count.single.get must be === 1
    underlyingFriend.count.single.get must be === 1

    system.shutdown()
  }
}
