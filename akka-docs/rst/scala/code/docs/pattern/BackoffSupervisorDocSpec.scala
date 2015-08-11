/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.pattern

import akka.actor.{ ActorSystem, Props }
import akka.pattern.BackoffSupervisor
import akka.testkit.TestActors.EchoActor

class BackoffSupervisorDocSpec {

  class BackoffSupervisorDocSpecExample {
    val system: ActorSystem = ???
    import scala.concurrent.duration._

    //#backoff
    val childProps = Props(classOf[EchoActor])

    val supervisor = BackoffSupervisor.props(
      childProps,
      childName = "myEcho",
      minBackoff = 3.seconds,
      maxBackoff = 30.seconds,
      randomFactor = 0.2) // adds 20% "noise" to vary the intervals slightly

    system.actorOf(supervisor, name = "echoSupervisor")
    //#backoff
  }

}
