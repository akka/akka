/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.persistence.query

import akka.actor.Props
import akka.persistence.PersistentRepr
import akka.persistence.query.{ EventEnvelope, Sequence }
import akka.serialization.SerializationExtension
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{ Cancel, Request }

import scala.concurrent.duration.FiniteDuration

object MyEventsByTagPublisher {
  def props(tag: String, offset: Long, refreshInterval: FiniteDuration): Props =
    Props(new MyEventsByTagPublisher(tag, offset, refreshInterval))
}

//#events-by-tag-publisher
class MyEventsByTagPublisher(tag: String, offset: Long, refreshInterval: FiniteDuration)
    extends ActorPublisher[EventEnvelope] {

  private case object Continue

  private val connection: java.sql.Connection = ???

  private val Limit = 1000
  private var currentOffset = offset
  var buf = Vector.empty[EventEnvelope]

  import context.dispatcher
  val continueTask = context.system.scheduler.schedule(refreshInterval, refreshInterval, self, Continue)

  override def postStop(): Unit = {
    continueTask.cancel()
  }

  def receive = {
    case _: Request | Continue =>
      query()
      deliverBuf()

    case Cancel =>
      context.stop(self)
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

  def query(): Unit =
    if (buf.isEmpty) {
      try {
        val result = Select.run(tag, currentOffset, Limit)
        currentOffset = if (result.nonEmpty) result.last._1 else currentOffset
        val serialization = SerializationExtension(context.system)

        buf = result.map {
          case (id, bytes) =>
            val p = serialization.deserialize(bytes, classOf[PersistentRepr]).get
            EventEnvelope(offset = Sequence(id), p.persistenceId, p.sequenceNr, p.payload)
        }
      } catch {
        case e: Exception =>
          onErrorThenStop(e)
      }
    }

  final def deliverBuf(): Unit =
    if (totalDemand > 0 && buf.nonEmpty) {
      if (totalDemand <= Int.MaxValue) {
        val (use, keep) = buf.splitAt(totalDemand.toInt)
        buf = keep
        use.foreach(onNext)
      } else {
        buf.foreach(onNext)
        buf = Vector.empty
      }
    }
}
//#events-by-tag-publisher
