/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.persistence.query

import akka.persistence.PersistentRepr
import akka.persistence.query.{ EventEnvelope, Sequence }
import akka.serialization.SerializationExtension
import akka.stream.{ ActorMaterializer, Attributes, Outlet, SourceShape }
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler, TimerGraphStageLogic }

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

//#events-by-tag-publisher
class MyEventsByTagSource(tag: String, offset: Long, refreshInterval: FiniteDuration)
    extends GraphStage[SourceShape[EventEnvelope]] {

  private case object Continue
  val out: Outlet[EventEnvelope] = Outlet("MyEventByTagSource.out")
  override def shape: SourceShape[EventEnvelope] = SourceShape(out)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with OutHandler {
      lazy val system = materializer match {
        case a: ActorMaterializer => a.system
        case _ =>
          throw new IllegalStateException("EventsByTagStage requires ActorMaterializer")
      }
      private val Limit = 1000
      private val connection: java.sql.Connection = ???
      private var currentOffset = offset
      private var buf = Vector.empty[EventEnvelope]

      override def preStart(): Unit = {
        schedulePeriodically(Continue, refreshInterval)
      }

      override def onPull(): Unit = {
        query()
        tryPush()
      }

      override def onDownstreamFinish(): Unit = {
        // close connection if responsible for doing so
      }

      private def query(): Unit = {
        if (buf.isEmpty) {
          try {
            val result = Select.run(tag, currentOffset, Limit)
            currentOffset = if (result.nonEmpty) result.last._1 else currentOffset
            val serialization = SerializationExtension(system)

            buf = result.map {
              case (id, bytes) =>
                val p = serialization.deserialize(bytes, classOf[PersistentRepr]).get
                EventEnvelope(offset = Sequence(id), p.persistenceId, p.sequenceNr, p.payload)
            }
          } catch {
            case NonFatal(e) =>
              failStage(e)
          }
        }
      }

      private def tryPush(): Unit = {
        if (buf.nonEmpty && isAvailable(out)) {
          push(out, out)
          buf = buf.tail
        }
      }

      override protected def onTimer(timerKey: Any): Unit = timerKey match {
        case Continue =>
          query()
          tryPush()
      }

      object Select {
        private def statement() = connection.prepareStatement("""
        SELECT id, persistent_repr FROM journal
        WHERE tag = ? AND id > ?
        ORDER BY id LIMIT ?
      """)

        def run(tag: String, from: Long, limit: Int): Vector[(Long, Array[Byte])] = {
          val s = statement()
          try {
            s.setString(1, tag)
            s.setLong(2, from)
            s.setLong(3, limit)
            val rs = s.executeQuery()

            val b = Vector.newBuilder[(Long, Array[Byte])]
            while (rs.next()) b += (rs.getLong(1) -> rs.getBytes(2))
            b.result()
          } finally s.close()
        }
      }
    }

}
//#events-by-tag-publisher
