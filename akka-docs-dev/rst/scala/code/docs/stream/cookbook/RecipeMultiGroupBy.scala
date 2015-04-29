package docs.stream.cookbook

import akka.stream.scaladsl.{ Sink, Source }

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._

class RecipeMultiGroupBy extends RecipeSpec {

  "Recipe for multi-groupBy" must {

    "work" in {

      case class Topic(name: String)

      val elems = Source(List("1: a", "1: b", "all: c", "all: d", "1: e"))
      val topicMapper = { msg: Message =>
        if (msg.startsWith("1")) List(Topic("1"))
        else List(Topic("1"), Topic("2"))
      }

      class X {
        //#multi-groupby
        val topicMapper: (Message) => immutable.Seq[Topic] = ???

        //#multi-groupby
      }

      //#multi-groupby
      val messageAndTopic: Source[(Message, Topic), Unit] = elems.mapConcat { msg: Message =>
        val topicsForMessage = topicMapper(msg)
        // Create a (Msg, Topic) pair for each of the topics
        // the message belongs to
        topicsForMessage.map(msg -> _)
      }

      val multiGroups: Source[(Topic, Source[String, Unit]), Unit] = messageAndTopic.groupBy(_._2).map {
        case (topic, topicStream) =>
          // chopping of the topic from the (Message, Topic) pairs
          (topic, topicStream.map(_._1))
      }
      //#multi-groupby

      val result = multiGroups.map {
        case (topic, topicMessages) => topicMessages.grouped(10).map(topic.name + _.mkString("[", ", ", "]")).runWith(Sink.head)
      }.mapAsync(4)(identity).grouped(10).runWith(Sink.head)

      Await.result(result, 3.seconds).toSet should be(Set(
        "1[1: a, 1: b, all: c, all: d, 1: e]",
        "2[all: c, all: d]"))

    }

  }

}
