/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed

class DeploymentConfigSpec extends TypedSpecSetup {

  val dispatcherFirst = DispatcherDefault(MailboxCapacity(666, DispatcherFromConfig("pool")))
  val mailboxFirst = MailboxCapacity(999) withNext dispatcherFirst

  object `A DeploymentConfig` {

    def `must get first dispatcher`(): Unit = {
      dispatcherFirst.firstOrElse[DispatcherSelector](null) should ===(dispatcherFirst)
      mailboxFirst.firstOrElse[DispatcherSelector](null) should ===(dispatcherFirst)
    }

    def `must get first mailbox`(): Unit = {
      dispatcherFirst.firstOrElse[MailboxCapacity](null).capacity should ===(666)
      mailboxFirst.firstOrElse[MailboxCapacity](null).capacity should ===(999)
    }

    def `must get default value`(): Unit = {
      mailboxFirst.firstOrElse[DispatcherFromExecutor](null) should ===(null)
    }

    def `must filter out the right things`(): Unit = {
      val filtered = mailboxFirst.filterNot[DispatcherSelector]
      filtered.firstOrElse[MailboxCapacity](null).capacity should ===(999)
      filtered.firstOrElse[DispatcherSelector](null) should ===(null)
    }

    def `must yield all configs of some type`(): Unit = {
      dispatcherFirst.allOf[DispatcherSelector] should ===(DispatcherDefault() :: DispatcherFromConfig("pool") :: Nil)
      mailboxFirst.allOf[MailboxCapacity] should ===(List(999, 666).map(MailboxCapacity(_)))
    }

  }
}
