/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.kernel

import com.scalablesolutions.akka.supervisor._

import java.util.{List => JList, ArrayList}

import java.lang.reflect.{Method, Field, InvocationHandler, Proxy, InvocationTargetException}
import java.lang.annotation.Annotation

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
    supervisor ! com.scalablesolutions.akka.supervisor.Start
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
  private val oneway = classOf[com.scalablesolutions.akka.annotation.oneway]
  private var targetInstance: AnyRef = _
  private[akka] def setTargetInstance(instance: AnyRef) = targetInstance = instance

  private[ActiveObjectProxy] object dispatcher extends GenericServer {
    override def body: PartialFunction[Any, Unit] = {
      case invocation: Invocation =>
        try {
          reply(ErrRef(invocation.invoke))
        } catch {
          case e: InvocationTargetException =>
            val te = e.getTargetException
            te.printStackTrace
            reply(ErrRef({ throw te }))
          case e =>
            e.printStackTrace
            reply(ErrRef({ throw e }))
        }
      case 'exit =>  exit; reply()
      case unexpected => throw new ActiveObjectException("Unexpected message to actor proxy: " + unexpected)
    }
  }

  private[akka] val server = new GenericServerContainer(target.getName, () => dispatcher)
  server.setTimeout(timeout)
  
  def invoke(proxy: AnyRef, m: Method, args: Array[AnyRef]): AnyRef = 
    invoke(Invocation(m, args, targetInstance))

  def invoke(invocation: Invocation): AnyRef =  {
    if (invocation.method.isAnnotationPresent(oneway)) server ! invocation
    else {
      val result: ErrRef[AnyRef] = server !!! (invocation, ErrRef({ 
        throw new ActiveObjectInvocationTimeoutException(
          "proxy invocation timed out after " + timeout + " milliseconds") 
      })) 
      result()
    }
  }
}

/**
 * Represents a snapshot of the current invocation.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
case class Invocation(val method: Method, val args: Array[Object], val target: AnyRef) {
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
