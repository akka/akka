/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed

import akka.typed.ScalaProcess._
import akka.typed.patterns.Receptionist._
import scala.concurrent.duration._
import akka.typed.AskPattern._
import org.scalatest.Succeeded
import akka.actor.InvalidActorNameException
import akka.Done
import akka.typed.Effect._
import java.util.concurrent.TimeoutException
import org.scalatest.prop.PropertyChecks
import scala.collection.immutable.TreeSet
import scala.util.Random

object ProcessSpec {

  sealed abstract class RequestService extends ServiceKey[Request]
  object RequestService extends RequestService

  case class Request(req: String, replyTo: ActorRef[Response])
  case class Response(res: String)

  sealed abstract class LoginService extends ServiceKey[Login]
  object LoginService extends LoginService

  case class Login(replyTo: ActorRef[AuthResult])
  sealed trait AuthResult
  case object AuthRejected extends AuthResult
  case class AuthSuccess(next: ActorRef[Store]) extends AuthResult

  sealed trait Store
  case class GetData(replyTo: ActorRef[DataResult]) extends Store
  case class DataResult(msg: String)
}

class ProcessSpec extends TypedSpec {
  import ProcessSpec._

  trait CommonTests {
    implicit def system: ActorSystem[TypedSpec.Command]

    def `demonstrates working processes`(): Unit = {

      def register[T](server: ActorRef[T], key: ServiceKey[T]) =
        OpDSL[Registered[T]] { implicit opDSL ⇒
          for {
            self ← opProcessSelf
            sys ← opSystem
          } yield {
            sys.receptionist ! Register(key, server)(self)
            opRead
          }
        }

      val backendStore =
        OpDSL.loopInf[Store] { implicit opDSL ⇒
          for (GetData(replyTo) ← opRead) yield {
            replyTo ! DataResult("yeehah")
          }
        }

      val backend =
        OpDSL[Login] { implicit opDSL ⇒
          for {
            self ← opProcessSelf
            _ ← opCall(register(self, LoginService).named("registerBackend"))
            store ← opFork(backendStore.named("store"))
          } yield OpDSL.loopInf { _ ⇒
            for (Login(replyTo) ← opRead) yield {
              replyTo ! AuthSuccess(store.ref)
            }
          }
        }

      val getBackend =
        OpDSL[Listing[Login]] { implicit opDSL ⇒
          for {
            self ← opProcessSelf
            system ← opSystem
            _ = system.receptionist ! Find(LoginService)(self)
          } yield opRead
        }

      def talkWithBackend(backend: ActorRef[Login], req: Request) =
        OpDSL[AuthResult] { implicit opDSL ⇒
          for {
            self ← opProcessSelf
            _ ← opUnit({ backend ! Login(self) })
            AuthSuccess(store) ← opRead
            data ← opNextStep[DataResult](1, { implicit opDSL ⇒
              for {
                self ← opProcessSelf
                _ = store ! GetData(self)
              } yield opRead
            })
          } req.replyTo ! Response(data.msg)
        }

      val server =
        OpDSL[Request] { implicit op ⇒
          for {
            _ ← opSpawn(backend.named("backend"))
            self ← opProcessSelf
            _ ← retry(1.second, 3, register(self, RequestService).named("register"))
            backend ← retry(1.second, 3, getBackend.named("getBackend"))
          } yield OpDSL.loopInf { _ ⇒
            for (req ← opRead)
              forkAndCancel(5.seconds, talkWithBackend(backend.addresses.head, req).named("worker"))
          }
        }

      sync(runTest("complexOperations") {
        OpDSL[Response] { implicit opDSL ⇒
          for {
            serverRef ← opSpawn(server.named("server").withMailboxCapacity(20))
            self ← opProcessSelf
          } yield OpDSL.loop(2) { _ ⇒
            for {
              _ ← opUnit(serverRef ! MainCmd(Request("hello", self)))
              msg ← opRead
            } msg should ===(Response("yeehah"))
          }.map { results ⇒
            results should ===(List(Succeeded, Succeeded))
          }
        }.withTimeout(3.seconds).toBehavior
      })
    }

