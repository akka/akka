package akka.stream.scaladsl

import java.nio.file.Paths
import java.util.concurrent.TimeoutException

import akka.NotUsed
import akka.stream.testkit.{ StreamSpec, TestSubscriber, Utils }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.testkit.TestProbe

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class GroupByUnsafeSpec extends StreamSpec {

  implicit val materializer = ActorMaterializer()

  "FlowInitForEachUnique" must {

    "groupByUnsafe" in Utils.assertAllStagesStopped {
      val p = TestProbe()

      val source = Source(List(
        "a-data", "a-data",
        "b-data", "b-data",
        "a-data", "c-data"
      ))

      source.groupByUnique[String](
        maxSubstreams = 10,
        extractKey = _.head.toString,
        initSink = key ⇒ Sink.foreach { t ⇒ p.ref ! s"KEY:$key=$t" }
      ).run()

      p.expectMsg("KEY:a=a-data")
      p.expectMsg("KEY:a=a-data")
      p.expectMsg("KEY:b=b-data")
      p.expectMsg("KEY:b=b-data")
      p.expectMsg("KEY:a=a-data")
      p.expectMsg("KEY:c=c-data")
    }
  }

}
