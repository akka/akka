/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.event.Logging
import akka.stream.Attributes.LogLevels
import akka.stream.testkit.{ StreamSpec, ScriptedTest }
import akka.stream._
import akka.testkit.TestProbe

class FlowWithContextLogSpec extends StreamSpec("""
     akka.loglevel = DEBUG # test verifies logging
     akka.actor.serialize-messages = off
     """) with ScriptedTest {

  implicit val mat: Materializer = ActorMaterializer()

  val logProbe = {
    val p = TestProbe()
    system.eventStream.subscribe(p.ref, classOf[Logging.LogEvent])
    p
  }

  "log() from FlowWithContextOps" must {

    val supervisorPath = ActorMaterializerHelper.downcast(mat).supervisor.path
    val LogSrc = s"akka.stream.Log($supervisorPath)"
    val LogClazz = classOf[Materializer]

    "on FlowWithContext" must {

      "log each element" in {
        val logging = FlowWithContext[Message, Long].log("my-log")
        Source(List(Message("a", 1L), Message("b", 2L)))
          .startContextPropagation(m ⇒ m.offset)
          .via(logging)
          .endContextPropagation
          .runWith(Sink.ignore)

        logProbe.expectMsg(Logging.Debug(LogSrc, LogClazz, "[my-log] Element: Message(a,1)"))
        logProbe.expectMsg(Logging.Debug(LogSrc, LogClazz, "[my-log] Element: Message(b,2)"))
        logProbe.expectMsg(Logging.Debug(LogSrc, LogClazz, "[my-log] Upstream finished."))
      }

      "allow extracting value to be logged" in {
        val logging = FlowWithContext[Message, Long].log("my-log2", m ⇒ m.data)
        Source(List(Message("a", 1L)))
          .startContextPropagation(m ⇒ m.offset)
          .via(logging)
          .endContextPropagation
          .runWith(Sink.ignore)

        logProbe.expectMsg(Logging.Debug(LogSrc, LogClazz, "[my-log2] Element: a"))
        logProbe.expectMsg(Logging.Debug(LogSrc, LogClazz, "[my-log2] Upstream finished."))
      }

      "allow disabling element logging" in {
        val disableElementLogging = Attributes.logLevels(
          onElement = LogLevels.Off,
          onFinish = Logging.DebugLevel,
          onFailure = Logging.DebugLevel)

        val logging = FlowWithContext[Message, Long].log("my-log3")
        Source(List(Message("a", 1L), Message("b", 2L)))
          .startContextPropagation(m ⇒ m.offset)
          .via(logging)
          .endContextPropagation
          .withAttributes(disableElementLogging)
          .runWith(Sink.ignore)

        logProbe.expectMsg(Logging.Debug(LogSrc, LogClazz, "[my-log3] Upstream finished."))
      }

    }

    "on SourceWithContext" must {

      "log each element" in {
        Source(List(Message("a", 1L), Message("b", 2L)))
          .startContextPropagation(m ⇒ m.offset)
          .log("my-log4")
          .endContextPropagation
          .runWith(Sink.ignore)

        logProbe.expectMsg(Logging.Debug(LogSrc, LogClazz, "[my-log4] Element: Message(a,1)"))
        logProbe.expectMsg(Logging.Debug(LogSrc, LogClazz, "[my-log4] Element: Message(b,2)"))
        logProbe.expectMsg(Logging.Debug(LogSrc, LogClazz, "[my-log4] Upstream finished."))
      }

      "allow extracting value to be logged" in {
        Source(List(Message("a", 1L)))
          .startContextPropagation(m ⇒ m.offset)
          .log("my-log5", m ⇒ m.data)
          .endContextPropagation
          .runWith(Sink.ignore)

        logProbe.expectMsg(Logging.Debug(LogSrc, LogClazz, "[my-log5] Element: a"))
        logProbe.expectMsg(Logging.Debug(LogSrc, LogClazz, "[my-log5] Upstream finished."))
      }

    }
  }

}
