/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sourceorflow

import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.WatchedActorTerminatedException
import akka.stream.scaladsl.Flow

object Watch {

  def someActor(): ActorRef = ???

  def watchExample(): Unit = {
    //#watch
    val ref: ActorRef = someActor()
    val flow: Flow[String, String, NotUsed] =
      Flow[String].watch(ref).recover {
        case _: WatchedActorTerminatedException => s"$ref terminated"
      }
    //#watch
  }

}
