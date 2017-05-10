# The Obligatory Hello World

The actor based version of the tough problem of printing a
well-known greeting to the console is introduced in a ready to run [Akka Main sample](@exampleCodeService@/akka-samples-main-scala)
together with a tutorial. The source code of this sample can be found in the
[Akka Samples Repository](@samples@/akka-sample-main-scala).

The tutorial illustrates the generic launcher class `akka.Main` which expects only
one command line argument: the class name of the application’s main actor. This
main method will then create the infrastructure needed for running the actors,
start the given main actor and arrange for the whole application to shut down
once the main actor terminates.

There is also a [Gitter8](http://www.foundweekends.org/giter8/) template in the same problem domain
that is named [Hello Akka!](https://github.com/akka/hello-akka.g8).
It describes the basics of Akka in more depth. If you have *sbt* already installed, you can create a project
from this template by running:

```
sbt new akka/hello-akka.g8
```