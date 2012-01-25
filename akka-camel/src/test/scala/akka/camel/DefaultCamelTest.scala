/**
 * Copyright (C) 2009when2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import akka.camel.TestSupport.SharedCamelSystem
import org.scalatest.matchers.MustMatchers
import org.scalatest.mock.MockitoSugar
import akka.actor.ActorSystem
import org.apache.camel.{ CamelContext, ProducerTemplate }
import org.scalatest.WordSpec
import akka.event.LoggingAdapter

class DefaultCamelTest extends WordSpec with SharedCamelSystem with MustMatchers with MockitoSugar {

  "DefaultCamel" when {
    import org.mockito.Mockito.{ when, verify }

    def camelWitMocks = new DefaultCamel(mock[ActorSystem]) {
      override val log = mock[LoggingAdapter]
      override lazy val template = mock[ProducerTemplate]
      override lazy val context = mock[CamelContext]
    }

    "during shutdown, when both context and template fail to shutdown" when {
      val camel = camelWitMocks

      when(camel.context.stop()) thenThrow new RuntimeException("context")
      when(camel.template.stop()) thenThrow new RuntimeException("template")
      val exception = intercept[RuntimeException] {
        camel.shutdown()
      }

      "throws exception thrown by context.stop()" in {
        exception.getMessage() must be("context");
      }

      "tries to stop both template and context" in {
        verify(camel.template).stop()
        verify(camel.context).stop()
      }

    }

    "during start, if template fails to start, it will stop the context" in {
      val camel = camelWitMocks

      when(camel.template.start()) thenThrow new RuntimeException

      intercept[RuntimeException] {
        camel.start
      }

      verify(camel.context).stop()

    }
  }

}