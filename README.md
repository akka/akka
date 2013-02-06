akka-osgi-sample   :  Clustered DiningHakkers
================
This project may be used to test akka bundles in OSGi Frameworks. The build tool (sbt for the moment) provide scripts to run in an OSGi Framework (Karaf only for the moment) a version of the DiningHakkers that runs on several nodes using the akka-cluster module.

## Bundle overview

This project provides three Osgi Bundles
 - api  providing an API for the Service exposed by the core and used by the command
 - core  implementing the whole logic: clustered connections, Hakkers, ChopSticks. Finally it provides an ActorRef of one created Hakker
 - command use a service to get a Hakker (ActorRef) with its position around the table

An integration testing module is provided to verify OSGi functionality:
 - integration-test

Two modules that provision the project into the Karaf OSGi container for experimentation and integration testing:
 - assembly-features  defines the karaf "feature" that allows Karaf to provision the bundles
 - assembly-dist  creates a distribution tar.gz and zip file containing the configured Karaf runtime

## How to use it

### Setup with sbt
just run:
```bash
sbt clean
sbt package
sbt osgi-bundle
```
sbt will creates the bundles in each subproject akka-sample/akka-sample-osgi-dining-hakkers/(api, command, core)/target directories. To have integration tests and OSGi environment loaded, please use the Maven build (at least for the moment)
### Setup with Maven
```bash
mvn clean install
```

The assembly-dist/target/ directory will now contain a tar.gz file that contains a pre-configured Karaf runtime.
This can be extracted to any location, and bin/karaf executed. The provided karaf.sh script automates this.

### Run
Extract the OSGi Framework from the tar.gz described above into any location, or run:
``./karaf.sh``

Execute the framework by running  ``bin/karaf`` from inside the extracted directory.

Then try to restart some bundles, to test the stability of the bundles:

``list`` to get the list of the bundles
``restart #bundle_number`` to restart the bundle using its ID
``exit`` or CTRL-D to exit the Karaf console

Depending on the akka version you're using, you may need to modify the core bundle when deploying on a second machine, to set its akka.remote.netty.hostname in the application.conf.
