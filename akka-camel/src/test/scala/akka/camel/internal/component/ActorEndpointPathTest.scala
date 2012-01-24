/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal.component

import org.scalatest.mock.MockitoSugar
import org.scalatest.matchers.ShouldMatchers
import akka.camel.TestSupport.SharedCamelSystem
import org.scalatest.FreeSpec
import akka.actor.Props

class ActorEndpointPathTest extends FreeSpec with SharedCamelSystem with ShouldMatchers with MockitoSugar {

  "ActorEndpointPath" - {
    def find(path: String) = ActorEndpointPath.fromCamelPath("path:" + path).findActorIn(system)

    "findActorIn" - {
      "returns Some(actor ref) if actor exists" in {
        val path = system.actorOf(Props(behavior = ctx ⇒ { case _ ⇒ {} })).path
        find(path.toString) should be('defined)
      }

      "returns None for" - {
        "invalid path" in { find("some_invalid_path") should be(None) }
        "non existing valid path" in { find("akka://system/user/$a") should be(None) }
      }
    }
  }

}