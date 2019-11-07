/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.snapshot

import akka.stream.FlowShape
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Merge
import akka.stream.scaladsl.Partition
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.scaladsl.TestSink

import scala.concurrent.Promise

class MaterializerStateSpec extends StreamSpec {

  "The MaterializerSnapshotting" must {

    "snapshot a running stream" in {
      implicit val mat = Materializer(system)
      try {
        Source.maybe[Int].map(_.toString).zipWithIndex.runWith(Sink.seq)

        awaitAssert({
          val snapshot = MaterializerState.streamSnapshots(mat).futureValue

          snapshot should have size (1)
          snapshot.head.activeInterpreters should have size (1)
          snapshot.head.activeInterpreters.head.logics should have size (4) // all 4 operators
        }, remainingOrDefault)
      } finally {
        mat.shutdown()
      }
    }

    "snapshot a running stream on the default dispatcher" in {
      val promise = Promise[Int]()
      Source.future(promise.future).map(_.toString).zipWithIndex.runWith(Sink.seq)

      awaitAssert({
        val snapshot = MaterializerState.streamSnapshots(system).futureValue

        snapshot should have size (1)
        snapshot.head.activeInterpreters should have size (1)
        snapshot.head.activeInterpreters.head.logics should have size (4) // all 4 operators
      }, remainingOrDefault)
      promise.success(1)
    }

    "snapshot a stream that has a stopped stage" in {
      implicit val mat = Materializer(system)
      try {
        val probe = TestSink.probe[String](system)
        val out = Source
          .single("one")
          .concat(Source.maybe[String]) // make sure we leave it running
          .runWith(probe)
        out.requestNext("one")
        awaitAssert({
          val snapshot = MaterializerState.streamSnapshots(mat).futureValue
          snapshot should have size (1)
          snapshot.head.activeInterpreters should have size (1)
          snapshot.head.activeInterpreters.head.stoppedLogics should have size (2) // Source.single and a detach
        }, remainingOrDefault)

      } finally {
        mat.shutdown()
      }
    }

    "snapshot a more complicated graph" in {
      implicit val mat = Materializer(system)
      try {
        // snapshot before anything is running
        MaterializerState.streamSnapshots(mat).futureValue

        val graph = Flow.fromGraph(GraphDSL.create() { implicit b =>
          import GraphDSL.Implicits._
          val partition = b.add(Partition[String](4, {
            case "green" => 0
            case "red"   => 1
            case "blue"  => 2
            case _       => 3
          }))
          val merge = b.add(Merge[String](4, eagerComplete = false))
          val discard = b.add(Sink.ignore.async)
          val one = b.add(Source.single("purple"))

          partition.out(0) ~> merge.in(0)
          partition.out(1).via(Flow[String].map(_.toUpperCase()).async) ~> merge.in(1)
          partition.out(2).groupBy(2, identity).mergeSubstreams ~> merge.in(2)
          partition.out(3) ~> discard

          one ~> merge.in(3)

          FlowShape(partition.in, merge.out)
        })

        val callMeMaybe =
          Source.maybe[String].viaMat(graph)(Keep.left).toMat(Sink.ignore)(Keep.left).run()

        // just check that we can snapshot without errors
        MaterializerState.streamSnapshots(mat).futureValue
        callMeMaybe.success(Some("green"))
        MaterializerState.streamSnapshots(mat).futureValue
        Thread.sleep(100) // just to give it a bigger chance to cover different states of shutting down
        MaterializerState.streamSnapshots(mat).futureValue

      } finally {
        mat.shutdown()
      }
    }

  }

}
