/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.remote.RemoteActorRefProvider
import org.HdrHistogram.Histogram
import java.util.concurrent.TimeUnit.SECONDS

class TaskRunnerMetrics(system: ActorSystem) {

  private var entryOffset = 0

  def printHistograms(): Unit = {
    val aeronSourceHistogram = new Histogram(SECONDS.toNanos(10), 3)
    val aeronSinkHistogram = new Histogram(SECONDS.toNanos(10), 3)
    system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport match {
      case a: ArteryTransport ⇒
        a.afrFileChannel.foreach { afrFileChannel ⇒
          var c = 0
          var aeronSourceMaxBeforeDelegate = 0L
          var aeronSinkMaxBeforeDelegate = 0L
          val reader = new FlightRecorderReader(afrFileChannel)
          reader.structure.hiFreqLog.logs.foreach(_.compactEntries.foreach { entry ⇒
            c += 1
            if (c > entryOffset) {
              entry.code match {
                case FlightRecorderEvents.AeronSource_ReturnFromTaskRunner ⇒
                  aeronSourceHistogram.recordValue(entry.param)
                case FlightRecorderEvents.AeronSink_ReturnFromTaskRunner ⇒
                  aeronSinkHistogram.recordValue(entry.param)
                case FlightRecorderEvents.AeronSource_DelegateToTaskRunner ⇒
                  aeronSourceMaxBeforeDelegate = math.max(aeronSourceMaxBeforeDelegate, entry.param)
                case FlightRecorderEvents.AeronSink_DelegateToTaskRunner ⇒
                  aeronSinkMaxBeforeDelegate = math.max(aeronSinkMaxBeforeDelegate, entry.param)
                case _ ⇒
              }
            }
          })

          reader.close()
          entryOffset = c

          if (aeronSourceHistogram.getTotalCount > 0) {
            println(s"Histogram of AeronSource tasks in microseconds. Max count before delegate: $aeronSourceMaxBeforeDelegate")
            aeronSourceHistogram.outputPercentileDistribution(System.out, 1000.0)
          }

          if (aeronSinkHistogram.getTotalCount > 0) {
            println(s"Histogram of AeronSink tasks in microseconds. Max count before delegate: $aeronSinkMaxBeforeDelegate")
            aeronSinkHistogram.outputPercentileDistribution(System.out, 1000.0)
          }
        }
      case _ ⇒
    }
  }

}
