/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.typed

import ScalaDSL._
import Ops._
import AskPattern._
import StepWise._
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.Future
import com.typesafe.config.ConfigFactory

class PerformanceSpec extends TypedSpec {

  object `A static behavior` {

    object `must be fast` {

      case class Ping(x: Int, pong: ActorRef[Pong], report: ActorRef[Pong])
      case class Pong(x: Int, ping: ActorRef[Ping], report: ActorRef[Pong])

      def behavior(pairs: Int, pings: Int, count: Int, executor: String) =
        StepWise[Pong] { (ctx, startWith) ⇒
          startWith {
            import ctx.executionContext

            val pinger = Props(SelfAware[Ping](self ⇒ Static { msg ⇒
              if (msg.x == 0) {
                msg.report ! Pong(0, self, msg.report)
              } else msg.pong ! Pong(msg.x - 1, self, msg.report)
            })).withDispatcher(executor)

            val ponger = Props(SelfAware[Pong](self ⇒ Static { msg ⇒
              msg.ping ! Ping(msg.x, self, msg.report)
            })).withDispatcher(executor)

            val actors =
              for (i ← 1 to pairs)
                yield (ctx.spawn(pinger, s"pinger-$i"), ctx.spawn(ponger, s"ponger-$i"))

            val start = Deadline.now

            for {
              (ping, pong) ← actors
              _ ← 1 to pings
            } ping ! Ping(count, pong, ctx.self)

            start
          }.expectMultipleMessages(15.seconds, pairs * pings) { (msgs, start) ⇒
            val stop = Deadline.now

            val rate = 2L * count * pairs * pings / (stop - start).toMillis
            info(s"messaging rate was $rate/ms")
          }
        }

      def `01 when warming up`(): Unit = sync(runTest("01")(behavior(1, 1, 1000000, "dispatcher-1")))
      def `02 when using a single message on a single thread`(): Unit = sync(runTest("02")(behavior(1, 1, 1000000, "dispatcher-1")))
      def `03 when using a 10 messages on a single thread`(): Unit = sync(runTest("03")(behavior(1, 10, 1000000, "dispatcher-1")))
      def `04 when using a single message on two threads`(): Unit = sync(runTest("04")(behavior(1, 1, 1000000, "dispatcher-2")))
      def `05 when using a 10 messages on two threads`(): Unit = sync(runTest("05")(behavior(1, 10, 1000000, "dispatcher-2")))
      def `06 when using 4 pairs with a single message`(): Unit = sync(runTest("06")(behavior(4, 1, 1000000, "dispatcher-8")))
      def `07 when using 4 pairs with 10 messages`(): Unit = sync(runTest("07")(behavior(4, 10, 1000000, "dispatcher-8")))
      def `08 when using 8 pairs with a single message`(): Unit = sync(runTest("08")(behavior(8, 1, 1000000, "dispatcher-8")))
      def `09 when using 8 pairs with 10 messages`(): Unit = sync(runTest("09")(behavior(8, 10, 1000000, "dispatcher-8")))

    }
  }

}