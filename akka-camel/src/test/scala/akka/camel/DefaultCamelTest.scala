/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

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

    "during shutdown, when both context and template fail to shutdown" - {
      val camel = camelWitMocks

      when(camel.context.stop()) thenThrow new RuntimeException("context")
      when(camel.template.stop()) thenThrow new RuntimeException("template")
      val exception = intercept[RuntimeException]{
        camel.shutdown()
      } 

      "throws exception thrown by context.stop()" in{
        exception.getMessage() should be ("context");
      }

      "tries to stop both template and context" in{
        verify(camel.template).stop()
        verify(camel.context).stop()
      }

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