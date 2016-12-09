# The Obligatory Hello World

The actor based version of the tough problem of printing a
well-known greeting to the console is introduced in a [Lightbend Activator ](http://www.lightbend.com/platform/getstarted)
tutorial named [Akka Main in Java ](http://www.lightbend.com/activator/template/akka-sample-main-java).

The tutorial illustrates the generic launcher class `akka.Main` which expects only
one command line argument: the class name of the application’s main actor. This
main method will then create the infrastructure needed for running the actors,
start the given main actor and arrange for the whole application to shut down
once the main actor terminates.

There is also another [Lightbend Activator ](http://www.lightbend.com/platform/getstarted)
tutorial in the same problem domain that is named [Hello Akka! ](http://www.lightbend.com/activator/template/hello-akka).
It describes the basics of Akka in more depth. 