    def `must spawn`(): Unit = sync(runTest("spawn") {
      OpDSL[Done] { implicit opDSL ⇒
        for {
          child ← opSpawn(OpDSL[ActorRef[Done]] { implicit opDSL ⇒
            opRead.map(_ ! Done)
          }.named("child").withMailboxCapacity(2))
          self ← opProcessSelf
          _ = child ! MainCmd(self)
          msg ← opRead
        } msg should ===(Done)
      }.withTimeout(3.seconds).toBehavior
    })

    def `must spawn anonymously`(): Unit = sync(runTest("spawnAnonymous") {
      OpDSL[Done] { implicit opDSL ⇒
        for {
          child ← opSpawn(OpDSL[ActorRef[Done]] { implicit opDSL ⇒
            opRead.map(_ ! Done)
          }.withMailboxCapacity(2))
          self ← opProcessSelf
          _ = child ! MainCmd(self)
          msg ← opRead
        } msg should ===(Done)
      }.withTimeout(3.seconds).toBehavior
    })

    def `must watch`(): Unit = sync(runTest("watch") {
      OpDSL[Done] { implicit opDSL ⇒
        for {
          self ← opProcessSelf
          child ← opSpawn(opUnit(()).named("unit"))
          _ ← opWatch(child, Done, self)
        } opRead
      }.withTimeout(3.seconds).toBehavior
    })

    def `must watch and report failure`(): Unit = sync(runTest("watch") {
      OpDSL[Throwable] { implicit opDSL ⇒
        for {
          self ← opProcessSelf
          filter = muteExpectedException[TimeoutException](occurrences = 1)
          child ← opSpawn(opRead.withTimeout(10.millis))
          _ ← opWatch(child, null, self, Some(_))
          thr ← opRead
        } yield {
          thr shouldBe a[TimeoutException]
          filter.awaitDone(100.millis)
        }
      }.withTimeout(3.seconds).toBehavior
    })

    def `must unwatch`(): Unit = sync(runTest("unwatch") {
      OpDSL[String] { implicit opDSL ⇒
        for {
          self ← opProcessSelf
          child ← opSpawn(opUnit(()).named("unit"))
          cancellable ← opWatch(child, "dead", self)
          _ ← opSchedule(50.millis, "alive", self)
          msg ← { cancellable.cancel(); opRead }
        } msg should ===("alive")
      }.withTimeout(3.seconds).toBehavior
    })

    def `must respect timeouts`(): Unit = sync(runTest("timeout") {
      OpDSL[Done] { implicit opDSL ⇒
        for {
          self ← opProcessSelf
          filter = muteExpectedException[TimeoutException](occurrences = 1)
          child ← opSpawn(opRead.named("read").withTimeout(10.millis))
          _ ← opWatch(child, Done, self)
          _ ← opRead
        } filter.awaitDone(100.millis)
      }.withTimeout(3.seconds).toBehavior
    })

    def `must cancel timeouts`(): Unit = sync(runTest("timeout") {
      val childProc = OpDSL[String] { implicit opDSL ⇒
        for {
          self ← opProcessSelf
          _ ← opFork(OpDSL[String] { _ ⇒ self ! ""; opRead }.named("read").withTimeout(1.second))
        } opRead
      }.named("child").withTimeout(100.millis)

      OpDSL[Done] { implicit opDSL ⇒
        for {
          self ← opProcessSelf
          start = Deadline.now
          filter = muteExpectedException[TimeoutException](occurrences = 1)
          child ← opSpawn(childProc)
          _ ← opWatch(child, Done, self)
          _ ← opRead
        } yield {
          // weird: without this I get diverging implicits on the `>`
          import FiniteDuration.FiniteDurationIsOrdered
          (Deadline.now - start) should be > 1.second
          filter.awaitDone(100.millis)
        }
      }.withTimeout(3.seconds).toBehavior
    })

