/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.serialization.Serializable
import se.scalablesolutions.akka.actor.annotation.transactionrequired
import se.scalablesolutions.akka.actor.annotation.prerestart
import se.scalablesolutions.akka.actor.annotation.postrestart
import se.scalablesolutions.akka.actor.annotation.inittransactionalstate
import se.scalablesolutions.akka.actor.annotation.oneway
import se.scalablesolutions.akka.stm._

import com.google.inject.Inject

trait Bar {
  @oneway
  def bar(msg: String): String
  def getExt: Ext
}

class BarImpl extends Bar {
  @Inject private var ext: Ext = _
  def getExt: Ext = ext
  def bar(msg: String) = msg
}

trait Ext
class ExtImpl extends Ext
 
class Foo extends Serializable.JavaJSON {
  @Inject
  private var bar: Bar = _
   def body = this
   def getBar = bar
   def foo(msg: String): String = msg + "_foo "
   def bar(msg: String): String = bar.bar(msg)
   def longRunning = {
     Thread.sleep(10000)
     "test"
   }
   def throwsException: String = {
     if (true) throw new RuntimeException("expected")
     "test"
  }
}

@serializable class InMemFailer { 
  def fail = throw new RuntimeException("expected")
}

@transactionrequired
class InMemStateful {
  private lazy val mapState = TransactionalState.newMap[String, String]
  private lazy val vectorState = TransactionalState.newVector[String]
  private lazy val refState = TransactionalState.newRef[String]

  def getMapState(key: String): String = mapState.get(key).get
  def getVectorState: String = vectorState.last
  def getRefState: String = refState.get.get
  def setMapState(key: String, msg: String): Unit = mapState.put(key, msg)
  def setVectorState(msg: String): Unit = vectorState.add(msg)
  def setRefState(msg: String): Unit = refState.swap(msg)
  def success(key: String, msg: String): Unit = {
    mapState.put(key, msg)
    vectorState.add(msg)
    refState.swap(msg)
  }

 def success(key: String, msg: String, nested: InMemStatefulNested): Unit = {
    mapState.put(key, msg)
    vectorState.add(msg)
    refState.swap(msg)
    nested.success(key, msg)
  }

 def failure(key: String, msg: String, failer: InMemFailer): String = {
    mapState.put(key, msg)
    vectorState.add(msg)
    refState.swap(msg)
    failer.fail
     msg
  }

 def failure(key: String, msg: String, nested: InMemStatefulNested, failer: InMemFailer): String = {
    mapState.put(key, msg)
    vectorState.add(msg)
    refState.swap(msg)
    nested.failure(key, msg, failer)
    msg
  }

  def thisMethodHangs(key: String, msg: String, failer: InMemFailer) = setMapState(key, msg)

  @prerestart def preRestart = println("################ PRE RESTART")
  @postrestart def postRestart = println("################ POST RESTART")
}

@transactionrequired
class InMemStatefulNested extends InMemStateful

