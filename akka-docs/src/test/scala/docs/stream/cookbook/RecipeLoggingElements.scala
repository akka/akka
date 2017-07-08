package docs.stream.cookbook

import akka.event.Logging
import akka.stream.{ Attributes, OverflowStrategy }
import akka.stream.scaladsl.{ Keep, Sink, Source, SourceQueueWithComplete }
import akka.stream.testkit.TestSubscriber
import akka.testkit.{ EventFilter, TestProbe }

class RecipeLoggingElements extends RecipeSpec {

  "Simple logging recipe" must {

    "work with println" in {
      val printProbe = TestProbe()
      def println(s: String): Unit = printProbe.ref ! s

      val mySource = Source(List("1", "2", "3"))

      //#println-debug
      val loggedSource = mySource.map { elem => println(elem); elem }
      //#println-debug

      loggedSource.runWith(Sink.ignore)
      printProbe.expectMsgAllOf("1", "2", "3")
    }

    "use log()" in {
      val mySource = Source(List("1", "2", "3"))
      def analyse(s: String) = s

      //#log-custom
      // customise log levels
      mySource.log("before-map")
        .withAttributes(Attributes.logLevels(onElement = Logging.WarningLevel))
        .map(analyse)

      // or provide custom logging adapter
      implicit val adapter = Logging(system, "customLogger")
      mySource.log("custom")
      //#log-custom

      val loggedSource = mySource.log("custom")
      EventFilter.debug(start = "[custom] Element: ").intercept {
        loggedSource.runWith(Sink.ignore)
      }

    }

    "enable verbose logging" in {
      val mySource = Source.queue[Int](100, OverflowStrategy.dropHead)
      val s = TestSubscriber.manualProbe[Int]()

      //#verbose-logging
      // enable verbose logging in source stage
      mySource.addAttributes(Attributes.verboseLogging)
      //#verbose-logging

      val queueSource: SourceQueueWithComplete[Int] =
        mySource.addAttributes(Attributes.verboseLogging)
          .toMat(Sink.fromSubscriber(s))(Keep.left).run()

      EventFilter.warning(pattern = "Queue is using .* of its buffer capacity", occurrences = 6).intercept {
        for (n ← 1 to 100) queueSource.offer(n)
      }

    }
  }

}
