/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.kernel

import scala.actors.behavior._

import java.util.{List => JList, ArrayList}

import java.lang.reflect.{Method, Field, InvocationHandler, Proxy, InvocationTargetException}
import java.lang.annotation.Annotation

sealed class ActiveObjectException(msg: String) extends RuntimeException(msg)
class ActiveObjectInvocationTimeoutException(msg: String) extends ActiveObjectException(msg)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ActiveObject {

  def newInstance[T](intf: Class[T] forSome {type T}, target: AnyRef, timeout: Int): T = {
    val proxy = new ActiveObjectProxy(target, timeout)
    supervise(proxy)
    newInstance(intf, proxy)
  }

  def newInstance[T](intf: Class[T] forSome {type T}, proxy: ActiveObjectProxy): T = {
    Proxy.newProxyInstance(
      proxy.target.getClass.getClassLoader,
      Array(intf),
      proxy).asInstanceOf[T]
  }

  def supervise(restartStrategy: RestartStrategy, components: List[Worker]): Supervisor = {
  	object factory extends SupervisorFactory {
      override def getSupervisorConfig: SupervisorConfig = {
        SupervisorConfig(restartStrategy, components)
      }
    }
    val supervisor = factory.newSupervisor
    supervisor ! scala.actors.behavior.Start
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
class ActiveObjectProxy(val target: AnyRef, val timeout: Int) extends InvocationHandler {
  private val oneway = classOf[scala.actors.annotation.oneway]

  private[ActiveObjectProxy] object dispatcher extends GenericServer {
    override def body: PartialFunction[Any, Unit] = {
      case invocation: Invocation =>
        try {
          reply(ErrRef(invocation.invoke))
        } catch {
          case e: InvocationTargetException => reply(ErrRef({ throw e.getTargetException }))
          case e => reply(ErrRef({ throw e }))
        }
      case 'exit =>  exit; reply()
      case unexpected => throw new ActiveObjectException("Unexpected message to actor proxy: " + unexpected)
    }
  }

  private[kernel] val server = new GenericServerContainer(target.getClass.getName, () => dispatcher)
  server.setTimeout(timeout)
  
  def invoke(proxy: AnyRef, m: Method, args: Array[AnyRef]): AnyRef = invoke(Invocation(m, args, target))

  def invoke(invocation: Invocation): AnyRef =  {
    if (invocation.method.isAnnotationPresent(oneway)) server ! invocation
    else {
      val result: ErrRef[AnyRef] = server !!! (invocation, ErrRef({ throw new ActiveObjectInvocationTimeoutException("proxy invocation timed out after " + timeout + " milliseconds") })) 
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
  def invoke: AnyRef = method.invoke(target, args: _*)

  override def toString: String = "Invocation [method: " + method.getName + ", args: " + args + ", target: " + target + "]"

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
    that.asInstanceOf[Invocation].args == args
    that.asInstanceOf[Invocation].target == target
  }
}

