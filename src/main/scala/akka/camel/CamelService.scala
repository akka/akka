/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.camel


import component.Path
import org.apache.camel.CamelContext
import akka.japi.{Option => JOption}

import migration.Migration._
import TypedCamelAccess._
import akka.actor.{ActorRef, Props, Actor, ActorSystem}

/**
 * Publishes consumer actors at their Camel endpoints. Consumer actors are published asynchronously when
 * they are started and un-published asynchronously when they are stopped. The CamelService is notified
 * about actor life cycle by registering listeners at Actor.registry.
 * <p>
 * If the akka-camel-typed jar is on the classpath, it is automatically detected by the CamelService. The
 * optional akka-camel-typed jar provides support for typed consumer actors.
 *
 * @author Martin Krasser
 */
trait CamelService extends Bootable with ConsumerRegistry{

  val actorSystem  = ActorSystem("Camel")
  private[camel] val consumerPublisher = actorSystem.actorOf(Props[ConsumerPublisher])

  private val serviceEnabled = config.isModuleEnabled("camel")

  /**
   * Starts this CamelService if the <code>akka.enabled-modules</code> list contains <code>"camel"</code>.
   */
  abstract override def onLoad = {
    super.onLoad
    if (serviceEnabled) start
  }

  /**
   * Stops this CamelService if the <code>akka.enabled-modules</code> list contains <code>"camel"</code>.
   */
  abstract override def onUnload = {
    if (serviceEnabled) stop
    super.onUnload
  }

  @deprecated("use start() instead", "1.1")
  def load = start

  @deprecated("use stop() instead", "1.1")
  def unload = stop

  /**
   * Starts this CamelService.
   */
  def start: CamelService = {
    // Only init and start if not already done by application
    if (!CamelContextManager.initialized) CamelContextManager.init
    if (!CamelContextManager.started) CamelContextManager.start

    for (tc <- TypedCamelModule.typedCamelObject) tc.onCamelServiceStart(this)

    // Register this instance as current CamelService and return it
    CamelServiceManager.register(this)
    CamelServiceManager.mandatoryService
  }

  /**
   * Stops this CamelService.
   */
  def stop = {
    // Unregister this instance as current CamelService
    CamelServiceManager.unregister(this)

    for (tc <- TypedCamelModule.typedCamelObject) tc.onCamelServiceStop(this)

    // Stop related services
    actorSystem.shutdown()
    CamelContextManager.stop
  }
}

/**
 * Manages consumer registration. Consumers call registerConsumer method to register themselves  when they get created.
 * ActorEndpoint uses it to lookup an actor by its path.
 */
trait ConsumerRegistry{ self:CamelService =>
  //TODO: save some kittens and use less blocking collection
  val consumers = synchronized(scala.collection.mutable.HashMap[Path, ActorRef]())

  def registerConsumer(route: String, consumer: Consumer with Actor) = {
    consumers.put(Path(consumer.self.path.toString), consumer.self)
    consumerPublisher ! ConsumerActorRegistered(route, consumer.self, consumer)
  }

  def findConsumer(path: Path) : Option[ActorRef] = consumers.get(path)
}



/**
 * Manages a CamelService (the 'current' CamelService).
 *
 * @author Martin Krasser
 */
object CamelServiceManager {
  def findConsumer(path: Path) = mandatoryService.findConsumer(path)


  /**
   * The current CamelService which is defined when a CamelService has been started.
   */
  private var _current: Option[CamelService] = None

  /**
   * Starts a new CamelService, makes it the current CamelService and returns it.
   *
   * @see CamelService#start
   * @see CamelService#onLoad
   */
  def startCamelService = CamelServiceFactory.createCamelService.start

  /**
   * Stops the current CamelService (if defined).
   *
   * @see CamelService#stop
   * @see CamelService#onUnload
   */
  def stopCamelService = for (s <- service) s.stop

  /**
   * Returns <code>Some(CamelService)</code> if this <code>CamelService</code>
   * has been started, <code>None</code> otherwise.
   */
  def service = _current

  /**
   * Returns the current <code>CamelService</code> if <code>CamelService</code>
   * has been started, otherwise throws an <code>IllegalStateException</code>.
   * <p>
   * Java API
   */
  def getService: JOption[CamelService] = CamelServiceManager.service

  /**
   * Returns <code>Some(CamelService)</code> (containing the current CamelService)
   * if this <code>CamelService</code>has been started, <code>None</code> otherwise.
   */
  def mandatoryService =
    if (_current.isDefined) _current.get
    else throw new IllegalStateException("No current CamelService")

  /**
   * Returns <code>Some(CamelService)</code> (containing the current CamelService)
   * if this <code>CamelService</code>has been started, <code>None</code> otherwise.
   * <p>
   * Java API
   */
  def getMandatoryService = mandatoryService

  private[camel] def register(service: CamelService) =
    if (_current.isDefined) throw new IllegalStateException("current CamelService already registered")
    else _current = Some(service)

  private[camel] def unregister(service: CamelService) =
    if (_current == Some(service)) _current = None
    else throw new IllegalStateException("only current CamelService can be unregistered")
}

/**
 * @author Martin Krasser
 */
object CamelServiceFactory {
  /**
   * Creates a new CamelService instance.
   */
  def createCamelService: CamelService = new CamelService { }

  /**
   * Creates a new CamelService instance and initializes it with the given CamelContext.
   */
  def createCamelService(camelContext: CamelContext): CamelService = {
    CamelContextManager.init(camelContext)
    createCamelService
  }
}
