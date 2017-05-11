# Quickstart

After all this introduction, we are ready to build our first actor system. We will do so in three chapters.
This first chapter will help you to set up your project, tools and have a simple "Hello World" demo running.
We will keep this section to a bare minimum and then extend the sample application in the next chapter. Finally, we review
what we have learned in the third chapter, looking in detail how the pieces work and fit together.

> Our goal in this chapter is to set up a working environment for you, create an application that starts up and stops
an ActorSystem and create an actor which we will test.

As the very first thing, we need to make sure that we can compile our project and have a working IDE setup to be
able to edit code comfortably. Depending on preference for build tool and IDE there are multiple paths that can
be followed.

## Setting Up the Build

Depending on the choice of build tool, we need to set up the layout for our project and tell the build tool about our
dependencies (libraries that we want to use). There are common things to care for independently of our choice
of build tool:

* Declare `akka-actor` as a dependency. This is the core library of Akka, the one we are now learning
* Declare `akka-testkit` as a dependency. This is a toolkit for testing Akka applications. Without this
  dependency we will have a hard time testing actors.
* **Use the latest Akka version for new projects (unless there are additional constraints)!**  
* **Don’t mix Akka versions! You are free to use any Akka version in your project, but you must use
    that version for all Akka core projects** In this sample it means that `akka-actor` and `akka-testkit` should
    always be the same version.

### sbt

If the choice is to use [sbt](http://www.scala-sbt.org/), the first step is to set up the directory structure for the project. sbt follows the
directory layout standard of Maven. Usually, this means to start with the following directories and files:

* `/src`
  * `/main` (this is where main, production classes live)
    * `/scala` (this is where Scala classes live)
    * `/java` (this is where Java classes live if Java is to be used)
    * `/resources` (this is where non-source files live which are required on the classpath.
    A typical example is application.conf which contains the configuration for the application.
    We will cover this in detail in CONFIG-SECTION)
  * `/test`
    * `/scala` (this is where Scala test and test helper classes live)
    * `/java` (this is where Java test and test helper classes live if Java is to be used)
    * `/resources` (this is where non-source files live which are required on the classpath to run tests.
    Typically this contains an application.conf that overrides the default configuration for tests. We will
    cover this in detail in CONFIG-SECTION)
  * `project`
    * `build.properties` ()
* `build.sbt`

For example, if there is a Scala class `TestClass` in package `com.example.foo` then it should go in
`/src/main/scala/com/example/foo/TestClass.scala`.

The file `build.sbt` contains the necessary information for sbt about the project metadata and dependencies.
For our sample project, this file should contain the following:

@@@vars
```scala
// build.sbt

name := "intro-akka"
organization := "intro-akka.organization"
version := "0.1-SNAPSHOT"

scalaVersion := $scala.version$
val AkkaVersion = $akka.version$

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % AkkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % "test"
```
@@@

This simple file sets up first the project metadata ( _name_ and _organization_; we just picked a sample one here).
Thereafter we set up the version of the Scala compiler we use, then set a variable with the Akka version we intend to
use. Always strive to use the latest version.

As the last step, we add two dependencies, `akka-actor` and `akka-testkit`. Note that we used the `AkkaVersion`
variable for both dependencies, ensuring that versions are not accidentally mixed and kept in sync when upgrading.

Finally, check that everything works by running `sbt update` from the base directory of your project
(where the `build.sbt` file is).

## Setting up Your IDE

If [IDEA](https://www.jetbrains.com/idea/) is the choice of IDE, it has flexible means to import project either manually created by one of the
previous steps from @ref:[setting up the build](#setting-up-the-build), or to let IDEA create it for you. Depending on your build tool,
there are detailed steps in the IDEA documentation:

sbt
:  [https://www.jetbrains.com/help/idea/2016.2/getting-started-with-sbt.html](https://www.jetbrains.com/help/idea/2016.2/getting-started-with-sbt.html)

## Building the First Application

Akka applications are simply Scala or Java applications. To get an actor system up and running there is no need to set up any container, application server, etc. Instead, all that is needed is a class with a proper `main` method that starts and stops an actor
system. The pieces of the puzzle that we need to put together are: What is an actor system and why do I need one?
How can we start and stop it? Why do we need to stop it?

In Akka, actors belong to actor systems, which are instances of the type `ActorSystem`. This class acts as a
resource container which holds among others:

* Configuration shared by all actors in that system.
* A pool of threads that will execute actors that are ready to process messages.
* A dispatcher mechanism that dynamically assigns actors to threads of the pool.
* A scheduler used for timer-related tasks.

The `ActorSystem` manages its actors and runs them in the background on its encapsulated thread pool.
This means that is must be explicitly shut down, otherwise, these threads keep running and the JVM does
not exit (by default threads created by ActorSystem are not daemon threads; see the JDK documentation on
more details on [daemon or non-daemon threads](https://docs.oracle.com/javase/8/docs/api/java/lang/Thread.html)).
The usual pattern is to have your system set up to stop on external signal (i.e. user pressing ENTER in the console).

Once there is an `ActorSystem` we can populate it with actors. This is done by using the `actorOf` method. The `actorOf` method expects a `Props` instance and the name of the actor to be created. You can think of the `Props` as a configuration value for what actor to create and how it should be created. Creating an actor with the `actorOf` method will return an `ActorRef` instance. Think of the `ActorRef` as a unique address with which it is possible to message the actor instance. The `ActorRef` object contains a few methods with which you can send messages to the actor instance. One of them is called `tell`, or in the Scala case simply `!` (bang), and this method is used in the example here below. Calling the `!` method is an asynchronous operation and it instructs Akka to send a message to the actor instance that is uniquely identified by the actor reference.

@@snip [HelloWorldApp.scala](../../../../test/scala/quickstart/HelloWorldApp.scala) { #create-send }

Before we can create any actor in the actor system we must define one first. Luckily, creating actors in Akka is quite simple! Just have your actor class extend `akka.actor.Actor` and override the method `receive: Receive` and you are good to go. As for our `HelloWorldActor` class, it extends `Actor` and overrides the `receive` method as per the requirement. Our implementation of the `receive` method expects messages of type `String`. For every `String` message it receives it will print "Hello " and the value of the `String`. Since the message we send in the main class is "World" we expect the string "Hello World" to be printed when running the application.

@@snip [HelloWorldApp.scala](../../../../test/scala/quickstart/HelloWorldApp.scala) { #actor-impl }

Here is the full example:

@@snip [HelloWorldApp.scala](../../../../test/scala/quickstart/HelloWorldApp.scala) { #full-example }

Now that you have seen the basics of an Akka application it is time to dive deeper.
