/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.kernel

import scala.actors.behavior._
import scala.actors.annotation.oneway

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */


// class ActiveObjectSuite extends TestNGSuite {

//   private var messageLog = ""

//   trait Foo {
//     def foo(msg: String): String    
//     @oneway def bar(msg: String)
//     def longRunning
//     def throwsException
//   }

//   class FooImpl extends Foo {
//     val bar: Bar = new BarImpl 
//     def foo(msg: String): String = { 
//       messageLog += msg
//       "return_foo " 
//     }
//     def bar(msg: String) = bar.bar(msg)
//     def longRunning = Thread.sleep(10000)
//     def throwsException = error("expected")
//   }

//   trait Bar {
//     @oneway def bar(msg: String)
//   }

//   class BarImpl extends Bar {
//     def bar(msg: String) = { 
//       Thread.sleep(100)
//       messageLog += msg
//     }
//   }

//   @BeforeMethod
//   def setup = messageLog = ""

//   @Test { val groups=Array("unit") }
//   def testCreateGenericServerBasedComponentUsingDefaultSupervisor = {
//     val foo = ActiveObject.newInstance[Foo](classOf[Foo], new FooImpl, 1000)
    
//     val result = foo.foo("foo ")
//     messageLog += result

//     foo.bar("bar ")
//     messageLog += "before_bar "

//     Thread.sleep(500)
//     assert(messageLog === "foo return_foo before_bar bar ")
//   }

//   @Test { val groups=Array("unit") }
//   def testCreateGenericServerBasedComponentUsingCustomSupervisorConfiguration = {
//     val proxy = new ActiveObjectProxy(new FooImpl, 1000)

//     val supervisor = 
//       ActiveObject.supervise(
//         RestartStrategy(AllForOne, 3, 100),
//         Component(
//           proxy,
//           LifeCycle(Permanent, 100))
//         :: Nil)

//     val foo = ActiveObject.newInstance[Foo](classOf[Foo], proxy)

//     val result = foo.foo("foo ")
//     messageLog += result
    
//     foo.bar("bar ")
//     messageLog += "before_bar "

//     Thread.sleep(500)
//     assert(messageLog === "foo return_foo before_bar bar ")

//     supervisor ! Stop
//   }

//    @Test { val groups=Array("unit") }
//   def testCreateTwoGenericServerBasedComponentUsingCustomSupervisorConfiguration = {
//     val fooProxy = new ActiveObjectProxy(new FooImpl, 1000)
//     val barProxy = new ActiveObjectProxy(new BarImpl, 1000)

//     val supervisor = 
//       ActiveObject.supervise(
//         RestartStrategy(AllForOne, 3, 100),
//         Component(
//           fooProxy,
//           LifeCycle(Permanent, 100)) ::
//         Component(
//           barProxy,
//           LifeCycle(Permanent, 100))
//         :: Nil)

//     val foo = ActiveObject.newInstance[Foo](classOf[Foo], fooProxy)
//     val bar = ActiveObject.newInstance[Bar](classOf[Bar], barProxy)

//     val result = foo.foo("foo ")
//     messageLog += result

//     bar.bar("bar ")
//     messageLog += "before_bar "

//     Thread.sleep(500)
//     assert(messageLog === "foo return_foo before_bar bar ")
    
//     supervisor ! Stop
//   }

//   @Test { val groups=Array("unit") }
//   def testCreateGenericServerBasedComponentUsingDefaultSupervisorAndForcedTimeout = {
//     val foo = ActiveObject.newInstance[Foo](classOf[Foo], new FooImpl, 1000)  
//     intercept(classOf[ActiveObjectInvocationTimeoutException]) {
//       foo.longRunning
//     }
//     assert(true === true)
//   }

//   @Test { val groups=Array("unit") }
//   def testCreateGenericServerBasedComponentUsingDefaultSupervisorAndForcedException = {
//     val foo = ActiveObject.newInstance[Foo](classOf[Foo], new FooImpl, 10000)  
//     intercept(classOf[RuntimeException]) {
//       foo.throwsException
//     }
//     assert(true === true)
//   }
// }
