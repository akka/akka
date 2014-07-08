package akka.persistence

import java.util.concurrent.atomic.AtomicInteger

import scala.reflect.ClassTag

import akka.actor._
import akka.testkit._

import com.typesafe.config._

import org.scalatest._

trait PluginSpec extends TestKitBase with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {
  private val counter = new AtomicInteger(0)

  private var _extension: Persistence = _
  private var _pid: String = _

  // used to avoid messages be delivered to a restarted actor,
  // this is akka-persistence internals and journals themselfes don't really care
  protected val actorInstanceId = 1

  override protected def beforeEach(): Unit = {
    _pid = s"p-${counter.incrementAndGet()}"
  }
  override protected def beforeAll(): Unit =
    _extension = Persistence(system)

  override protected def afterAll(): Unit =
    shutdown(system)

  val config: Config

  def extension: Persistence =
    _extension

  def pid: String =
    _pid

  def subscribe[T: ClassTag](subscriber: ActorRef) =
    system.eventStream.subscribe(subscriber, implicitly[ClassTag[T]].runtimeClass)
}
