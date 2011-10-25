/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.dispatch

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.reflect.{ Manifest }
import akka.dispatch._
import akka.testkit.AkkaSpec
import akka.config.Configuration

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DispatchersSpec extends AkkaSpec {

  import app.dispatcherFactory._

  val tipe = "type"
  val keepalivems = "keep-alive-time"
  val corepoolsizefactor = "core-pool-size-factor"
  val maxpoolsizefactor = "max-pool-size-factor"
  val executorbounds = "executor-bounds"
  val allowcoretimeout = "allow-core-timeout"
  val rejectionpolicy = "rejection-policy" // abort, caller-runs, discard-oldest, discard
  val throughput = "throughput" // Throughput for Dispatcher

  def instance(dispatcher: MessageDispatcher): (MessageDispatcher) ⇒ Boolean = _ == dispatcher
  def ofType[T <: MessageDispatcher: Manifest]: (MessageDispatcher) ⇒ Boolean = _.getClass == manifest[T].erasure

  def typesAndValidators: Map[String, (MessageDispatcher) ⇒ Boolean] = Map(
    "BalancingDispatcher" -> ofType[BalancingDispatcher],
    "Dispatcher" -> ofType[Dispatcher])

  def validTypes = typesAndValidators.keys.toList

  lazy val allDispatchers: Map[String, Option[MessageDispatcher]] = {
    validTypes.map(t ⇒ (t, from(Configuration.fromMap(Map(tipe -> t))))).toMap
  }

  "Dispatchers" must {

    "yield None if type is missing" in {
      assert(from(Configuration.fromMap(Map())) === None)
    }

    "throw IllegalArgumentException if type does not exist" in {
      intercept[IllegalArgumentException] {
        from(Configuration.fromMap(Map(tipe -> "typedoesntexist")))
      }
    }

    "get the correct types of dispatchers" in {
      //It can create/obtain all defined types
      assert(allDispatchers.values.forall(_.isDefined))
      //All created/obtained dispatchers are of the expeced type/instance
      assert(typesAndValidators.forall(tuple ⇒ tuple._2(allDispatchers(tuple._1).get)))
    }

    "default to default while loading the default" in {
      assert(from(Configuration.fromMap(Map())).getOrElse(defaultGlobalDispatcher) == defaultGlobalDispatcher)
    }
  }

}
