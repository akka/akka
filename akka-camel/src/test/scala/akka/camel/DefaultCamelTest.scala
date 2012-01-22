package akka.camel

import org.scalatest.FreeSpec
import akka.camel.TestSupport.SharedCamelSystem
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.MockitoSugar
import akka.actor.ActorSystem
import org.apache.camel.{CamelContext, ProducerTemplate}


class DefaultCamelTest extends FreeSpec with SharedCamelSystem with ShouldMatchers with MockitoSugar{

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