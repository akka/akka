/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed.supervision

import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration._

object SupervisionCompileOnlyTest {

  val behavior = Behaviors.empty[String]

  //#restart
  Behaviors.supervise(behavior)
    .onFailure[IllegalStateException](SupervisorStrategy.restart)
  //#restart

  //#resume
  Behaviors.supervise(behavior)
    .onFailure[IllegalStateException](SupervisorStrategy.resume)
  //#resume

  //#restart-limit
  Behaviors.supervise(behavior)
    .onFailure[IllegalStateException](SupervisorStrategy.restartWithLimit(
      maxNrOfRetries = 10, withinTimeRange = 10.seconds
    ))
  //#restart-limit

  //#multiple
  Behaviors.supervise(Behaviors.supervise(behavior)
    .onFailure[IllegalStateException](SupervisorStrategy.restart))
    .onFailure[IllegalArgumentException](SupervisorStrategy.stop)
  //#multiple
}
