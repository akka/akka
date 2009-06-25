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
import kernel.nio.{RemoteRequest, NettyClient}
import kernel.stm.{ChangeSet, Transaction}
import kernel.util.Helpers.ReadWriteLock
import kernel.util.{HashCode, ResultOrFailure}
import kernel.state.{Transactional, TransactionalMap, TransactionalRef, TransactionalVector}

import org.codehaus.aspectwerkz.intercept.{Advisable, AroundAdvice}
import org.codehaus.aspectwerkz.joinpoint.{MethodRtti, JoinPoint}
import org.codehaus.aspectwerkz.proxy.Proxy

import org.apache.camel.{Processor, Exchange}

import scala.collection.mutable.HashMap

sealed class ActiveObjectException(msg: String) extends RuntimeException(msg)
class ActiveObjectInvocationTimeoutException(msg: String) extends ActiveObjectException(msg)
class TransactionAwareException(val cause: Throwable, val tx: Option[Transaction]) extends RuntimeException(cause)

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
  def newInstance[T](target: Class[T]): T = ActiveObject.newInstance(target, new Dispatcher(target.getName), false)

  def newInstance[T](intf: Class[T], target: AnyRef): T = ActiveObject.newInstance(intf, target, new Dispatcher(intf.getName), false)

  def newRemoteInstance[T](target: Class[T]): T = ActiveObject.newInstance(target, new Dispatcher(target.getName), true)

  def newRemoteInstance[T](intf: Class[T], target: AnyRef): T = ActiveObject.newInstance(intf, target, new Dispatcher(intf.getName), true)

  /*
  def newInstanceAndLink[T](target: Class[T], supervisor: AnyRef): T = {
    val actor = new Dispatcher(target.getName)
    ActiveObject.newInstance(target, actor)
  }

  def newInstanceAndLink[T](intf: Class[T], target: AnyRef, supervisor: AnyRef): T = {
    val actor = new Dispatcher(target.getName)
    ActiveObject.newInstance(intf, target, actor)
  }
  */
  // ================================================

  private[kernel] def newInstance[T](target: Class[T], actor: Actor, remote: Boolean): T = {
    ActiveObject.newInstance(target, actor, remote)
  }

  private[kernel] def newInstance[T](intf: Class[T], target: AnyRef, actor: Actor, remote: Boolean): T = {
    ActiveObject.newInstance(intf, target, actor, remote)
  }

  private[kernel] def supervise(restartStrategy: RestartStrategy, components: List[Worker]): Supervisor =
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

  def newInstance[T](target: Class[T]): T = newInstance(target, new Dispatcher(target.getName), false)

  def newInstance[T](intf: Class[T], target: AnyRef): T = newInstance(intf, target, new Dispatcher(intf.getName), false)

  def newRemoteInstance[T](target: Class[T]): T = newInstance(target, new Dispatcher(target.getName), true)

  def newRemoteInstance[T](intf: Class[T], target: AnyRef): T = newInstance(intf, target, new Dispatcher(intf.getName), true)

  // ================================================

  private[kernel] def newInstance[T](target: Class[T], actor: Actor, remote: Boolean): T = {
    if (remote) NettyClient.connect
    val proxy = Proxy.newInstance(target, false, true)
    // FIXME switch to weaving in the aspect at compile time
    proxy.asInstanceOf[Advisable].aw_addAdvice(
      "execution(* *.*(..))", new SequentialTransactionalAroundAdvice(target, proxy, actor, remote))
    proxy.asInstanceOf[T]
  }

  private[kernel] def newInstance[T](intf: Class[T], target: AnyRef, actor: Actor, remote: Boolean): T = {
    if (remote) NettyClient.connect
    val proxy = Proxy.newInstance(Array(intf), Array(target), false, true)
    proxy.asInstanceOf[Advisable].aw_addAdvice(
      "execution(* *.*(..))", new SequentialTransactionalAroundAdvice(intf, target, actor, remote))
    proxy.asInstanceOf[T]
  }

  private[kernel] def supervise(restartStrategy: RestartStrategy, components: List[Worker]): Supervisor = {
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
@serializable sealed class SequentialTransactionalAroundAdvice(
    target: Class[_], targetInstance: AnyRef, actor: Actor, val remote: Boolean) extends AroundAdvice {
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

    val isOneWay = rtti.getMethod.getReturnType == java.lang.Void.TYPE
    // FIXME join TX with same id, do not COMMIT
    tryToCommitTransaction
    if (isInExistingTransaction) {
      joinExistingTransaction
    } else {
      if (method.isAnnotationPresent(Annotations.transactional)) startNewTransaction
    }
    try {
      incrementTransaction
      if (remote) {
        val future = NettyClient.send(
          new RemoteRequest(false, rtti.getParameterValues, rtti.getMethod.getName, target.getName, isOneWay, false))
        if (isOneWay) null // for void methods
        else {
          future.await_?
          val result = getResultOrThrowException(future)
          if (result.isDefined) result.get
          else throw new IllegalStateException("No result defined for invocation [" + joinpoint + "]")
        }
      } else {
        if (isOneWay) actor !! Invocation(joinpoint, activeTx) // FIXME investigate why ! causes TX to race
        else {
          val result = actor !! Invocation(joinpoint, activeTx)
          if (result.isDefined) result.get
          else throw new IllegalStateException("No result defined for invocation [" + joinpoint + "]")
        }
      }
    } catch {
      case e: TransactionAwareException =>
        rollback(e.tx)
        throw e.cause
    } finally {
      decrementTransaction
      if (isTransactionAborted) removeTransactionIfTopLevel
      else tryToPrecommitTransaction
    }
  }

  // TODO: create a method setCallee/setCaller to the joinpoint interface and compiler
  private def nullOutTransientFieldsInJoinpoint(joinpoint: JoinPoint) = {
    val clazz = joinpoint.getClass
    val callee = clazz.getDeclaredField("CALLEE")
    callee.setAccessible(true)
    callee.set(joinpoint, null)
    val caller = clazz.getDeclaredField("CALLER")
    caller.setAccessible(true)
    caller.set(joinpoint, null)
    val interceptors = clazz.getDeclaredField("AROUND_INTERCEPTORS")
    interceptors.setAccessible(true)
    interceptors.set(joinpoint, null)
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

  /*
  private def sendOneWay(joinpoint: JoinPoint) =
    mailbox.append(new MessageHandle(this, Invocation(joinpoint, activeTx), new NullFutureResult))

  private def sendAndReceiveEventually(joinpoint: JoinPoint): ResultOrFailure[AnyRef] = {
    val future = postMessageToMailboxAndCreateFutureResultWithTimeout(Invocation(joinpoint, activeTx), 1000) // FIXME configure
    future.await_?
    getResultOrThrowException(future)
  }
  */
  
  private def postMessageToMailboxAndCreateFutureResultWithTimeout(message: AnyRef, timeout: Long): CompletableFutureResult = {
    val future = new DefaultCompletableFutureResult(timeout)
    mailbox.append(new MessageHandle(this, message, future))
    future
  }

  private def getResultOrThrowException[T](future: FutureResult): Option[T] =
    if (future.exception.isDefined) {
      val (_, cause) = future.exception.get
      throw new TransactionAwareException(cause, activeTx)
    } else future.result.asInstanceOf[Option[T]]
/*
    if (future.exception.isDefined) {
      var resultOrFailure = ResultOrFailure(activeTx)
      val (toBlame, cause) = future.exception.get
      resultOrFailure() = throw cause
      resultOrFailure
    } else ResultOrFailure(future.result.get, activeTx)
 */
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

  // FIXME implement the pre/post restart methods and call annotated methods on the POJO

  // FIXME create new POJO on creation and swap POJO at restart - joinpoint.setTarget(new POJO)
  
  override def receive: PartialFunction[Any, Unit] = {

    case Invocation(joinpoint: JoinPoint, tx: Option[Transaction]) =>
      ActiveObject.threadBoundTx.set(tx)
      try {
        reply(joinpoint.proceed)
      } catch {
        case e =>
          throw new TransactionAwareException(e, tx)
/*
          val resultOrFailure = ResultOrFailure(tx)
          resultOrFailure() = throw e
          reply(resultOrFailure)
*/      }

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
