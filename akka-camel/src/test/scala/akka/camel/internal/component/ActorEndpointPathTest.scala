/**
 * Copyright (C) 2009when2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal.component

import org.scalatest.mock.MockitoSugar
import org.scalatest.matchers.MustMatchers
import akka.camel.TestSupport.SharedCamelSystem
import akka.actor.Props
import org.scalatest.{WordSpec}

class ActorEndpointPathTest extends WordSpec with SharedCamelSystem with MustMatchers with MockitoSugar {

  def find(path: String) = ActorEndpointPath.fromCamelPath("path:" + path).findActorIn(system)

  "findActorIn returns Some(actor ref) if actor exists" in {
    val path = system.actorOf(Props(behavior = ctx ⇒ { case _ ⇒ {} })).path
    find(path.toString) must be('defined)
  }

  "findActorIn returns None" when {
    "invalid path" in { find("some_invalid_path") must be(None) }
    "non existing valid path" in { find("akka://system/user/$a") must be(None) }
  }

}