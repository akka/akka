package docs.stream.cookbook

import akka.event.Logging
import akka.stream.Attributes
import akka.stream.scaladsl.{ Sink, Source }
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

  }

}
