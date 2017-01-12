/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.camel

import akka.camel.TestSupport.SharedCamelSystem
import internal.DefaultCamel
import org.scalatest.Matchers
import org.scalatest.mock.MockitoSugar
import org.apache.camel.ProducerTemplate
import org.scalatest.WordSpec
import akka.event.{ LoggingAdapter, MarkerLoggingAdapter }
import akka.actor.ActorSystem.Settings
import com.typesafe.config.ConfigFactory
import org.apache.camel.impl.DefaultCamelContext
import akka.actor.ExtendedActorSystem

class DefaultCamelTest extends WordSpec with SharedCamelSystem with Matchers with MockitoSugar {

  import org.mockito.Mockito.{ when, verify }
  val sys = mock[ExtendedActorSystem]
  val config = ConfigFactory.defaultReference()
  when(sys.dynamicAccess) thenReturn system.asInstanceOf[ExtendedActorSystem].dynamicAccess
  when(sys.settings) thenReturn (new Settings(this.getClass.getClassLoader, config, "mocksystem"))
  when(sys.name) thenReturn ("mocksystem")

  def camelWithMocks = new DefaultCamel(sys) {
    override val log = mock[MarkerLoggingAdapter]
    override lazy val template = mock[ProducerTemplate]
    override lazy val context = mock[DefaultCamelContext]
    override val settings = mock[CamelSettings]
  }

  "during shutdown, when both context and template fail to shutdown" when {
    val camel = camelWithMocks

    when(camel.context.stop()) thenThrow new RuntimeException("context")
    when(camel.template.stop()) thenThrow new RuntimeException("template")
    val exception = intercept[RuntimeException] {
      camel.shutdown()
    }

    "throws exception thrown by context.stop()" in {
      exception.getMessage() should ===("context");
    }

    "tries to stop both template and context" in {
      verify(camel.template).stop()
      verify(camel.context).stop()
    }

  }

  "during start, if template fails to start, it will stop the context" in {
    val camel = camelWithMocks

    when(camel.template.start()) thenThrow new RuntimeException

    intercept[RuntimeException] {
      camel.start
    }

    verify(camel.context).stop()

  }
}
