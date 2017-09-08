/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import akka.cluster.ClusterEvent.{MemberEvent, MemberUp}
import akka.typed.ActorSystem
import akka.typed.scaladsl.Actor
import akka.typed.cluster.Cluster._

class ClusterApiSpec {

  // Compile only for now

  val system: ActorSystem[AnyRef] = ???


  val cluster = Cluster(system)

  val subscriber = Actor.immutable[MemberEvent] { (ctx, msg) =>

    msg match {
      case _: MemberUp =>
        cluster.subscriptions ! Unsubscribe(ctx.self)
        Actor.same

      case other =>
        println(s"Got cluster state event $other")
        Actor.same
    }




  }

  cluster.subscriptions ! Subscribe[MemberEvent](subscriber)


}
