/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.concurrent.Await
import scala.util.control.NoStackTrace
import akka.stream.testkit.AkkaSpec
import akka.testkit.DefaultTimeout

class FlowFoldSpec extends AkkaSpec with DefaultTimeout {
  implicit val mat = FlowMaterializer()

  "A Fold" must {

    "fold" in {
      val input = 1 to 100
      val foldSink = FoldSink[Int, Int](0)(_ + _)
      val mf = FlowFrom(input).withSink(foldSink).run()
      val future = foldSink.future(mf)
      val expected = input.fold(0)(_ + _)
      Await.result(future, timeout.duration) should be(expected)
    }

    "propagate an error" in {
      val error = new Exception with NoStackTrace
      val foldSink = FoldSink[Unit, Unit](())((_, _) ⇒ ())
      val mf = FlowFrom[Unit](() ⇒ throw error).withSink(foldSink).run()
      val future = foldSink.future(mf)
      the[Exception] thrownBy Await.result(future, timeout.duration) should be(error)
    }

  }

}