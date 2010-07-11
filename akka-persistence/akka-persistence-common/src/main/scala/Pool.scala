/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.common

import org.apache.commons.pool._
import org.apache.commons.pool.impl._

import org.apache.thrift.transport._

trait Pool[T] extends java.io.Closeable {
  def borrowObject: T
  def returnObject(t: T): Unit
  def invalidateObject(t: T): Unit
  def addObject(): Unit
  def getNumIdle: Int
  def getNumActive: Int
  def clear(): Unit
  def setFactory(factory: PoolItemFactory[T]): Unit
}

trait PoolFactory[T] {
  def createPool: Pool[T]
}

trait PoolItemFactory[T] {
  def makeObject: T
  def destroyObject(t: T): Unit
  def validateObject(t: T): Boolean
  def activateObject(t: T): Unit
  def passivateObject(t: T): Unit
}

trait PoolBridge[T, OP <: ObjectPool] extends Pool[T] {
  val impl: OP
  override def borrowObject: T = impl.borrowObject.asInstanceOf[T]
  override def returnObject(t: T) = impl.returnObject(t)
  override def invalidateObject(t: T) = impl.invalidateObject(t)
  override def addObject = impl.addObject
  override def getNumIdle: Int = impl.getNumIdle
  override def getNumActive: Int = impl.getNumActive
  override def clear(): Unit = impl.clear()
  override def close(): Unit = impl.close()
  override def setFactory(factory: PoolItemFactory[T]) = impl.setFactory(toPoolableObjectFactory(factory))

  def toPoolableObjectFactory[T](pif: PoolItemFactory[T]) = new PoolableObjectFactory {
    def makeObject: Object = pif.makeObject.asInstanceOf[Object]
    def destroyObject(o: Object): Unit = pif.destroyObject(o.asInstanceOf[T])
    def validateObject(o: Object): Boolean = pif.validateObject(o.asInstanceOf[T])
    def activateObject(o: Object): Unit = pif.activateObject(o.asInstanceOf[T])
    def passivateObject(o: Object): Unit = pif.passivateObject(o.asInstanceOf[T])
  }
}

object StackPool {
  def apply[T](factory: PoolItemFactory[T]) = new PoolBridge[T,StackObjectPool] {
    val impl = new StackObjectPool(toPoolableObjectFactory(factory))
  }

  def apply[T](factory: PoolItemFactory[T], maxIdle: Int) = new PoolBridge[T,StackObjectPool] {
    val impl = new StackObjectPool(toPoolableObjectFactory(factory),maxIdle)
  }

  def apply[T](factory: PoolItemFactory[T], maxIdle: Int, initIdleCapacity: Int) = new PoolBridge[T,StackObjectPool] {
    val impl = new StackObjectPool(toPoolableObjectFactory(factory),maxIdle,initIdleCapacity)
  }
}

object SoftRefPool {
  def apply[T](factory: PoolItemFactory[T]) = new PoolBridge[T,SoftReferenceObjectPool] {
    val impl = new SoftReferenceObjectPool(toPoolableObjectFactory(factory))
  }
}

trait TransportFactory[T <: TTransport] extends PoolItemFactory[T] {
  def createTransport: T
  def makeObject: T = createTransport
  def destroyObject(transport: T): Unit = transport.close
  def validateObject(transport: T) = transport.isOpen
  def activateObject(transport: T): Unit = if( !transport.isOpen ) transport.open else ()
  def passivateObject(transport: T): Unit = transport.flush
}

case class SocketProvider(val host: String, val port: Int) extends TransportFactory[TSocket] {
  def createTransport = {
    val t =   new TSocket(host, port)
    t.open
    t
  }
}
