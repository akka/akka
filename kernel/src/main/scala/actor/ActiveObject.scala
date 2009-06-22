/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.actor

import java.util.{List => JList, ArrayList}
import java.lang.reflect.{Method, Field}
import java.lang.annotation.Annotation

import kernel.config.ActiveObjectGuiceConfigurator
import kernel.config.ScalaConfig._
import kernel.camel.{MessageDriven, ActiveObjectProducer}
import kernel.util.Helpers.ReadWriteLock
import kernel.util.{HashCode, ResultOrFailure}
import kernel.state.{Transactional, TransactionalMap, TransactionalRef, TransactionalVector}
import kernel.stm.Transaction

import org.codehaus.aspectwerkz.intercept.{Advisable, AroundAdvice}
import org.codehaus.aspectwerkz.joinpoint.{MethodRtti, JoinPoint}
import org.codehaus.aspectwerkz.proxy.Proxy

import org.apache.camel.{Processor, Exchange}

import scala.collection.mutable.HashMap

sealed class ActiveObjectException(msg: String) extends RuntimeException(msg)
class ActiveObjectInvocationTimeoutException(msg: String) extends ActiveObjectException(msg)

object Annotations {
  import se.scalablesolutions.akka.annotation._
  val transactional = classOf[transactional]
  val oneway =        classOf[oneway]
  val immutable =     classOf[immutable]
  val state =         classOf[state]
}

/**
 * Factory for Java API.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectFactory {
  def newInstance[T](target: Class[T], actor: Actor): T = {
    ActiveObject.newInstance(target, actor)
  }

  def newInstance[T](intf: Class[T], target: AnyRef, actor: Actor): T = {
    ActiveObject.newInstance(intf, target, actor)
  }

  def supervise(restartStrategy: RestartStrategy, components: List[Worker]): Supervisor =
    ActiveObject.supervise(restartStrategy, components)
}

/**
 * Factory for Scala API.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ActiveObject {
  val AKKA_CAMEL_ROUTING_SCHEME = "akka"

  private[kernel] val threadBoundTx: ThreadLocal[Option[Transaction]] = {
    val tl = new ThreadLocal[Option[Transaction]]
    tl.set(None)
    tl
  }

  def newInstance[T](target: Class[T], actor: Actor): T = {
    val proxy = Proxy.newInstance(target, false, true)
    // FIXME switch to weaving in the aspect at compile time
    proxy.asInstanceOf[Advisable].aw_addAdvice("execution(* *.*(..))", new SequentialTransactionalAroundAdvice(target, proxy, actor))
    proxy.asInstanceOf[T]
  }

  def newInstance[T](intf: Class[T], target: AnyRef, actor: Actor): T = {
    val proxy = Proxy.newInstance(Array(intf), Array(target), false, true)
    proxy.asInstanceOf[Advisable].aw_addAdvice("execution(* *.*(..))", new SequentialTransactionalAroundAdvice(intf, target, actor))
    proxy.asInstanceOf[T]
  }

  def supervise(restartStrategy: RestartStrategy, components: List[Worker]): Supervisor = {
    object factory extends SupervisorFactory {
      override def getSupervisorConfig = SupervisorConfig(restartStrategy, components)
    }
    val supervisor = factory.newSupervisor
    supervisor ! StartSupervisor
    supervisor
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */

// FIXME: STM that allows concurrent updates, detects collision, rolls back and restarts
sealed class SequentialTransactionalAroundAdvice(target: Class[_], targetInstance: AnyRef, actor: Actor) extends AroundAdvice {
  private val changeSet = new ChangeSet(target.getName)
  
  private val (maps, vectors, refs) = getTransactionalItemsFor(targetInstance)
  changeSet.refs = refs
  changeSet.maps = maps
  changeSet.vectors = vectors

  import kernel.reactor._
  private[this] var dispatcher = new ProxyMessageDispatcher
  private[this] var mailbox = dispatcher.messageQueue
  dispatcher.start

  import ActiveObject.threadBoundTx
  private[this] var activeTx: Option[Transaction] = None

  // FIXME: switch to using PCD annotation matching, break out into its own aspect + switch to StaticJoinPoint
  def invoke(joinpoint: JoinPoint): AnyRef = {
    val rtti = joinpoint.getRtti.asInstanceOf[MethodRtti]
    val method = rtti.getMethod

    tryToCommitTransaction

    if (isInExistingTransaction) {
      joinExistingTransaction
    } else {
      if (method.isAnnotationPresent(Annotations.transactional)) startNewTransaction
    }

    val result: AnyRef = try {
      incrementTransaction
//      if (rtti.getMethod.isAnnotationPresent(Annotations.oneway)) sendOneWay(joinpoint) // FIXME put in 2 different aspects
//      else handleResult(sendAndReceiveEventually(joinpoint))
      val result = actor !! Invocation(joinpoint, activeTx)
      val resultOrFailure =
        if (result.isDefined) result.get.asInstanceOf[ResultOrFailure[AnyRef]]
        else throw new ActiveObjectInvocationTimeoutException("TIMED OUT")
      handleResult(resultOrFailure)
    } finally {
      decrementTransaction
      if (isTransactionAborted) removeTransactionIfTopLevel
      else tryToPrecommitTransaction
    }
    result
  }

