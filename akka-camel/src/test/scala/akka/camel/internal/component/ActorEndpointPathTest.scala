/**
 * Copyright (C) 2009when2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal.component

import org.scalatest.mock.MockitoSugar
import org.scalatest.matchers.MustMatchers
import akka.camel.TestSupport.SharedCamelSystem
import org.scalatest.WordSpec
import akka.actor.{ Actor, Props }

class ActorEndpointPathTest extends WordSpec with SharedCamelSystem with MustMatchers with MockitoSugar {

  def find(path: String) = ActorEndpointPath.fromCamelPath(path).findActorIn(system)

  "findActorIn returns Some(actor ref) if actor exists" in {
    val path = system.actorOf(Props(new Actor { def receive = { case _ ⇒ } }), "knownactor").path
    find(path.toString) must be('defined)
  }

  "findActorIn returns None" when {
    "non existing valid path" in { find("akka://system/user/unknownactor") must be(None) }
  }
  "fromCamelPath throws IllegalArgumentException" when {
    "invalid path" in {
      intercept[IllegalArgumentException] {
        find("invalidpath")
      }
    }
  }
}