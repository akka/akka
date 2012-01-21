package akka.camel

import internal.component.ActorEndpointPath
import org.scalatest.FreeSpec
import akka.camel.TestSupport.SharedCamelSystem
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.MockitoSugar
import akka.actor.{ActorSystem, Props}
import org.apache.camel.{CamelContext, ProducerTemplate}


class DefaultCamelTest extends FreeSpec with SharedCamelSystem with ShouldMatchers with MockitoSugar{

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

  "DefaultCamel" - {
    import org.mockito.Mockito.{when,  verify}

    def camelWitMocks = new DefaultCamel(mock[ActorSystem]){
      override lazy val template = mock[ProducerTemplate]
      override lazy val context = mock[CamelContext]
    }

    "during shutdown, stops both template and context even if one of them fails to shutdown" in {
      val camel = camelWitMocks

      when(camel.template.stop()) thenThrow new RuntimeException
      when(camel.context.stop()) thenThrow new RuntimeException

      intercept[RuntimeException]{
        camel.shutdown()
      }

      verify(camel.template).stop()
      verify(camel.context).stop()

    }

    "during start, if template fails to start, it will stop the context" in {
      val camel = camelWitMocks

      when(camel.template.start()) thenThrow new RuntimeException

      intercept[RuntimeException]{
        camel.start
      }

      verify(camel.context).stop()

    }
  }

}