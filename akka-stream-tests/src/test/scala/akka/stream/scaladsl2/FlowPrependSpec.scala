/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.MaterializerSettings
import akka.stream.testkit.AkkaSpec

class FlowPrependSpec extends AkkaSpec with River {

  val settings = MaterializerSettings(system)
  implicit val materializer = FlowMaterializer(settings)

  "ProcessorFlow" should {
    "prepend ProcessorFlow" in riverOf[String] { subscriber ⇒
      FlowFrom[String]
        .prepend(otherFlow)
        .withSource(IterableSource(elements))
        .publishTo(subscriber)
    }

    "prepend FlowWithSource" in riverOf[String] { subscriber ⇒
      FlowFrom[String]
        .prepend(otherFlow.withSource(IterableSource(elements)))
        .publishTo(subscriber)
    }
  }

  "FlowWithSink" should {
    "prepend ProcessorFlow" in riverOf[String] { subscriber ⇒
      FlowFrom[String]
        .withSink(SubscriberSink(subscriber))
        .prepend(otherFlow)
        .withSource(IterableSource(elements))
        .run()
    }

    "prepend FlowWithSource" in riverOf[String] { subscriber ⇒
      FlowFrom[String]
        .withSink(SubscriberSink(subscriber))
        .prepend(otherFlow.withSource(IterableSource(elements)))
        .run()
    }
  }

}
