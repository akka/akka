/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence

import java.util.concurrent.atomic.AtomicInteger
import scala.reflect.ClassTag
import akka.actor._
import akka.testkit._
import com.typesafe.config._
import org.scalatest._
import java.util.UUID

abstract class PluginSpec(val config: Config) extends TestKitBase with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {
  private val counter = new AtomicInteger(0)

  private var _extension: Persistence = _
  private var _pid: String = _
  private var _writerUuid: String = _

  // used to avoid messages be delivered to a restarted actor,
  // this is akka-persistence internals and journals themselves don't really care
  protected val actorInstanceId = 1

  override protected def beforeEach(): Unit = {
    _pid = s"p-${counter.incrementAndGet()}"
    _writerUuid = UUID.randomUUID.toString
  }

  override protected def beforeAll(): Unit =
    _extension = Persistence(system)

  override protected def afterAll(): Unit =
    shutdown(system)

  def extension: Persistence = _extension

  def pid: String = _pid

  def writerUuid: String = _writerUuid

  def subscribe[T: ClassTag](subscriber: ActorRef) =
    system.eventStream.subscribe(subscriber, implicitly[ClassTag[T]].runtimeClass)
}