    def `must name process refs appropriately`(): Unit = sync(runTest("naming") {
      OpDSL[Done] { implicit opDSL ⇒
        opProcessSelf.foreach { self ⇒
          val name = self.path.name
          withClue(s" name=$name") {
            name.substring(0, 1) should ===("$")
            name.substring(name.length - 5) should ===("-read")
          }
        }
      }.named("read").toBehavior
    })

    // TODO dropping messages on a subactor ref

    // TODO dropping messages on the main ref including warning when dropping Traversals (or better: make it robust)
  }

  object `A ProcessDSL (native)` extends CommonTests with NativeSystem {

    def `must reject invalid process names early`(): Unit = {
      a[InvalidActorNameException] mustBe thrownBy {
        opRead(null).named("$hello")
      }
      a[InvalidActorNameException] mustBe thrownBy {
        opRead(null).named("hello").copy(name = "$hello")
      }
      a[InvalidActorNameException] mustBe thrownBy {
        Process("$hello", Duration.Inf, 1, null)
      }
    }

    def `must name process refs appropriately (EffectfulActorContext)`(): Unit = {
      val ctx = new EffectfulActorContext("name", OpDSL[ActorRef[Done]] { implicit opDSL ⇒
        opRead
      }.named("read").toBehavior, 1, system)
      val Spawned(name) = ctx.getEffect()
      withClue(s" name=$name") {
        name.substring(0, 1) should ===("$")
        name.substring(name.length - 5) should ===("-read")
      }
      ctx.getAllEffects() should ===(Nil)
    }

    def `must read`(): Unit = {
      val ret = Inbox[Done]("readRet")
      val ctx = new EffectfulActorContext("read", OpDSL[ActorRef[Done]] { implicit opDSL ⇒
        opRead.foreach(_ ! Done)
      }.named("read").toBehavior, 1, system)

      val Spawned(procName) = ctx.getEffect()
      ctx.hasEffects should ===(false)
      val procInbox = ctx.childInbox[ActorRef[Done]](procName)

      ctx.run(MainCmd(ret.ref))
      procInbox.receiveAll() should ===(List(ret.ref))

      val t = ctx.selfInbox.receiveMsg()
      t match {
        case sub: SubActor[_] ⇒ sub.ref.path.name should ===(procName)
        case other            ⇒ fail(s"expected SubActor, got $other")
      }
      ctx.run(t)
      ctx.getAllEffects() should ===(Nil)
      ctx.selfInbox.receiveAll() should ===(Nil)
      ret.receiveAll() should ===(List(Done))
      ctx.isAlive should ===(false)
    }

    def `must call`(): Unit = {
      val ret = Inbox[Done]("callRet")
      val ctx = new EffectfulActorContext("call", OpDSL[ActorRef[Done]] { implicit opDSL ⇒
        opRead.flatMap(replyTo ⇒ opCall(OpDSL[String] { implicit opDSL ⇒
          opUnit(replyTo ! Done)
        }.named("called")))
      }.named("call").toBehavior, 1, system)

      val Spawned(procName) = ctx.getEffect()
      ctx.hasEffects should ===(false)
      val procInbox = ctx.childInbox[ActorRef[Done]](procName)

      ctx.run(MainCmd(ret.ref))
      procInbox.receiveAll() should ===(List(ret.ref))

      val t = ctx.selfInbox.receiveMsg()
      t match {
        case sub: SubActor[_] ⇒ sub.ref.path.name should ===(procName)
        case other            ⇒ fail(s"expected SubActor, got $other")
      }
      ctx.run(t)
      val Spawned(calledName) = ctx.getEffect()

      ctx.getAllEffects() should ===(Nil)
      ctx.selfInbox.receiveAll() should ===(Nil)
      ret.receiveAll() should ===(List(Done))
      ctx.isAlive should ===(false)
    }

