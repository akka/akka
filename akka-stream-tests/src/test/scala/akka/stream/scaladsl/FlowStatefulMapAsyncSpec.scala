/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.testkit.StreamSpec
import akka.stream.testkit.scaladsl.TestSink

import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.Future

class FlowStatefulMapAsyncSpec extends StreamSpec {

  implicit val ec = system.dispatcher

  def randomSleep(): Unit =
    Thread.sleep(ThreadLocalRandom.current().nextInt(1, 10))

  "statefulMapAsync" must {
    "worked in happy case and emit final state properly" in {

      val probe = Source(1 to 3)
        .statefulMapAsync[Int, String](1)(
          () =>
            Future {
              randomSleep()
              0
            },
          (_, e) =>
            Future {
              randomSleep()
              val state = e
              state -> s"$e element"
            },
          s => Future.successful(Some(s"$s state")),
          _ + _)
        .runWith(TestSink())

      val sub = probe.expectSubscription()
      sub.request(1)
      probe.expectNext("1 element")
      sub.request(1)
      probe.expectNext("2 element")
      sub.request(1)
      probe.expectNext("3 element")
      sub.request(1)
      probe.expectNext("6 state")
      probe.expectComplete()
    }

    "combine state orderly" in {
      val alphabet = "abcdefghijklmnopqrstuvwxyz"
      val probe = Source(alphabet)
        .statefulMapAsync[String, String](10)(
          () =>
            Future {
              randomSleep()
              ""
            },
          (str, char) =>
            Future {
              randomSleep()
              // current state isn't always the most updated snapshot
              char.toString -> str
            },
          str => Future.successful(Some(str)),
          _ ++ _)
        .runWith(TestSink())

      val sub = probe.ensureSubscription()

      sub.request(alphabet.length)
      probe.expectNextN(alphabet.length)
      sub.request(1)
      probe.expectNext(alphabet)
      probe.expectComplete()
    }

    "got deterministic state shows up in every call of f when parallelism = 1" in {
      val alphabet = "abcdefghijklmnopqrstuvwxyz"
      val probe = Source(alphabet)
        .statefulMapAsync[String, String](1)(
          () =>
            Future {
              randomSleep()
              ""
            },
          (str, char) =>
            Future {
              randomSleep()
              str.appended(char) -> char.toString
            },
          str => Future.successful(Some(str)),
          (_, y) => y)
        .runWith(TestSink())

      val sub = probe.ensureSubscription()

      sub.request(alphabet.length)
      probe.expectNextN(alphabet.length)
      sub.request(1)
      probe.expectNext(alphabet)
      probe.expectComplete()
    }

    "emulate mapAsync in happy case" in {
      implicit class EmulateMapAsync[Out, Mat](source: Source[Out, Mat]) {
        def emulatedMapAsync[U](parallelism: Int)(f: (Out) => Future[U]): Source[U, Mat] =
          source.statefulMapAsync[Unit, U](parallelism)(
            () => Future.unit,
            (_, out) => f(out).map(() -> _), // shouldn't relay on external ec, but leave this after stable API design
            _ => Future.successful(None),
            (_, _) => ())
      }

      val probe = Source(1 to 50)
        .emulatedMapAsync(10)(
          n =>
            if (n % 3 == 0) Future.successful(n)
            else
              Future {
                randomSleep()
                n
              })
        .runWith(TestSink())

      val sub = probe.expectSubscription()
      sub.request(1000)
      for (n <- 1 to 50) probe.expectNext(n)
      probe.expectComplete()
    }

  }

}
