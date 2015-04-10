/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import scala.concurrent.Await
import akka.stream.ActorFlowMaterializer
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.Supervision
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec
import akka.stream.OperationAttributes
import akka.stream.ActorOperationAttributes

class FlowErrorDocSpec extends AkkaSpec {

  "demonstrate fail stream" in {
    //#stop
    implicit val mat = ActorFlowMaterializer()
    val source = Source(0 to 5).map(100 / _)
    val result = source.runWith(Sink.fold(0)(_ + _))
    // division by zero will fail the stream and the
    // result here will be a Future completed with Failure(ArithmeticException)
    //#stop

    intercept[ArithmeticException] {
      Await.result(result, remaining)
    }
  }

  "demonstrate resume stream" in {
    //#resume
    val decider: Supervision.Decider = {
      case _: ArithmeticException => Supervision.Resume
      case _                      => Supervision.Stop
    }
    implicit val mat = ActorFlowMaterializer(
      ActorFlowMaterializerSettings(system).withSupervisionStrategy(decider))
    val source = Source(0 to 5).map(100 / _)
    val result = source.runWith(Sink.fold(0)(_ + _))
    // the element causing division by zero will be dropped
    // result here will be a Future completed with Success(228)
    //#resume

    Await.result(result, remaining) should be(228)
  }

  "demonstrate resume section" in {
    //#resume-section
    implicit val mat = ActorFlowMaterializer()
    val decider: Supervision.Decider = {
      case _: ArithmeticException => Supervision.Resume
      case _                      => Supervision.Stop
    }
    val flow = Flow[Int]
      .filter(100 / _ < 50).map(elem => 100 / (5 - elem))
      .withAttributes(ActorOperationAttributes.supervisionStrategy(decider))
    val source = Source(0 to 5).via(flow)

    val result = source.runWith(Sink.fold(0)(_ + _))
    // the elements causing division by zero will be dropped
    // result here will be a Future completed with Success(150)
    //#resume-section

    Await.result(result, remaining) should be(150)
  }

  "demonstrate restart section" in {
    //#restart-section
    implicit val mat = ActorFlowMaterializer()
    val decider: Supervision.Decider = {
      case _: IllegalArgumentException => Supervision.Restart
      case _                           => Supervision.Stop
    }
    val flow = Flow[Int]
      .scan(0) { (acc, elem) =>
        if (elem < 0) throw new IllegalArgumentException("negative not allowed")
        else acc + elem
      }
      .withAttributes(ActorOperationAttributes.supervisionStrategy(decider))
    val source = Source(List(1, 3, -1, 5, 7)).via(flow)
    val result = source.grouped(1000).runWith(Sink.head)
    // the negative element cause the scan stage to be restarted,
    // i.e. start from 0 again
    // result here will be a Future completed with Success(Vector(0, 1, 0, 5, 12))
    //#restart-section

    Await.result(result, remaining) should be(Vector(0, 1, 0, 5, 12))
  }

}