    def `must fork`(): Unit = {
      val ret = Inbox[Done]("callRet")
      val ctx = new EffectfulActorContext("call", OpDSL[ActorRef[Done]] { implicit opDSL ⇒
        opFork(opRead.map(_ ! Done).named("forkee"))
          .map { sub ⇒
            opRead.map(sub.ref ! _)
          }
      }.named("call").toBehavior, 1, system)

      val Spawned(procName) = ctx.getEffect()
      val procInbox = ctx.childInbox[ActorRef[Done]](procName)

      val Spawned(forkName) = ctx.getEffect()
      val forkInbox = ctx.childInbox[ActorRef[Done]](forkName)
      ctx.hasEffects should ===(false)

      ctx.run(MainCmd(ret.ref))
      procInbox.receiveAll() should ===(List(ret.ref))
      ctx.getAllEffects() should ===(Nil)

      val t1 = ctx.selfInbox.receiveMsg()
      t1 match {
        case sub: SubActor[_] ⇒ sub.ref.path.name should ===(procName)
        case other            ⇒ fail(s"expected SubActor, got $other")
      }

      ctx.run(t1)
      forkInbox.receiveAll() should ===(List(ret.ref))
      ctx.getAllEffects() should ===(Nil)

      val t2 = ctx.selfInbox.receiveMsg()
      t2 match {
        case sub: SubActor[_] ⇒ sub.ref.path.name should ===(forkName)
        case other            ⇒ fail(s"expected SubActor, got $other")
      }

      ctx.run(t2)
      ctx.getAllEffects() should ===(Nil)
      ctx.selfInbox.receiveAll() should ===(Nil)
      ret.receiveAll() should ===(List(Done))
      ctx.isAlive should ===(false)
    }

    def `must return all the things`(): Unit = {
      case class Info(sys: ActorSystem[Nothing], proc: ActorRef[Nothing], actor: ActorRef[Nothing], value: Int)
      val ret = Inbox[Info]("thingsRet")
      val ctx = new EffectfulActorContext("things", OpDSL[ActorRef[Done]] { implicit opDSL ⇒
        for {
          sys ← opSystem
          proc ← opProcessSelf
          actor ← opActorSelf
          value ← opUnit(42)
        } ret.ref ! Info(sys, proc, actor, value)
      }.named("things").toBehavior, 1, system)

      val Spawned(procName) = ctx.getEffect()
      ctx.hasEffects should ===(false)
      ctx.isAlive should ===(false)

      val Info(sys, proc, actor, value) = ret.receiveMsg()
      ret.hasMessages should ===(false)
      sys should ===(system)
      proc.path.name should ===(procName)
      actor.path should ===(proc.path.parent)
      value should ===(42)
    }

    def `must filter`(): Unit = {
      val ctx = new EffectfulActorContext("filter", OpDSL[String] { implicit opDSL ⇒
        for {
          self ← opProcessSelf
          if false
        } opRead
      }.toBehavior, 1, system)

      val Spawned(procName) = ctx.getEffect()
      ctx.hasEffects should ===(false)
      ctx.isAlive should ===(false)
    }

    def `must filter across call`(): Unit = {
      val ctx = new EffectfulActorContext("filter", OpDSL[String] { implicit opDSL ⇒
        val callee =
          for {
            self ← opProcessSelf
            if false
          } opRead

        for {
          _ ← opCall(callee.named("callee"))
        } opRead
      }.toBehavior, 1, system)

      val Spawned(procName) = ctx.getEffect()
      val Spawned(calleeName) = ctx.getEffect()
      calleeName should endWith("-callee")
      ctx.hasEffects should ===(false)
      ctx.isAlive should ===(false)
    }

