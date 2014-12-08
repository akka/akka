package docs.stream.cookbook

import akka.event.Logging
import akka.stream.scaladsl.{ Sink, Source, Flow }
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

    "work with PushStage" in {
      val mySource = Source(List("1", "2", "3"))

      //#loggingadapter
      import akka.stream.stage._
      class LoggingStage[T] extends PushStage[T, T] {
        private val log = Logging(system, "loggingName")

        override def onPush(elem: T, ctx: Context[T]): Directive = {
          log.debug("Element flowing through: {}", elem)
          ctx.push(elem)
        }

        override def onUpstreamFailure(cause: Throwable,
                                       ctx: Context[T]): TerminationDirective = {
          log.error(cause, "Upstream failed.")
          super.onUpstreamFailure(cause, ctx)
        }

        override def onUpstreamFinish(ctx: Context[T]): TerminationDirective = {
          log.debug("Upstream finished")
          super.onUpstreamFinish(ctx)
        }
      }

      val loggedSource = mySource.transform(() => new LoggingStage)
      //#loggingadapter

      EventFilter.debug(start = "Element flowing").intercept {
        loggedSource.runWith(Sink.ignore)
      }

    }

  }

}
