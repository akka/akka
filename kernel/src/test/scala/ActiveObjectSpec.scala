/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnit3Suite

import se.scalablesolutions.akka.annotation.{oneway, transactional, stateful}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ActiveObjectSpec {
  var messageLog = ""
}
class ActiveObjectSpec extends Spec with ShouldMatchers {

  describe("An ActiveObject") {

    it("(with default supervisor) should dispatch method calls normally") {
      val foo = ActiveObject.newInstance[Foo](classOf[Foo], new FooImpl, 1000)

      val result = foo.foo("foo ")
      ActiveObjectSpec.messageLog += result

      foo.bar("bar ")
      ActiveObjectSpec.messageLog += "before_bar "

      Thread.sleep(500)
      ActiveObjectSpec.messageLog should equal ("foo return_foo before_bar bar ")
    }

    it("should not rollback state for a stateful server in case of success") {
      val stateful = ActiveObject.newInstance[Stateful](classOf[Stateful], new StatefulImpl, 1000)

      stateful.success("new state")
      stateful.state should equal ("new state")
    }

    it("should rollback state for a stateful server in case of failure") {
      val stateful = ActiveObject.newInstance[Stateful](classOf[Stateful], new StatefulImpl, 1000)
      val failer = ActiveObject.newInstance[Failer](classOf[Failer], new FailerImpl, 1000)

      stateful.failure("new state", failer)
      stateful.state should equal ("nil")
    }
  }
}

trait Foo {
  def foo(msg: String): String
  @transactional def fooInTx(msg: String): String
  @oneway def bar(msg: String)
  def longRunning
  def throwsException
}

class FooImpl extends Foo {
  val bar: Bar = new BarImpl
  def foo(msg: String): String = {
    ActiveObjectSpec.messageLog += msg
    "return_foo "
  }
  def fooInTx(msg: String): String = {
    ActiveObjectSpec.messageLog += msg
    "return_foo "
  }
  def bar(msg: String) = bar.bar(msg)
  def longRunning = Thread.sleep(10000)
  def throwsException = error("expected")
}

trait Bar {
  @oneway def bar(msg: String)
}

class BarImpl extends Bar {
  def bar(msg: String) = {
    Thread.sleep(100)
    ActiveObjectSpec.messageLog += msg
  }
}

trait Stateful {
  @transactional def success(msg: String)
  @transactional def failure(msg: String, failer: Failer)
  def state: String
}

@stateful
class StatefulImpl extends Stateful {
  var state: String = "nil"
  def success(msg: String) = state = msg
  def failure(msg: String, failer: Failer) = {
    state = msg
    failer.fail
  }
}

trait Failer {
  def fail
}

class FailerImpl extends Failer {
  def fail = throw new RuntimeException("expected")
}

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