    def `must filter across call with replacement value`(): Unit = {
      var received: String = null
      val ctx = new EffectfulActorContext("filter", OpDSL[String] { implicit opDSL ⇒
        val callee =
          for {
            self ← opProcessSelf
            if false
          } opRead

        for {
          result ← opCall(callee.named("callee"), Some("hello"))
        } yield {
          received = result
          opRead
        }
      }.toBehavior, 1, system)

      val Spawned(_) = ctx.getEffect()
      val Spawned(calleeName) = ctx.getEffect()
      calleeName should endWith("-callee")
      ctx.hasEffects should ===(false)
      ctx.isAlive should ===(true)
      received should ===("hello")
    }

    def `must cleanup at the right times`(): Unit = {
      var calls = List.empty[Int]
      def call(n: Int): Unit = calls ::= n

      val ctx = new EffectfulActorContext("cleanup", OpDSL[String] { implicit opDSL ⇒
        (for {
          _ ← opProcessSelf
          _ = call(0)
          _ ← opCleanup(() ⇒ call(1))
          _ ← opUnit(call(2))
        } opCleanup(() ⇒ call(3))
        ).foreach { msg ⇒
          msg should ===(Done)
          call(4)
        }
      }.toBehavior, 1, system)

      val Spawned(_) = ctx.getEffect()
      ctx.getAllEffects() should ===(Nil)
      ctx.isAlive should ===(false)
      calls.reverse should ===(List(0, 2, 3, 1, 4))
    }

    def `must cleanup when short-circuiting`(): Unit = {
      var calls = List.empty[Int]
      def call(n: Int): Unit = calls ::= n

      val ctx = new EffectfulActorContext("cleanup", OpDSL[String] { implicit opDSL ⇒
        val callee =
          for {
            _ ← opProcessSelf
            _ ← opUnit(call(10))
            _ ← opCleanup(() ⇒ call(11))
            if false
          } call(12)

        (for {
          _ ← opProcessSelf
          _ = call(0)
          _ ← opCleanup(() ⇒ call(1))
          _ ← opCall(callee.named("callee"))
        } opCleanup(() ⇒ call(3))
        ).foreach { _ ⇒
          call(4)
        }
      }.toBehavior, 1, system)

      val Spawned(_) = ctx.getEffect()
      val Spawned(calleeName) = ctx.getEffect()
      calleeName should endWith("-callee")
      ctx.getAllEffects() should ===(Nil)
      ctx.isAlive should ===(false)
      calls.reverse should ===(List(0, 10, 11, 1))
    }

    def `must cleanup when short-circuiting with replacement`(): Unit = {
      var calls = List.empty[Int]
      def call(n: Int): Unit = calls ::= n

      val ctx = new EffectfulActorContext("cleanup", OpDSL[String] { implicit opDSL ⇒
        val callee =
          for {
            _ ← opProcessSelf
            _ ← opUnit(call(10))
            _ ← opCleanup(() ⇒ call(11))
            _ ← opCleanup(() ⇒ call(12))
            if false
          } call(13)

        (for {
          _ ← opProcessSelf
          _ = call(0)
          _ ← opCleanup(() ⇒ call(1))
          _ ← opCall(callee.named("callee"), Some("hello"))
        } opCleanup(() ⇒ call(3))
        ).foreach { msg ⇒
          msg should ===(Done)
          call(4)
        }
      }.toBehavior, 1, system)

      val Spawned(_) = ctx.getEffect()
      val Spawned(calleeName) = ctx.getEffect()
      calleeName should endWith("-callee")
      ctx.getAllEffects() should ===(Nil)
      ctx.isAlive should ===(false)
      calls.reverse should ===(List(0, 10, 12, 11, 3, 1, 4))
    }

