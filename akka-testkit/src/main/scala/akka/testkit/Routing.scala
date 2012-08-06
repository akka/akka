/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import scala.annotation.tailrec

import akka.actor.{ UnstartedCell, ActorRef }
import akka.routing.{ RoutedActorRef, RoutedActorCell, Route }

/**
 * This object can be used to extract the `Route` out of a RoutedActorRef.
 * These are the refs which represent actors created from [[akka.actor.Props]]
 * having a [[akka.routing.RouterConfig]]. Use this extractor if you want to
 * test the routing directly, i.e. without actually dispatching messages.
 *
 * {{{
 * val router = system.actorOf(Props[...].withRouter(new MyRouter))
 * val route = ExtractRoute(router)
 * route(sender -> message) must be(...)
 * }}}
 */
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
