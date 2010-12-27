/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor.dispatch

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.junit.JUnitSuite
import org.junit.Test

import net.lag.configgy.Config
import scala.reflect.{Manifest}
import akka.dispatch._

object DispatchersSpec {
  import Dispatchers._
  //
  val tipe               = "type"
  val keepalivems        = "keep-alive-time"
  val corepoolsizefactor = "core-pool-size-factor"
  val maxpoolsizefactor  = "max-pool-size-factor"
  val executorbounds     = "executor-bounds"
  val allowcoretimeout   = "allow-core-timeout"
  val rejectionpolicy    = "rejection-policy"   // abort, caller-runs, discard-oldest, discard
  val throughput         = "throughput"         // Throughput for ExecutorBasedEventDrivenDispatcher
  val aggregate          = "aggregate"          // Aggregate on/off for HawtDispatchers

  def instance(dispatcher: MessageDispatcher): (MessageDispatcher) => Boolean = _ == dispatcher
  def ofType[T <: MessageDispatcher : Manifest]: (MessageDispatcher) => Boolean = _.getClass == manifest[T].erasure

  def typesAndValidators: Map[String,(MessageDispatcher) => Boolean] = Map(
    "ExecutorBasedEventDrivenWorkStealing"      -> ofType[ExecutorBasedEventDrivenWorkStealingDispatcher],
    "ExecutorBasedEventDriven"                  -> ofType[ExecutorBasedEventDrivenDispatcher],
    "Hawt"                                      -> ofType[HawtDispatcher],
    "GlobalExecutorBasedEventDriven"            -> instance(globalExecutorBasedEventDrivenDispatcher),
    "GlobalHawt"                                -> instance(globalHawtDispatcher)
  )

  def validTypes = typesAndValidators.keys.toList

  lazy val allDispatchers: Map[String,Option[MessageDispatcher]] = {
    validTypes.map(t => (t,from(Config.fromMap(Map(tipe -> t))))).toMap
  }
}

class DispatchersSpec extends JUnitSuite {

  import Dispatchers._
  import DispatchersSpec._

  @Test def shouldYieldNoneIfTypeIsMissing {
    assert(from(Config.fromMap(Map())) === None)
  }

  @Test(expected = classOf[IllegalArgumentException])
  def shouldThrowIllegalArgumentExceptionIfTypeDoesntExist {
    from(Config.fromMap(Map(tipe -> "typedoesntexist")))
  }

  @Test def shouldGetTheCorrectTypesOfDispatchers {
    //It can create/obtain all defined types
    assert(allDispatchers.values.forall(_.isDefined))
    //All created/obtained dispatchers are of the expeced type/instance
    assert(typesAndValidators.forall( tuple => tuple._2(allDispatchers(tuple._1).get) ))
  }

  @Test def defaultingToDefaultWhileLoadingTheDefaultShouldWork {
    assert(from(Config.fromMap(Map())).getOrElse(defaultGlobalDispatcher) == defaultGlobalDispatcher)
  }

}
