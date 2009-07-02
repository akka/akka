/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.actor

import java.lang.reflect.Method
import java.net.InetSocketAddress
import kernel.config.ScalaConfig._
import kernel.nio.{RemoteRequest, RemoteClient}
import kernel.reactor.{MessageDispatcher, FutureResult}
import kernel.util.HashCode

import org.codehaus.aspectwerkz.intercept.{Advisable, AroundAdvice}
import org.codehaus.aspectwerkz.joinpoint.{MethodRtti, JoinPoint}
import org.codehaus.aspectwerkz.proxy.Proxy

sealed class ActiveObjectException(msg: String) extends RuntimeException(msg)
class ActiveObjectInvocationTimeoutException(msg: String) extends ActiveObjectException(msg)

object Annotations {
  import se.scalablesolutions.akka.annotation._
  val transactional = classOf[transactional]
  val oneway =        classOf[oneway]
  val immutable =     classOf[immutable]
  val prerestart =     classOf[prerestart]
  val postrestart =    classOf[postrestart]
}

/**
 * Factory for Java API.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectFactory {

  // FIXME How to pass the MessageDispatcher on from active object to child???????

  // FIXME call backs to @prerestart @postrestart methods

  // FIXME dispatcher.newThreadPoolWith....build

  // FIXME JMX enable configuration

  // FIXME Configgy for config

  def newInstance[T](target: Class[T], timeout: Long): T =
    ActiveObject.newInstance(target, new Dispatcher, None, timeout)

  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long): T =
    ActiveObject.newInstance(intf, target, new Dispatcher, None, timeout)

  def newRemoteInstance[T](target: Class[T], timeout: Long, hostname: String, port: Int): T =
    ActiveObject.newInstance(target, new Dispatcher, Some(new InetSocketAddress(hostname, port)), timeout)

  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, hostname: String, port: Int): T =
    ActiveObject.newInstance(intf, target, new Dispatcher, Some(new InetSocketAddress(hostname, port)), timeout)

  def newInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher): T = {
    val actor = new Dispatcher
    actor.dispatcher = dispatcher
    ActiveObject.newInstance(target, actor, None, timeout)
  }
  
  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long, dispatcher: MessageDispatcher): T = {
    val actor = new Dispatcher
    actor.dispatcher = dispatcher
    ActiveObject.newInstance(intf, target, actor, None, timeout)
  }

  def newRemoteInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher, hostname: String, port: Int): T = {
    val actor = new Dispatcher
    actor.dispatcher = dispatcher
    ActiveObject.newInstance(target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, dispatcher: MessageDispatcher, hostname: String, port: Int): T = {
    val actor = new Dispatcher
    actor.dispatcher = dispatcher
    ActiveObject.newInstance(intf, target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  private[kernel] def newInstance[T](target: Class[T], actor: Dispatcher, remoteAddress: Option[InetSocketAddress], timeout: Long): T = {
    ActiveObject.newInstance(target, actor, remoteAddress, timeout)
  }

  private[kernel] def newInstance[T](intf: Class[T], target: AnyRef, actor: Dispatcher, remoteAddress: Option[InetSocketAddress], timeout: Long): T = {
    ActiveObject.newInstance(intf, target, actor, remoteAddress, timeout)
  }
  
  private[kernel] def supervise(restartStrategy: RestartStrategy, components: List[Worker]): Supervisor =
    ActiveObject.supervise(restartStrategy, components)

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
}

/**
 * Factory for Scala API.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ActiveObject {
  val MATCH_ALL = "execution(* *.*(..))"
  val AKKA_CAMEL_ROUTING_SCHEME = "akka"

  def newInstance[T](target: Class[T], timeout: Long): T =
    newInstance(target, new Dispatcher, None, timeout)

  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long): T =
    newInstance(intf, target, new Dispatcher, None, timeout)

  def newRemoteInstance[T](target: Class[T], timeout: Long, hostname: String, port: Int): T =
    newInstance(target, new Dispatcher, Some(new InetSocketAddress(hostname, port)), timeout)

  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, hostname: String, port: Int): T =
    newInstance(intf, target, new Dispatcher, Some(new InetSocketAddress(hostname, port)), timeout)

  def newInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher): T = {
    val actor = new Dispatcher
    actor.dispatcher = dispatcher
    newInstance(target, actor, None, timeout)
  }

  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long, dispatcher: MessageDispatcher): T = {
    val actor = new Dispatcher
    actor.dispatcher = dispatcher
    newInstance(intf, target, actor, None, timeout)
  }

  def newRemoteInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher, hostname: String, port: Int): T = {
    val actor = new Dispatcher
    actor.dispatcher = dispatcher
    newInstance(target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, dispatcher: MessageDispatcher, hostname: String, port: Int): T = {
    val actor = new Dispatcher
    actor.dispatcher = dispatcher
    newInstance(intf, target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  private[kernel] def newInstance[T](target: Class[T], actor: Dispatcher, remoteAddress: Option[InetSocketAddress], timeout: Long): T = {
    if (remoteAddress.isDefined) actor.makeRemote(remoteAddress.get)
    val proxy = Proxy.newInstance(target, false, true)
    actor.initialize(target, proxy)
    // FIXME switch to weaving in the aspect at compile time
    proxy.asInstanceOf[Advisable].aw_addAdvice(
      MATCH_ALL, new ActorAroundAdvice(target, proxy, actor, remoteAddress, timeout))
    proxy.asInstanceOf[T]
  }

  private[kernel] def newInstance[T](intf: Class[T], target: AnyRef, actor: Dispatcher, remoteAddress: Option[InetSocketAddress], timeout: Long): T = {
    if (remoteAddress.isDefined) actor.makeRemote(remoteAddress.get)
    val proxy = Proxy.newInstance(Array(intf), Array(target), false, true)
    actor.initialize(target.getClass, target)
    proxy.asInstanceOf[Advisable].aw_addAdvice(
      MATCH_ALL, new ActorAroundAdvice(intf, target, actor, remoteAddress, timeout))
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
@serializable
sealed class ActorAroundAdvice(val target: Class[_],
                               val targetInstance: AnyRef,
                               val actor: Dispatcher,
                               val remoteAddress: Option[InetSocketAddress],
                               val timeout: Long) extends AroundAdvice {
  val id = target.getName
  actor.timeout = timeout
  actor.start
  
  def invoke(joinpoint: JoinPoint): AnyRef = dispatch(joinpoint)

  private def dispatch(joinpoint: JoinPoint) = {
    if (remoteAddress.isDefined) remoteDispatch(joinpoint)
    else localDispatch(joinpoint)
  }

  private def localDispatch(joinpoint: JoinPoint): AnyRef = {
    val rtti = joinpoint.getRtti.asInstanceOf[MethodRtti]
    if (isOneWay(rtti)) actor ! Invocation(joinpoint)
    else {
      val result = actor !! Invocation(joinpoint)
      if (result.isDefined) result.get
      else throw new IllegalStateException("No result defined for invocation [" + joinpoint + "]")
    }
  }

  private def remoteDispatch(joinpoint: JoinPoint): AnyRef = {
    val rtti = joinpoint.getRtti.asInstanceOf[MethodRtti]
    val oneWay = isOneWay(rtti)
    val future = RemoteClient.clientFor(remoteAddress.get).send(
      new RemoteRequest(false, rtti.getParameterValues, rtti.getMethod.getName, target.getName,
                        timeout, None, oneWay, false, actor.registerSupervisorAsRemoteActor))
    if (oneWay) null // for void methods
    else {
      if (future.isDefined) {
        future.get.await
        val result = getResultOrThrowException(future.get)
        if (result.isDefined) result.get
        else throw new IllegalStateException("No result returned from call to [" + joinpoint + "]")
      } else throw new IllegalStateException("No future returned from call to [" + joinpoint + "]")
    }
  }

  private def getResultOrThrowException[T](future: FutureResult): Option[T] =
    if (future.exception.isDefined) {
      val (_, cause) = future.exception.get
      throw cause
    } else future.result.asInstanceOf[Option[T]]
  
  private def isOneWay(rtti: MethodRtti) =
    rtti.getMethod.isAnnotationPresent(Annotations.oneway) // FIXME investigate why @oneway causes TX to race
    //rtti.getMethod.getReturnType == java.lang.Void.TYPE
}

/**
 * Represents a snapshot of the current invocation.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable private[kernel] case class Invocation(val joinpoint: JoinPoint) {

  override def toString: String = synchronized {
    "Invocation [joinpoint: " + joinpoint.toString + "]"
  }

  override def hashCode(): Int = synchronized {
    var result = HashCode.SEED
    result = HashCode.hash(result, joinpoint)
    result
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[Invocation] &&
    that.asInstanceOf[Invocation].joinpoint == joinpoint
  }
}

/**
 * Generic Actor managing Invocation dispatch, transaction and error management.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[kernel] class Dispatcher extends Actor {
  private val ZERO_ITEM_CLASS_ARRAY = Array[Class[_]]()
  
  makeTransactional

  private[actor] var target: Option[AnyRef] = None
  private var preRestart: Option[Method] = None
  private var postRestart: Option[Method] = None

  private[actor] def initialize(targetClass: Class[_], targetInstance: AnyRef) = {
    id = targetClass.getName
    target = Some(targetInstance)
    val methods = targetInstance.getClass.getDeclaredMethods.toList

    preRestart = methods.find( m => m.isAnnotationPresent(Annotations.prerestart) && m.getName.startsWith("aw$original"))
    if (preRestart.isDefined) preRestart.get.setAccessible(true)
    if (preRestart.isDefined && preRestart.get.getParameterTypes.length != 0)
      throw new IllegalStateException("Method annotated with @prerestart in [" + targetClass.getName + "] must have a zero argument definition")

    postRestart = methods.find( m => m.isAnnotationPresent(Annotations.postrestart) && m.getName.startsWith("aw$original"))
    if (postRestart.isDefined) postRestart.get.setAccessible(true)
    if (postRestart.isDefined && postRestart.get.getParameterTypes.length != 0)
      throw new IllegalStateException("Method annotated with @postrestart in [" + targetClass.getName + "] must have a zero argument definition")
  }

  override def receive: PartialFunction[Any, Unit] = {
    case Invocation(joinpoint: JoinPoint) =>
       reply(joinpoint.proceed)
    case unexpected =>
      throw new ActiveObjectException("Unexpected message [" + unexpected + "] sent to [" + this + "]")
  }

  override protected def preRestart(reason: AnyRef, config: Option[AnyRef]) {
    if (preRestart.isDefined) preRestart.get.invoke(target.get, ZERO_ITEM_CLASS_ARRAY)
  }

  override protected def postRestart(reason: AnyRef, config: Option[AnyRef]) {
    if (postRestart.isDefined) postRestart.get.invoke(target.get, ZERO_ITEM_CLASS_ARRAY)
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
