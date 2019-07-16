# Mailboxes

## Dependency

Mailboxes are part of core Akka, which means that they are part of the akka-actor-typed dependency:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-actor-typed_$scala.binary_version$"
  version="$akka.version$"
}

## Introduction 

Each actor in Akka has a `Mailbox`, this is where the messages are enqueued before being processed by the actor.

By default an unbounded mailbox is used, this means any number of messages can be enqueued into the mailbox. 

The unbounded mailbox is a convenient default but in a scenario where messages are added to the mailbox faster
than the actor can process them, this can lead to the application running out of memory.
For this reason a bounded mailbox can be specified, the bounded mailbox will pass new messages to `deadletters` when the
mailbox is full.

For advanced use cases it is also possible to defer mailbox selection to config by pointing to a config path.

## Selecting what mailbox is used

To select mailbox for an actor use `MailboxSelector` to create a `Props` instance for spawning your actor:

Scala
:  @@snip [MailboxDocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/MailboxDocSpec.scala) { #select-mailbox }

Java
:  @@snip [MailboxDocTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/MailboxDocTest.java) { #select-mailbox }

`fromConfig` takes an absolute config path to a block defining the dispatcher in the config file like this:

@@snip [MailboxDocSpec.scala](/akka-actor-typed-tests/src/test/resources/mailbox-config-sample.conf) { }

For more details on advanced mailbox config and custom mailbox implementations, see @ref[Classic Mailboxes](../mailboxes.md#builtin-mailbox-implementations).