  private def startNewTransaction = {
    val newTx = new Transaction
    newTx.begin(changeSet)
    val tx = Some(newTx)
    activeTx = tx
    threadBoundTx.set(tx)
  }

  private def joinExistingTransaction = {
    val cflowTx = threadBoundTx.get
    if (!activeTx.isDefined && cflowTx.isDefined) {
      val currentTx = cflowTx.get
      currentTx.join(changeSet)
      activeTx = Some(currentTx)
    }
  }

  private def tryToPrecommitTransaction = if (activeTx.isDefined) activeTx.get.precommit(changeSet)

  private def tryToCommitTransaction: Boolean = if (activeTx.isDefined) {
    val tx = activeTx.get
    tx.commit(changeSet)
    removeTransactionIfTopLevel
    true
  } else false
                                                               
  private def handleResult(result: ResultOrFailure[AnyRef]): AnyRef = {
    try {
      result()
    } catch {
      case e =>
        rollback(result.tx)
        throw e
    }
  }

  private def rollback(tx: Option[Transaction]) = tx match {
    case None => {} // no tx; nothing to do
    case Some(tx) =>
      tx.rollback(changeSet)
  }

  private def isInExistingTransaction = ActiveObject.threadBoundTx.get.isDefined

  private def isTransactionAborted = activeTx.isDefined && activeTx.get.isAborted

  private def incrementTransaction =  if (activeTx.isDefined) activeTx.get.increment

  private def decrementTransaction =  if (activeTx.isDefined) activeTx.get.decrement

  private def removeTransactionIfTopLevel =
    if (activeTx.isDefined && activeTx.get.topLevel_?) {
      activeTx = None
      threadBoundTx.set(None)
    }

  private def reenteringExistingTransaction= if (activeTx.isDefined) {
    val cflowTx = threadBoundTx.get
    if (cflowTx.isDefined && cflowTx.get.id == activeTx.get.id) false
    else true
  } else true

  private def sendOneWay(joinpoint: JoinPoint) =
    mailbox.append(new MessageHandle(this, Invocation(joinpoint, activeTx), new NullFutureResult))

  private def sendAndReceiveEventually(joinpoint: JoinPoint): ResultOrFailure[AnyRef] = {
    val future = postMessageToMailboxAndCreateFutureResultWithTimeout(Invocation(joinpoint, activeTx), 1000) // FIXME configure
    future.await_?
    getResultOrThrowException(future)
  }

  private def postMessageToMailboxAndCreateFutureResultWithTimeout(message: AnyRef, timeout: Long): CompletableFutureResult = {
    val future = new DefaultCompletableFutureResult(timeout)
    mailbox.append(new MessageHandle(this, message, future))
    future
  }

  private def getResultOrThrowException[T](future: FutureResult): ResultOrFailure[AnyRef] =
    if (future.exception.isDefined) {
      var resultOrFailure = ResultOrFailure(activeTx)
      val (toBlame, cause) = future.exception.get
      resultOrFailure() = throw cause
      resultOrFailure
    } else ResultOrFailure(future.result.get, activeTx)

  /**
   * Search for transactional items for a specific target instance, crawl the class hierarchy recursively up to the top.
   */
  private def getTransactionalItemsFor(targetInstance: AnyRef):
    Tuple3[List[TransactionalMap[_, _]], List[TransactionalVector[_]], List[TransactionalRef[_]]] = {
    require(targetInstance != null)
    var maps:    List[TransactionalMap[_, _]] = Nil
    var refs:    List[TransactionalRef[_]] = Nil
    var vectors: List[TransactionalVector[_]] = Nil

    def getTransactionalItemsFor(target: Class[_]):
      Tuple3[List[TransactionalMap[_, _]], List[TransactionalVector[_]], List[TransactionalRef[_]]] = {
      target.getDeclaredFields.toArray.toList.asInstanceOf[List[Field]].foreach(println)
    for {
        field <- target.getDeclaredFields.toArray.toList.asInstanceOf[List[Field]]
        fieldType = field.getType
        if (fieldType == classOf[TransactionalMap[_, _]]) ||
           (fieldType == classOf[TransactionalVector[_]]) ||
           (fieldType == classOf[TransactionalRef[_]])
        txItem = {
          field.setAccessible(true)
          field.get(targetInstance)
        }
        if txItem != null
      } {
        if (txItem.isInstanceOf[TransactionalMap[_, _]])      maps    ::= txItem.asInstanceOf[TransactionalMap[_, _]]
        else if (txItem.isInstanceOf[TransactionalRef[_]])    refs    ::= txItem.asInstanceOf[TransactionalRef[_]]
        else if (txItem.isInstanceOf[TransactionalVector[_]]) vectors ::= txItem.asInstanceOf[TransactionalVector[_]]
      }
      val parent = target.getSuperclass
      if (parent == classOf[Object]) (maps, vectors, refs)
      else getTransactionalItemsFor(parent)
    }

    // start the search for transactional items, crawl the class hierarchy up until we reach Object
    getTransactionalItemsFor(targetInstance.getClass)
  }
}

