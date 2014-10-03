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
      val foldDrain = FoldDrain[Int, Int](0)(_ + _)
      val mf = Source(input).connect(foldDrain).run()
      val future = foldDrain.future(mf)
      val expected = input.fold(0)(_ + _)
      Await.result(future, timeout.duration) should be(expected)
    }

    "propagate an error" in {
      val error = new Exception with NoStackTrace
      val foldSink = FoldDrain[Unit, Unit](())((_, _) ⇒ ())
      val mf = Source[Unit](() ⇒ throw error).connect(foldSink).run()
      val future = foldSink.future(mf)
      the[Exception] thrownBy Await.result(future, timeout.duration) should be(error)
    }

  }

}