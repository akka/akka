/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import kernel.camel.{MessageDriven, ActiveObjectProducer}
import config.ActiveObjectGuiceConfigurator
import config.ScalaConfig._

import java.util.{List => JList, ArrayList}
import java.lang.reflect.{Method, Field}
import java.lang.annotation.Annotation

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
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectFactory {
  def newInstance[T](target: Class[T], server: GenericServerContainer): T = {
    ActiveObject.newInstance(target, server)
  }

  def newInstance[T](intf: Class[T], target: AnyRef, server: GenericServerContainer): T = {
    ActiveObject.newInstance(intf, target, server)
  }

  def supervise(restartStrategy: RestartStrategy, components: List[Worker]): Supervisor =
    ActiveObject.supervise(restartStrategy, components)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ActiveObject {
  val AKKA_CAMEL_ROUTING_SCHEME = "akka"

  private[kernel] val threadBoundTx: ThreadLocal[Option[Transaction]] = {
    val tl = new ThreadLocal[Option[Transaction]]
    tl.set(None)
    tl
  }

  def newInstance[T](target: Class[T], server: GenericServerContainer): T = {
    val proxy = Proxy.newInstance(target, false, true)
    // FIXME switch to weaving in the aspect at compile time
    proxy.asInstanceOf[Advisable].aw_addAdvice("execution(* *.*(..))", new TransactionalAroundAdvice(target, proxy, server))
    proxy.asInstanceOf[T]
  }

  def newInstance[T](intf: Class[T], target: AnyRef, server: GenericServerContainer): T = {
    val proxy = Proxy.newInstance(Array(intf), Array(target), false, true)
    proxy.asInstanceOf[Advisable].aw_addAdvice("execution(* *.*(..))", new TransactionalAroundAdvice(intf, target, server))
    proxy.asInstanceOf[T]
  }

  def supervise(restartStrategy: RestartStrategy, components: List[Worker]): Supervisor = {
    object factory extends SupervisorFactory {
      override def getSupervisorConfig = SupervisorConfig(restartStrategy, components)
    }
    val supervisor = factory.newSupervisor
    supervisor ! se.scalablesolutions.akka.kernel.Start
    supervisor
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */

// FIXME: STM that allows concurrent updates, detects collision, rolls back and restarts
sealed class TransactionalAroundAdvice(target: Class[_],
                                       targetInstance: AnyRef,
                                       server: GenericServerContainer) extends AroundAdvice {
  private val (maps, vectors, refs) = getTransactionalItemsFor(targetInstance)
  server.transactionalRefs = refs
  server.transactionalMaps = maps
  server.transactionalVectors = vectors

  import ActiveObject.threadBoundTx
  private[this] var activeTx: Option[Transaction] = None

  // FIXME: switch to using PCD annotation matching, break out into its own aspect + switch to StaticJoinPoint
  def invoke(joinpoint: JoinPoint): AnyRef = {
    val rtti = joinpoint.getRtti.asInstanceOf[MethodRtti]
    val method = rtti.getMethod

    if (method.isAnnotationPresent(Annotations.transactional)) {
      tryToCommitTransaction
      startNewTransaction
    }
    joinExistingTransaction

    val result: AnyRef = if (rtti.getMethod.isAnnotationPresent(Annotations.oneway)) sendOneWay(joinpoint)
                         else handleResult(sendAndReceiveEventually(joinpoint))
    tryToPrecommitTransaction
    result
  }

  private def startNewTransaction = {
    val newTx = new Transaction
    newTx.begin(server)
    threadBoundTx.set(Some(newTx))
  }

  private def joinExistingTransaction = {
    val cflowTx = threadBoundTx.get
    if (!activeTx.isDefined && cflowTx.isDefined) {
      val currentTx = cflowTx.get
      currentTx.join(server)
      activeTx = Some(currentTx)
    }
    activeTx = threadBoundTx.get
  }

  private def tryToPrecommitTransaction = {
    // FIXME: clear threadBoundTx on successful commit
    if (activeTx.isDefined) activeTx.get.precommit(server)
  }

  private def tryToCommitTransaction = if (activeTx.isDefined) {
    val tx = activeTx.get
    tx.commit(server)
    threadBoundTx.set(None)
    activeTx = None
  }

  private def handleResult(result: ErrRef[AnyRef]): AnyRef = {
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
      tx.rollback(server)
      threadBoundTx.set(Some(tx))
  }

  private def sendOneWay(joinpoint: JoinPoint) = server ! (activeTx, joinpoint)

  private def sendAndReceiveEventually(joinpoint: JoinPoint): ErrRef[AnyRef] = {
    server !!! ((activeTx, joinpoint), {
      var ref = ErrRef(activeTx)
      ref() = throw new ActiveObjectInvocationTimeoutException("Invocation to active object [" + targetInstance.getClass.getName + "] timed out after " + server.timeout + " milliseconds")
      ref
    })
  }

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
    for {
        field <- target.getDeclaredFields.toArray.toList.asInstanceOf[List[Field]]
        fieldType = field.getType
        if fieldType ==  classOf[TransactionalMap[_, _]] ||
           fieldType  == classOf[TransactionalVector[_]] ||
           fieldType  == classOf[TransactionalRef[_]]
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
      if (parent == null) (maps, vectors, refs)
      else getTransactionalItemsFor(parent)
    }

    // start the search for transactional items, crawl the class hierarchy up until we reach 'null'
    getTransactionalItemsFor(targetInstance.getClass)
  }
}

/**
 * Generic GenericServer managing Invocation dispatch, transaction and error management.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[kernel] class Dispatcher(val targetName: String) extends GenericServer {
  override def body: PartialFunction[Any, Unit] = {

    case (tx: Option[Transaction], joinpoint: JoinPoint) =>
      ActiveObject.threadBoundTx.set(tx)
      try {
        reply(ErrRef(joinpoint.proceed, tx))
      } catch {
        case e =>
          val ref = ErrRef(tx); ref() = throw e; reply(ref)
      }

    case 'exit =>
      exit; reply()

/*    case exchange: Exchange =>
      println("=============> Exchange From Actor: " + exchange)
      val invocation = exchange.getIn.getBody.asInstanceOf[Invocation]
      invocation.invoke
*/
    case unexpected =>
      throw new ActiveObjectException("Unexpected message [" + unexpected + "] to [" + this + "] from [" + sender + "]")
  }

  override def toString(): String = "GenericServer[" + targetName + "]"
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