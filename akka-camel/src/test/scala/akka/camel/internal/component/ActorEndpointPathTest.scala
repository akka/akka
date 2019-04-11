/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.camel.internal.component

import org.scalatest.mock.MockitoSugar
import org.scalatest.Matchers
import akka.camel.TestSupport.SharedCamelSystem
import org.scalatest.WordSpec
import akka.actor.{ Actor, Props }

class ActorEndpointPathTest extends WordSpec with SharedCamelSystem with Matchers with MockitoSugar {

  def find(path: String) = ActorEndpointPath.fromCamelPath(path).findActorIn(system)

  "findActorIn returns Some(actor ref) if actor exists" in {
    val path = system.actorOf(Props(new Actor { def receive = { case _ => } }), "knownactor").path
    find(path.toString) should be('defined)
  }

  "findActorIn returns None" when {
    "non existing valid path" in { find("akka://system/user/unknownactor") should ===(None) }
  }
  "fromCamelPath throws IllegalArgumentException" when {
    "invalid path" in {
      intercept[IllegalArgumentException] {
        find("invalidpath")
      }
    }
  }
}
