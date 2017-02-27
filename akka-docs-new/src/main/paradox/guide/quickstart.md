# Your First Akka Application - Hello World

After all this introduction, we are ready to build our first actor system. We will do this in three chapters. 
In this first chapter we will help you to set up your project and tools and have a simple "Hello World" demo running. 
We will keep this to the bare minimum and then extend the sample application in the next chapter. Finally we review 
what we have learned in the third chapter, looking in detail how the pieces work and fit together.

> Our goal in this chapter is to set up a working environment for you, create an application that starts up and stops 
an ActorSystem and create an actor which we will test.

As the very first thing, we need to make sure that we can compile our project and have a working IDE setup to be 
able to edit code comfortably. Depending on your preference for build tool and IDE there are multiple paths you can 
follow here.
 
## Setting up the build

Depending on your build tool, you need to set up the layout for your project and tell the build tool about your 
dependencies (libraries that you want to use). There are common things to care for independently of your choice 
of build tool:

* Declare `akka-actor` as a dependency. This is the core library of Akka, the one we are now learning
* Declare `akka-testkit` as a dependency. This is a toolkit for testing Akka applications. Without this 
  dependency you will have a hard time testing actors.
* **Use the latest Akka version for new projects (unless you have additional constraints)!**  
* **Don’t mix Akka versions! You are free to use any Akka version in your project, but you must use 
    that version for all Akka core projects** In this sample it means that `akka-actor` and `akka-testkit` should 
    always be of the same version.

### sbt

If you plan to use sbt, your first step is to set up the directory structure for the project. sbt follows the 
directory layout standard of Maven. Usually, you want to start with the following directories and files:

* `/src`
  * `/main` (this is where main, production classes live)
    * `/scala` (this is where Scala classes live)
    * `/java` (this is where Java classes live if you plan to use Java as well)
    * `/resources` (this is where non-source files live which are required on the classpath. 
    Typical example is application.conf which contains the configuration for your application. 
    We will cover this in detail in CONFIG-SECTION)
  * `/test`
    * `/scala` (this is where Scala test and test helper classes live)
    * `/java` (this is where Java test and test helper classes live if you plan to use Java as well)
    * `/resources` (this is where non-source files live which are required on the classpath to run tests. 
    Typically this contains an application.conf that overrides the default configuration for tests. We will 
    cover this in detail in CONFIG-SECTION)
  * `project`
    * `build.properties` ()
* `build.sbt`

For example, if you have a Scala class `TestClass` in package `com.example.foo` then should go in 
`/src/main/scala/com/example/foo/TestClass.scala`.

The file `build.sbt` contains the necessary information for sbt about your project metadata and dependencies. 
For our sample project, this file should contain the following:

```scala
// build.sbt

name := "intro-akka"
organization := "intro-akka.organization"
version := "0.1-SNAPSHOT"

scalaVersion := "2.11.8"
val AkkaVersion = "2.4.12"

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % AkkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % "test"
```

This simple file sets up first the project metadata (_name_ and _organization_; we just picked a sample one here). 
Then we set up the version of the scala compiler we use, then set a variable with the Akka version we intend to
use (always try to use the latest). 

As the last step, we add two dependencies, `akka-actor` and `akka-testkit`. Note that we used the `AkkaVersion` 
variable for both dependencies, ensuring that versions are not accidentally mixed and kept in sync when upgrading.

Finally, check that everything works by running `sbt update` from the base directory of your project 
(where the `build.sbt` file is).

## Setting up your IDE

If you go with IDEA, you usually have flexible means to import project either manually created by one of the 
previous steps from Setting up Your Build, or to let it create it for you. Depending on your build tool, 
there are detailed steps in the IDEA documentation

sbt
:  [https://www.jetbrains.com/help/idea/2016.2/getting-started-with-sbt.html](https://www.jetbrains.com/help/idea/2016.2/getting-started-with-sbt.html)

## Building the first application

Akka applications are simply Scala/Java applications, you don’t need to set up any container (application server, etc.) 
to have an actor system running. All we need is a class with a proper `main` method that stops and starts an actor 
system. The pieces of the puzzle that we need to put together are: What is an actor system and why do I need one? 
How can I start and stop it? Why do I need to stop it?

In Akka, actors belong to actor systems, which are instances of the type `ActorSystem`. This class acts as a 
resource container which holds among others

* configuration shared by all actors in that system
* a pool of threads that will execute actors that are ready to process messages
* a dispatcher mechanism that dynamically assigns actors to threads of the pool
* a scheduler used for timer related tasks

The `ActorSystem` manages its actors and runs them in the background on its encapsulated thread pool. 
This means that is must be explicitly shut down, otherwise these threads keep running and the JVM does 
not exit (by default threads created by ActorSystem are not daemon threads; see the JDK documentation on 
more details on [daemon or non-daemon threads](https://docs.oracle.com/javase/8/docs/api/java/lang/Thread.html)). 

This is the usual pattern to have your system set up and stopped on external signal 
(user pressing ENTER in the console):