    def `must cleanup at the right times when failing in cleanup`(): Unit = {
      var calls = List.empty[Int]
      def call(n: Int): Unit = calls ::= n

      val ctx = new EffectfulActorContext("cleanup", OpDSL[String] { implicit opDSL ⇒
        (for {
          _ ← opCleanup(() ⇒ call(0))
          _ ← opCleanup(() ⇒ call(1))
          _ ← opCleanup(() ⇒ throw new Exception("expected"))
          _ ← opRead
        } opCleanup(() ⇒ call(3))
        ).foreach { _ ⇒
          call(4)
        }
      }.toBehavior, 1, system)

      val Spawned(mainName) = ctx.getEffect()
      ctx.getAllEffects() should ===(Nil)

      ctx.run(MainCmd(""))
      ctx.childInbox[String](mainName).receiveAll() should ===(List(""))
      val t = ctx.selfInbox.receiveMsg()
      a[Exception] shouldBe thrownBy {
        ctx.run(t)
      }
      calls.reverse should ===(List(3))

      ctx.signal(PostStop)

      ctx.getAllEffects() should ===(Nil)
      calls.reverse should ===(List(3, 1, 0))
    }

    def `must cleanup at the right times when failing somewhere else`(): Unit = {
      var calls = List.empty[Int]
      def call(n: Int): Unit = calls ::= n

      val ctx = new EffectfulActorContext("cleanup", OpDSL[String] { implicit opDSL ⇒
        for {
          _ ← opFork(
            (for {
              _ ← opCleanup(() ⇒ call(0))
              _ ← opCleanup(() ⇒ call(1))
            } opRead).named("fork"))
          _ ← opRead
        } throw new Exception("expected")
      }.toBehavior, 1, system)

      val Spawned(mainName) = ctx.getEffect()
      val Spawned(forkName) = ctx.getEffect()
      forkName should endWith("-fork")
      ctx.getAllEffects() should ===(Nil)

      ctx.run(MainCmd(""))
      ctx.childInbox[String](mainName).receiveAll() should ===(List(""))
      val t = ctx.selfInbox.receiveMsg()
      a[Exception] shouldBe thrownBy {
        ctx.run(t)
      }
      calls.reverse should ===(List())

      ctx.signal(PostStop)

      ctx.getAllEffects() should ===(Nil)
      calls.reverse should ===(List(1, 0))
    }

    def `must handle ephemeral state`(): Unit = {
      case class Add(num: Int)
      object Key extends StateKey[Int] {
        type Event = Add
        def initial = 0
        def apply(s: Int, ev: Add) = s + ev.num
        def clazz = classOf[Add]
      }

      var values = List.empty[Int]
      def publish(n: Int): Unit = values ::= n

      val ctx = new EffectfulActorContext("state", OpDSL[String] { implicit opDSL ⇒
        for {
          i1 ← opUpdateState(Key)(i ⇒ { publish(i); List(Add(2)) → 5 })
          _ = publish(i1)
          i2 ← opUpdateAndReadState(Key)(i ⇒ { publish(i); List(Add(2)) })
          _ = publish(i2)
          Done ← opForgetState(Key)
          i3 ← opReadState(Key)
        } publish(i3)
      }.toBehavior, 1, system)

      val Spawned(_) = ctx.getEffect()
      ctx.getAllEffects() should ===(Nil)
      ctx.isAlive should ===(false)
      values.reverse should ===(List(0, 5, 2, 4, 0))
    }

  }

  object `A ProcessDSL (adapted)` extends CommonTests with AdaptedSystem

  object `A TimeoutOrdering` extends PropertyChecks {

    def `must sort correctly`(): Unit = {
      forAll { (l: List[Int]) ⇒
        val offsets = (TreeSet.empty[Int] ++ l.filterNot(_ == 1)).toVector
        val deadlines = offsets.map(o ⇒ Deadline((Long.MaxValue + o).nanos))
        val mapping = deadlines.zip(offsets).toMap
        val shuffled = Random.shuffle(deadlines)
        val sorted = TreeSet.empty(internal.ProcessInterpreter.timeoutOrdering) ++ shuffled
        withClue(s" mapping=$mapping shuffled=$shuffled sorted=$sorted") {
          sorted.toVector.map(mapping) should ===(offsets)
        }
      }
    }

  }

}
