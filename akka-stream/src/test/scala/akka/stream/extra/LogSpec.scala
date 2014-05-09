/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.extra

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.impl.ActorBasedFlowMaterializer
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.testkit.EventFilter
import akka.stream.impl.FlowNameCounter

object LogSpec {
  class TestException extends IllegalArgumentException("simulated err") with NoStackTrace
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class LogSpec extends AkkaSpec("akka.loglevel=INFO") {
  import LogSpec._

  val materializer = FlowMaterializer(MaterializerSettings())

  def flowCount = FlowNameCounter(system).counter.get
  def nextFlowCount = flowCount + 1

  "Log Transformer" must {

    "log onNext elements" in {
      EventFilter.info(source = s"akka://LogSpec/user/flow-$nextFlowCount-1-log", pattern = """OnNext: \[[1|2|3]\]""", occurrences = 3) intercept {
        Flow(List(1, 2, 3)).
          transform(Log()).
          consume(materializer)
      }
    }

    "log onComplete" in {
      EventFilter.info(source = s"akka://LogSpec/user/flow-$nextFlowCount-1-log", message = "OnComplete", occurrences = 1) intercept {
        Flow(Nil).
          transform(Log()).
          consume(materializer)
      }
    }

    "log onError exception" in {
      // FIXME the "failure during processing" occurrence comes from ActorProcessImpl#fail, and will probably be removed
      EventFilter[TestException](source = s"akka://LogSpec/user/flow-$nextFlowCount-2-mylog",
        pattern = "[OnError: simulated err|failure during processing]", occurrences = 2) intercept {
          Flow(List(1, 2, 3)).
            map(i ⇒ if (i == 2) throw new TestException else i).
            transform(Log(name = "mylog")).
            consume(materializer)
        }
    }

    "have type inference" in {
      val f1: Flow[Int] = Flow(List(1, 2, 3)).transform(Log[Int])
      val f2: Flow[Int] = Flow(List(1, 2, 3)).transform(Log())
      val f3: Flow[String] = Flow(List(1, 2, 3)).transform(Log[Int]).map((i: Int) ⇒ i.toString).transform(Log[String])
      val f4: Flow[String] = Flow(List(1, 2, 3)).transform(Log()).map((i: Int) ⇒ i.toString).transform(Log())
      val f5: Flow[String] =
        Flow(List(1, 2, 3)).transform(new Log[Int](name = "mylog") {
          override def logOnNext(i: Int): Unit =
            log.debug("Got element {}", i)
        }).map((i: Int) ⇒ i.toString)
    }

    "have nice DSL" in {
      import akka.stream.extra.Implicits._
      val f: Flow[String] = Flow(List(1, 2, 3)).log().map((i: Int) ⇒ i.toString).log()
    }

  }

}