class ChangeSet(val id: String) {
  private val lock = new ReadWriteLock
  
  private[kernel] def full: List[Transactional] = lock.withReadLock {
    _maps ::: _vectors ::: _refs
  }

  // TX Maps
  private[kernel] var _maps: List[TransactionalMap[_, _]] = Nil
  private[kernel] def maps_=(maps: List[TransactionalMap[_, _]]) = lock.withWriteLock {
    _maps = maps
  }
  private[kernel] def maps: List[TransactionalMap[_, _]] = lock.withReadLock {
    _maps
  }

  // TX Vectors
  private[kernel] var _vectors: List[TransactionalVector[_]] = Nil
  private[kernel] def vectors_=(vectors: List[TransactionalVector[_]]) = lock.withWriteLock {
    _vectors = vectors
  }
  private[kernel] def vectors: List[TransactionalVector[_]] = lock.withReadLock {
    _vectors
  }

  // TX Refs
  private[kernel] var _refs: List[TransactionalRef[_]] = Nil
  private[kernel] def refs_=(refs: List[TransactionalRef[_]]) = lock.withWriteLock {
    _refs = refs
  }
  private[kernel] def refs: List[TransactionalRef[_]] = lock.withReadLock {
    _refs
  }  
}

/**
 * Represents a snapshot of the current invocation.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable private[kernel] case class Invocation(val joinpoint: JoinPoint, val transaction: Option[Transaction]) {

  override def toString: String = synchronized {
    "Invocation [joinpoint: " + joinpoint.toString+ " | transaction: " + transaction.toString + "]"
  }

  override def hashCode(): Int = synchronized {
    var result = HashCode.SEED
    result = HashCode.hash(result, joinpoint)
    if (transaction.isDefined) result = HashCode.hash(result, transaction.get)
    result
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[Invocation] &&
    that.asInstanceOf[Invocation].joinpoint == joinpoint &&
    that.asInstanceOf[Invocation].transaction.getOrElse(false) == transaction.getOrElse(false)
  }
}

/**
 * Generic Actor managing Invocation dispatch, transaction and error management.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[kernel] class Dispatcher(val targetName: String) extends Actor {
  id = targetName
  override def receive: PartialFunction[Any, Unit] = {

    case Invocation(joinpoint: JoinPoint, tx: Option[Transaction]) =>
      ActiveObject.threadBoundTx.set(tx)
      try {
        reply(ResultOrFailure(joinpoint.proceed, tx))
      } catch {
        case e =>
          val resultOrFailure = ResultOrFailure(tx)
          resultOrFailure() = throw e
          reply(resultOrFailure)
      }

    case unexpected =>
      throw new ActiveObjectException("Unexpected message [" + unexpected + "] sent to [" + this + "]")
  }
}

/*
ublic class CamelInvocationHandler implements InvocationHandler {
    private final Endpoint endpoint;
    private final Producer producer;
    private final MethodInfoCache methodInfoCache;

    public CamelInvocationHandler(Endpoint endpoint, Producer producer, MethodInfoCache methodInfoCache) {
        this.endpoint = endpoint;
        this.producer = producer;
        this.methodInfoCache = methodInfoCache;
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        BeanInvocation invocation = new BeanInvocation(method, args);
        ExchangePattern pattern = ExchangePattern.InOut;
        MethodInfo methodInfo = methodInfoCache.getMethodInfo(method);
        if (methodInfo != null) {
            pattern = methodInfo.getPattern();
        }
        Exchange exchange = new DefaultExchange(endpoint, pattern);
        exchange.getIn().setBody(invocation);

        producer.process(exchange);
        Throwable fault = exchange.getException();
        if (fault != null) {
            throw new InvocationTargetException(fault);
        }
        if (pattern.isOutCapable()) {
            return exchange.getOut().getBody();
        } else {
            return null;
        }
    }
}

      if (joinpoint.target.isInstanceOf[MessageDriven] &&
          joinpoint.method.getName == "onMessage") {
        val m = joinpoint.method

      val endpointName = m.getDeclaringClass.getName + "." + m.getName
        val activeObjectName = m.getDeclaringClass.getName
        val endpoint = conf.getRoutingEndpoint(conf.lookupUriFor(m))
        val producer = endpoint.createProducer
        val exchange = endpoint.createExchange
        exchange.getIn().setBody(joinpoint)
        producer.process(exchange)
        val fault = exchange.getException();
        if (fault != null) throw new InvocationTargetException(fault)

        // FIXME: need some timeout and future here...
        exchange.getOut.getBody

      } else
*/
