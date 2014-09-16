/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.stream.testkit.AkkaSpec

class FlowFoldSpec extends AkkaSpec {
  implicit val mat = FlowMaterializer()
  import system.dispatcher

  "A Fold" must {

    "fold" in {
      val input = 1 to 100
      val foldSink = FoldSink[Int, Int](0)(_ + _)
      val mf = FlowFrom(input).withSink(foldSink).run()
      val future = foldSink.future(mf)
      val expected = input.fold(0)(_ + _)
      Await.result(future, 5.seconds) should be(expected)
    }

  }

}