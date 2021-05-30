---
project.description: Multi JVM testing of distributed systems built with Akka.
---
# Multi JVM Testing

Supports running applications (objects with main methods) and ScalaTest tests in multiple JVMs at the same time.
Useful for integration testing where multiple systems communicate with each other.

## Setup

The multi-JVM testing is an sbt plugin that you can find at [https://github.com/sbt/sbt-multi-jvm](https://github.com/sbt/sbt-multi-jvm).
To configure it in your project you should do the following steps:

1. Add it as a plugin by adding the following to your project/plugins.sbt:

    @@snip [plugins.sbt](/project/plugins.sbt) { #sbt-multi-jvm }

2. Add multi-JVM testing to `build.sbt` or `project/Build.scala` by enabling `MultiJvmPlugin` and 
setting the `MultiJvm` config.

    ```none
    lazy val root = (project in file("."))
      .enablePlugins(MultiJvmPlugin)
      .configs(MultiJvm)
    ```
    
**Please note** that by default MultiJvm test sources are located in `src/multi-jvm/...`, 
and not in `src/test/...`.

## Running tests

The multi-JVM tasks are similar to the normal tasks: `test`, `testOnly`,
and `run`, but are under the `multi-jvm` configuration.

So in Akka, to run all the multi-JVM tests in the akka-remote project use (at
the sbt prompt):

```none
akka-remote-tests/multi-jvm:test
```

Or one can change to the `akka-remote-tests` project first, and then run the
tests:

```none
project akka-remote-tests
multi-jvm:test
```

To run individual tests use `testOnly`:

```none
multi-jvm:testOnly akka.remote.RandomRoutedRemoteActor
```

More than one test name can be listed to run multiple specific
tests. Tab-completion in sbt makes it easy to complete the test names.

It's also possible to specify JVM options with `testOnly` by including those
options after the test names and `--`. For example:

```none
multi-jvm:testOnly akka.remote.RandomRoutedRemoteActor -- -Dsome.option=something
```

## Creating application tests

The tests are discovered, and combined, through a naming convention. MultiJvm test sources
are located in `src/multi-jvm/...`. A test is named with the following pattern:

```none
{TestName}MultiJvm{NodeName}
```

That is, each test has `MultiJvm` in the middle of its name. The part before
it groups together tests/applications under a single `TestName` that will run
together. The part after, the `NodeName`, is a distinguishing name for each
forked JVM.

So to create a 3-node test called `Sample`, you can create three applications
like the following:

```scala
package sample

object SampleMultiJvmNode1 {
  def main(args: Array[String]) {
    println("Hello from node 1")
  }
}

object SampleMultiJvmNode2 {
  def main(args: Array[String]) {
    println("Hello from node 2")
  }
}

object SampleMultiJvmNode3 {
  def main(args: Array[String]) {
    println("Hello from node 3")
  }
}
```

When you call `multi-jvm:run sample.Sample` at the sbt prompt, three JVMs will be
spawned, one for each node. It will look like this:

```none
> multi-jvm:run sample.Sample
...
[info] * sample.Sample
[JVM-1] Hello from node 1
[JVM-2] Hello from node 2
[JVM-3] Hello from node 3
[success] Total time: ...
```

## Changing Defaults

You can specify JVM options for the forked JVMs:

```
jvmOptions in MultiJvm := Seq("-Xmx256M")
```

You can change the name of the multi-JVM test source directory by adding the following
configuration to your project:

```none
unmanagedSourceDirectories in MultiJvm :=
   Seq(baseDirectory(_ / "src/some_directory_here")).join.value
```

You can change what the `MultiJvm` identifier is. For example, to change it to
`ClusterTest` use the `multiJvmMarker` setting:

```none
multiJvmMarker in MultiJvm := "ClusterTest"
```

Your tests should now be named `{TestName}ClusterTest{NodeName}`.

## Configuration of the JVM instances

You can define specific JVM options for each of the spawned JVMs. You do that by creating
a file named after the node in the test with suffix `.opts` and put them in the same
directory as the test.

For example, to feed the JVM options `-Dakka.remote.port=9991` and `-Xmx256m` to the `SampleMultiJvmNode1`
let's create three `*.opts` files and add the options to them. Separate multiple options with
space. 

`SampleMultiJvmNode1.opts`:

```
-Dakka.remote.port=9991 -Xmx256m
```

`SampleMultiJvmNode2.opts`:

```
-Dakka.remote.port=9992 -Xmx256m
```

`SampleMultiJvmNode3.opts`:

```
-Dakka.remote.port=9993 -Xmx256m
```

## ScalaTest

There is also support for creating ScalaTest tests rather than applications. To
do this use the same naming convention as above, but create ScalaTest suites
rather than objects with main methods. You need to have ScalaTest on the
classpath. Here is a similar example to the one above but using ScalaTest:

```scala
package sample

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.must.Matchers

class SpecMultiJvmNode1 extends AnyWordSpec with Matchers {
  "A node" should {
    "be able to say hello" in {
      val message = "Hello from node 1"
      message must be("Hello from node 1")
    }
  }
}

class SpecMultiJvmNode2 extends AnyWordSpec with Matchers {
  "A node" should {
    "be able to say hello" in {
      val message = "Hello from node 2"
      message must be("Hello from node 2")
    }
  }
}
```

To run just these tests you would call `multi-jvm:testOnly sample.Spec` at
the sbt prompt.

## Multi Node Additions

There has also been some additions made to the `SbtMultiJvm` plugin to accommodate the
@ref:[may change](common/may-change.md) module @ref:[multi node testing](multi-node-testing.md),
described in that section.

## Example project

@extref[Cluster example project](samples:akka-samples-cluster-scala)
is an example project that can be downloaded, and with instructions of how to run.

This project illustrates Cluster features and also includes Multi JVM Testing with the `sbt-multi-jvm` plugin.
