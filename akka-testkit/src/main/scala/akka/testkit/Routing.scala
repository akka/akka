/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import scala.annotation.tailrec

import akka.actor.{ UnstartedCell, ActorRef }
import akka.routing.{ RoutedActorRef, RoutedActorCell, Route }

object ExtractRoute {
  def apply(ref: ActorRef): Route = {
    @tailrec def rec(tries: Int = 10): Route = {
      ref match {
        case r: RoutedActorRef ⇒
          r.underlying match {
            case rc: RoutedActorCell ⇒ rc.route
            case uc: UnstartedCell ⇒
              if (tries > 0) {
                Thread.sleep(100)
                rec(tries - 1)
              } else throw new IllegalStateException("no Route in unstarted cell (even after 1 second)")
            case x ⇒ throw new IllegalArgumentException("no Route in cell of type " + x.getClass)
          }
        case x ⇒ throw new IllegalArgumentException("no Route in ref of type " + x.getClass)
      }
    }
    rec()
  }
}
