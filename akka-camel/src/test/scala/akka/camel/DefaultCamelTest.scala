package akka.camel

import internal.component.ActorEndpointPath
import org.scalatest.FreeSpec
import akka.camel.TestSupport.SharedCamelSystem
import org.scalatest.matchers.ShouldMatchers
import akka.actor.Props


class DefaultCamelTest extends FreeSpec with SharedCamelSystem with ShouldMatchers{

  "ConsumerRegistry" -{
    def find(path : String) = camel.findActor(ActorEndpointPath.fromCamelPath("path:"+path))

    "findActor" -{
      "returns Some(actor ref) if actor exists" in{
        val path = system.actorOf(Props(behavior = ctx => {case _ => {}})).path
        find(path.toString) should be ('defined)
      }

      "returns None for" -{
        "invalid path" in { find("some_invalid_path") should be (None)}
        "non existing valid path" in { find("akka://system/user/$a") should be (None)}
      }
    }
  }
}