/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import java.util.{List => JList, ArrayList}
import java.lang.reflect.{Method, Field, InvocationHandler, Proxy, InvocationTargetException}
import java.lang.annotation.Annotation

//import voldemort.client.{SocketStoreClientFactory, StoreClient, StoreClientFactory}
//import voldemort.versioning.Versioned

sealed class ActiveObjectException(msg: String) extends RuntimeException(msg)
class ActiveObjectInvocationTimeoutException(msg: String) extends ActiveObjectException(msg)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectFactory {
  def newInstance[T](intf: Class[_], proxy: ActiveObjectProxy): T = ActiveObject.newInstance(intf, proxy)

  def supervise(restartStrategy: RestartStrategy, components: JList[Worker]): Supervisor =
    ActiveObject.supervise(restartStrategy, components.toArray.toList.asInstanceOf[List[Worker]])  
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ActiveObject {
   private[kernel] val threadBoundTx: ThreadLocal[Option[Transaction]] = {
     val tl = new ThreadLocal[Option[Transaction]]
     tl.set(None)
     tl
   }

   def newInstance[T](intf: Class[_], proxy: ActiveObjectProxy): T = {
     Proxy.newProxyInstance(
       intf.getClassLoader,
       Array(intf),
       proxy).asInstanceOf[T]
   }

  def newInstance[T](intf: Class[_], target: AnyRef, timeout: Int): T = {
    val proxy = new ActiveObjectProxy(intf, target.getClass, timeout)
    proxy.setTargetInstance(target)
    supervise(proxy)
    newInstance(intf, proxy)
  }

  def supervise(restartStrategy: RestartStrategy, components: List[Worker]): Supervisor = {
    object factory extends SupervisorFactory {
      override def getSupervisorConfig = SupervisorConfig(restartStrategy, components)
    }
    val supervisor = factory.newSupervisor
    supervisor ! se.scalablesolutions.akka.kernel.Start
    supervisor
  }

  private def supervise(proxy: ActiveObjectProxy): Supervisor = 
    supervise(
      RestartStrategy(OneForOne, 5, 1000),
      Worker(
        proxy.server,
        LifeCycle(Permanent, 100))
      :: Nil)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectProxy(val intf: Class[_], val target: Class[_], val timeout: Int) extends InvocationHandler {
  val transactional = classOf[se.scalablesolutions.akka.annotation.transactional]
  val oneway =        classOf[se.scalablesolutions.akka.annotation.oneway]
  val immutable =     classOf[se.scalablesolutions.akka.annotation.immutable]
  val state=          classOf[se.scalablesolutions.akka.annotation.state]

  private[this] var activeTx: Option[Transaction] = None

  private var targetInstance: AnyRef = _
  private[kernel] def setTargetInstance(instance: AnyRef) = {
    targetInstance = instance
    getStateList(targetInstance) match {
      case Nil => {}
      case states => server.states = states
    }
  }

  private[this] val dispatcher = new GenericServer {
    override def body: PartialFunction[Any, Unit] = {
      case invocation: Invocation =>
        val tx = invocation.tx
        ActiveObject.threadBoundTx.set(tx)
        try {
          reply(ErrRef(invocation.invoke, tx))
        } catch {
          case e: InvocationTargetException =>
            val te = e.getTargetException
            te.printStackTrace
            reply(ErrRef({ throw te }, tx))
          case e =>
            e.printStackTrace
            reply(ErrRef({ throw e }, tx))
        }
      case 'exit =>  exit; reply()
      case unexpected => throw new ActiveObjectException("Unexpected message to actor proxy: " + unexpected)
    }
  }

  private[kernel] val server = new GenericServerContainer(target.getName, () => dispatcher)
  server.setTimeout(timeout)

  def invoke(proxy: AnyRef, m: Method, args: Array[AnyRef]): AnyRef = {
    if (m.isAnnotationPresent(transactional)) {
      val newTx = new Transaction
      newTx.begin(server)
      ActiveObject.threadBoundTx.set(Some(newTx))
    }
    val cflowTx = ActiveObject.threadBoundTx.get

//    println("========== invoking: " + m.getName)
//    println("========== cflowTx: " + cflowTx)
//    println("========== activeTx: " + activeTx)
    activeTx match {
      case Some(tx) =>
        if (cflowTx.isDefined && cflowTx.get != tx) {
          // new tx in scope; try to commit
          tx.commit(server)
          activeTx = None
        } 
      case None =>
        if (cflowTx.isDefined) activeTx = Some(cflowTx.get)
    }
    activeTx = ActiveObject.threadBoundTx.get
    invoke(Invocation(m, args, targetInstance, activeTx))
  }

  private def invoke(invocation: Invocation): AnyRef =  {
    val result: AnyRef = 
      if (invocation.method.isAnnotationPresent(oneway)) server ! invocation
      else {
        val result: ErrRef[AnyRef] =
          server !!! (invocation, {
            var ref = ErrRef(activeTx)
            ref() = throw new ActiveObjectInvocationTimeoutException("Invocation to active object [" + targetInstance.getClass.getName + "] timed out after " + timeout + " milliseconds")
            ref
          })
        try {
          result()
        } catch {
          case e => 
            rollback(result.tx)
            throw e
        }
      }
    if (activeTx.isDefined) activeTx.get.precommit(server)
    result
  }

  private def rollback(tx: Option[Transaction]) = tx match {
    case None => {} // no tx; nothing to do
    case Some(tx) =>
      println("================ ROLLING BACK")
      tx.rollback(server)
      ActiveObject.threadBoundTx.set(Some(tx))
  }

  private def getStateList(targetInstance: AnyRef): List[State[_,_]] = {
    require(targetInstance != null)
    import se.scalablesolutions.akka.kernel.configuration.ConfigurationException
    val states = for {
      field <- target.getDeclaredFields
      if field.isAnnotationPresent(state)
      state = field.get(targetInstance)
      if state != null
    } yield {
      if (!state.isInstanceOf[State[_, _]]) throw new ConfigurationException("Fields annotated with [@state] needs to to be a subtype of [se.scalablesolutions.akka.kernel.State[K, V]]")
      state
    }
    states
//    if (fields.size > 1) throw new ConfigurationException("Stateful active object can only have one single field '@Inject TransientObjectState state' defined")
  }
}

/**
 * Represents a snapshot of the current invocation.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
case class Invocation(val method: Method,
                      val args: Array[AnyRef],
                      val target: AnyRef,
                      val tx: Option[Transaction]) {
  method.setAccessible(true)

  def invoke: AnyRef = method.invoke(target, args:_*)

  override def toString: String = 
    "Invocation [method: " + method.getName + ", args: " + argsToString(args) + ", target: " + target + "]"

  override def hashCode(): Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, method)
    result = HashCode.hash(result, args)
    result = HashCode.hash(result, target)
    result
  }

  override def equals(that: Any): Boolean = {
    that != null &&
    that.isInstanceOf[Invocation] &&
    that.asInstanceOf[Invocation].method == method &&
    that.asInstanceOf[Invocation].target == target &&
    isEqual(that.asInstanceOf[Invocation].args, args)
  }

  private def isEqual(a1: Array[Object], a2: Array[Object]): Boolean =
    (a1 == null && a2 == null) ||
    (a1 != null && 
     a2 != null && 
     a1.size == a2.size && 
     a1.zip(a2).find(t => t._1 == t._2).isDefined)

  private def argsToString(array: Array[Object]): String = synchronized {
    array.foldLeft("(")(_ + " " + _) + ")" 
  }
}
