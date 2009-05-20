/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import kernel.camel.{MessageDriven, ActiveObjectProducer}
import config.ActiveObjectGuiceConfigurator
import config.ScalaConfig._

import java.util.{List => JList, ArrayList}
import java.lang.reflect.{Method, Field}
import org.codehaus.aspectwerkz.intercept.{Advisable, AroundAdvice}
import org.codehaus.aspectwerkz.joinpoint.{MethodRtti, JoinPoint}
import org.codehaus.aspectwerkz.proxy.Proxy
import java.lang.annotation.Annotation

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

  def supervise(restartStrategy: RestartStrategy, components: JList[Worker]): Supervisor =
    ActiveObject.supervise(restartStrategy, components.toArray.toList.asInstanceOf[List[Worker]])  
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
    proxy.asInstanceOf[Advisable].aw_addAdvice("execution(* *.*(..))", new ActorAroundAdvice(target, proxy, server))
    proxy.asInstanceOf[T]
  }

  def newInstance[T](intf: Class[T], target: AnyRef, server: GenericServerContainer): T = {
    val proxy = Proxy.newInstance(Array(intf), Array(target), false, true)
    proxy.asInstanceOf[Advisable].aw_addAdvice("execution(* *.*(..))", new ActorAroundAdvice(intf, target, server))
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

  /*
  private def supervise(proxy: AnyRef): Supervisor = 
    supervise(
      RestartStrategy(OneForOne, 5, 1000),
      Worker(
        proxy.server,
        LifeCycle(Permanent, 100))
      :: Nil)
  */
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */

// FIXME: STM that allows concurrent updates, detects collision, rolls back and restarts
sealed class ActorAroundAdvice(target: Class[_],
                               targetInstance: AnyRef,
                               val server: GenericServerContainer) extends AroundAdvice {
  val (maps, vectors, refs) = getTransactionalItemsFor(targetInstance) 
  server.transactionalRefs = refs
  server.transactionalMaps = maps
  server.transactionalVectors = vectors

  import ActiveObject.threadBoundTx
  private[this] var activeTx: Option[Transaction] = None
 
  def invoke(joinpoint: JoinPoint): AnyRef = {
    // FIXME: switch to using PCD annotation matching, break out into its own aspect + switch to StaticJoinPoint
    val method = joinpoint.getRtti.asInstanceOf[MethodRtti].getMethod
    if (method.isAnnotationPresent(Annotations.transactional)) {
      if (activeTx.isDefined) {
        val tx = activeTx.get
        //val cflowTx = threadBoundTx.get
        //  if (cflowTx.isDefined && cflowTx.get != tx) {
         // new tx in scope; try to commit
        tx.commit(server)
        threadBoundTx.set(None)
        activeTx = None
   //   }
      }
      // FIXME: check if we are already in a transaction if so NEST (set parent)
      val newTx = new Transaction
      newTx.begin(server)
      threadBoundTx.set(Some(newTx))
    }

    val cflowTx = threadBoundTx.get
     if (!activeTx.isDefined && cflowTx.isDefined) {
      val currentTx = cflowTx.get
      currentTx.join(server)
      activeTx = Some(currentTx)
    }
    activeTx = threadBoundTx.get
    invoke(joinpoint, activeTx)
    //invoke(Invocation(method, joinpoint.getRtti.asInstanceOf[MethodRtti].getParameterValues, targetInstance, activeTx))
  }

  private def invoke(joinpoint: JoinPoint, tx: Option[Transaction]): AnyRef =  {
    val result: AnyRef =
/*
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
        
      } else */
      // FIXME: switch to using PCD annotation matching, break out into its own aspect + switch to StaticJoinPoint
      if (joinpoint.getRtti.asInstanceOf[MethodRtti].getMethod.isAnnotationPresent(Annotations.oneway)) {
        server ! (tx, joinpoint)
      } else {
        val result: ErrRef[AnyRef] =
          server !!! ((tx, joinpoint), {
            var ref = ErrRef(activeTx)
            ref() = throw new ActiveObjectInvocationTimeoutException("Invocation to active object [" + targetInstance.getClass.getName + "] timed out after " + server.timeout + " milliseconds")
            ref
          })
        try {
          result()
        } catch {
          case e => 
            println("$$$$$$$$$$$$$$ " + joinpoint)
            rollback(result.tx)
            throw e
        }
      }
    // FIXME: clear threadBoundTx on successful commit
    if (activeTx.isDefined) activeTx.get.precommit(server)
    result
  }

  private def rollback(tx: Option[Transaction]) = tx match {
    case None => {} // no tx; nothing to do
    case Some(tx) =>
      tx.rollback(server)
      threadBoundTx.set(Some(tx))
  }

  private def getTransactionalItemsFor(targetInstance: AnyRef): 
    Tuple3[List[TransactionalMap[_, _]], List[TransactionalVector[_]], List[TransactionalRef[_]]] = {
    require(targetInstance != null)
    var maps:    List[TransactionalMap[_, _]] = Nil 
    var vectors: List[TransactionalVector[_]] = Nil 
    var refs:    List[TransactionalRef[_]] = Nil 
    for {
      field <- targetInstance.getClass.getDeclaredFields.toArray.toList.asInstanceOf[List[Field]]
      fieldType = field.getType
      if fieldType == classOf[TransactionalMap[_, _]] || 
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
    (maps, vectors, refs)
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

/**
 * Represents a snapshot of the current invocation.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[kernel] case class Invocation(val method: Method,
                                      val args: Array[AnyRef],
                                      val target: AnyRef,
                                      val tx: Option[Transaction]) {
  method.setAccessible(true)

  def invoke: AnyRef = synchronized {
    println("======== " + this.toString)
    if (method.getDeclaringClass.isInterface) {
      target.getClass.getDeclaredMethod(method.getName, method.getParameterTypes).invoke(target, args:_*)
    } else method.invoke(target, args:_*)
  }

  override def toString: String = synchronized {
    "Invocation [method: " + method.getName + ", args: " + argsToString(args) + ", target: " + target + "]"
  }
  
  override def hashCode(): Int = synchronized {
    var result = HashCode.SEED
    result = HashCode.hash(result, method)
    result = HashCode.hash(result, args)
    result = HashCode.hash(result, target)
    result
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[Invocation] &&
    that.asInstanceOf[Invocation].method == method &&
    that.asInstanceOf[Invocation].target == target &&
    isEqual(that.asInstanceOf[Invocation].args, args)
  }

  private[this] def isEqual(a1: Array[Object], a2: Array[Object]): Boolean =
    (a1 == null && a2 == null) ||
    (a1 != null && 
     a2 != null && 
     a1.size == a2.size && 
     a1.zip(a2).find(t => t._1 == t._2).isDefined)

  private[this] def argsToString(array: Array[Object]): String =
    array.foldLeft("(")(_ + " " + _) + ")" 
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
*/