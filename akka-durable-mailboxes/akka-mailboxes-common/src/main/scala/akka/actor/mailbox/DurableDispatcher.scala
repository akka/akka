/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.actor.{ newUuid, ActorRef }
import akka.util.ReflectiveAccess
import akka.dispatch._
import akka.config._
import akka.event.EventHandler

import java.lang.reflect.InvocationTargetException

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
sealed abstract class DurableMailboxStorage(mailboxFQN: String) {
  val constructorSignature = Array[Class[_]](classOf[ActorRef])

  val mailboxClass: Class[_] = ReflectiveAccess.getClassFor(mailboxFQN, classOf[ActorRef].getClassLoader) match {
    case Right(clazz) ⇒ clazz
    case Left(exception) ⇒
      val cause = exception match {
        case i: InvocationTargetException ⇒ i.getTargetException
        case _                            ⇒ exception
      }
      throw new DurableMailboxException("Cannot find class [%s] due to: %s".format(mailboxFQN, cause.toString))
  }

  //TODO take into consideration a mailboxConfig parameter so one can have bounded mboxes and capacity etc
  def createFor(actor: ActorRef): AnyRef = {
    EventHandler.debug(this, "Creating durable mailbox [%s] for [%s]".format(mailboxClass.getName, actor))
    ReflectiveAccess.createInstance[AnyRef](mailboxClass, constructorSignature, Array[AnyRef](actor)) match {
      case Right(instance) ⇒ instance
      case Left(exception) ⇒
        val cause = exception match {
          case i: InvocationTargetException ⇒ i.getTargetException
          case _                            ⇒ exception
        }
        throw new DurableMailboxException("Cannot instantiate [%s] due to: %s".format(mailboxClass.getName, cause.toString))
    }
  }
}

case object RedisDurableMailboxStorage extends DurableMailboxStorage("akka.actor.mailbox.RedisBasedMailbox")
case object MongoNaiveDurableMailboxStorage extends DurableMailboxStorage("akka.actor.mailbox.MongoBasedNaiveMailbox")
case object BeanstalkDurableMailboxStorage extends DurableMailboxStorage("akka.actor.mailbox.BeanstalkBasedMailbox")
case object FileDurableMailboxStorage extends DurableMailboxStorage("akka.actor.mailbox.FileBasedMailbox")
case object ZooKeeperDurableMailboxStorage extends DurableMailboxStorage("akka.actor.mailbox.ZooKeeperBasedMailbox")

/**
 * The durable equivalent of Dispatcher
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
case class DurableDispatcher(
  _name: String,
  _storage: DurableMailboxStorage,
  _throughput: Int = Dispatchers.THROUGHPUT,
  _throughputDeadlineTime: Int = Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS,
  _mailboxType: MailboxType = Dispatchers.MAILBOX_TYPE,
  _config: ThreadPoolConfig = ThreadPoolConfig()) extends ExecutorBasedEventDrivenDispatcher(
  _name,
  _throughput,
  _throughputDeadlineTime,
  _mailboxType,
  _config) {

  def this(_name: String, _storage: DurableMailboxStorage, throughput: Int, throughputDeadlineTime: Int, mailboxType: MailboxType) =
    this(_name, _storage, throughput, throughputDeadlineTime, mailboxType, ThreadPoolConfig()) // Needed for Java API usage

  def this(_name: String, _storage: DurableMailboxStorage, throughput: Int, mailboxType: MailboxType) =
    this(_name, _storage, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, mailboxType) // Needed for Java API usage

  def this(_name: String, _storage: DurableMailboxStorage, throughput: Int) =
    this(_name, _storage, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  def this(_name: String, _storage: DurableMailboxStorage, _config: ThreadPoolConfig) =
    this(_name, _storage, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE, _config)

  def this(_name: String, _storage: DurableMailboxStorage) =
    this(_name, _storage, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  override def register(actorRef: ActorRef) {
    super.register(actorRef)
    val mbox = actorRef.mailbox.asInstanceOf[MessageQueue with ExecutableMailbox]
    if (mbox ne null) //Schedule the ActorRef for initial execution, because we might be resuming operations after a failure
      super.registerForExecution(mbox)
  }

  override def createMailbox(actorRef: ActorRef): AnyRef = _storage.createFor(actorRef)

  protected[akka] override def dispatch(invocation: MessageInvocation): Unit = {
    if (invocation.channel.isInstanceOf[ActorCompletableFuture])
      throw new IllegalArgumentException("Durable mailboxes do not support Future-based messages from ?")
    super.dispatch(invocation)
  }
}

/**
 * The durable equivalent of ThreadBasedDispatcher
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
case class DurableThreadBasedDispatcher(
  _actor: ActorRef,
  _storage: DurableMailboxStorage,
  _mailboxType: MailboxType) extends ThreadBasedDispatcher(_actor, _mailboxType) {

  def this(actor: ActorRef, _storage: DurableMailboxStorage) =
    this(actor, _storage, UnboundedMailbox()) // For Java API

  def this(actor: ActorRef, _storage: DurableMailboxStorage, capacity: Int) =
    this(actor, _storage, BoundedMailbox(capacity)) //For Java API

  def this(actor: ActorRef, _storage: DurableMailboxStorage, capacity: Int, pushTimeOut: akka.util.Duration) = //For Java API
    this(actor, _storage, BoundedMailbox(capacity, pushTimeOut))

  override def register(actorRef: ActorRef) {
    super.register(actorRef)
    val mbox = actorRef.mailbox.asInstanceOf[MessageQueue with ExecutableMailbox]
    if (mbox ne null) //Schedule the ActorRef for initial execution, because we might be resuming operations after a failure
      super.registerForExecution(mbox)
  }

  override def createMailbox(actorRef: ActorRef): AnyRef = _storage.createFor(actorRef)

  protected[akka] override def dispatch(invocation: MessageInvocation): Unit = {
    if (invocation.channel.isInstanceOf[ActorCompletableFuture])
      throw new IllegalArgumentException("Actor has a durable mailbox that does not support ?")
    super.dispatch(invocation)
  }
}

/**
 * Configurator for the DurableDispatcher
 * Do not forget to specify the "storage", valid values are "redis", "beanstalkd", "zookeeper", "mongodb" and "file"
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class DurableDispatcherConfigurator extends MessageDispatcherConfigurator {
  def configure(config: Configuration): MessageDispatcher = {
    configureThreadPool(config, threadPoolConfig ⇒ new DurableDispatcher(
      config.getString("name", newUuid.toString),
      getStorage(config),
      config.getInt("throughput", Dispatchers.THROUGHPUT),
      config.getInt("throughput-deadline-time", Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS),
      mailboxType(config),
      threadPoolConfig)).build
  }

  def getStorage(config: Configuration): DurableMailboxStorage = {
    val storage = config.getString("storage") map {
      case "redis"     ⇒ RedisDurableMailboxStorage
      case "mongodb"   ⇒ MongoNaiveDurableMailboxStorage
      case "beanstalk" ⇒ BeanstalkDurableMailboxStorage
      case "zookeeper" ⇒ ZooKeeperDurableMailboxStorage
      case "file"      ⇒ FileDurableMailboxStorage
      case unknown     ⇒ throw new IllegalArgumentException("[%s] is not a valid storage, valid options are [redis, beanstalk, zookeeper, file]" format unknown)
    }

    storage.getOrElse(throw new DurableMailboxException("No 'storage' defined for DurableDispatcherConfigurator"))
  }
}
