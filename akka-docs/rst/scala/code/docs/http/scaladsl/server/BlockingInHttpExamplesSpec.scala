/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.scaladsl.server

import akka.actor.ActorSystem
import akka.http.scaladsl.server.{ Directives, Route }
import docs.CompileOnlySpec
import org.scalatest.WordSpec

import scala.concurrent.Future

class BlockingInHttpExamplesSpec extends WordSpec with CompileOnlySpec
  with Directives {

  compileOnlySpec {
    val system: ActorSystem = ???

    //#blocking-example-in-default-dispatcher
    // BAD (due to blocking in Future, on default dispatcher)
    implicit val defaultDispatcher = system.dispatcher

    val routes: Route = post {
      complete {
        Future { // uses defaultDispatcher
          Thread.sleep(5000) // will block on default dispatcher,
          System.currentTimeMillis().toString // Starving the routing infrastructure
        }
      }
    }
    //#
  }

  compileOnlySpec {
    val system: ActorSystem = ???

    //#blocking-example-in-dedicated-dispatcher
    // GOOD (the blocking is now isolated onto a dedicated dispatcher):
    implicit val blockingDispatcher = system.dispatchers.lookup("my-blocking-dispatcher")

    val routes: Route = post {
      complete {
        Future { // uses the good "blocking dispatcher" that we configured,
          // instead of the default dispatcher- the blocking is isolated.
          Thread.sleep(5000)
          System.currentTimeMillis().toString
        }
      }
    }
    //#
  }

}
