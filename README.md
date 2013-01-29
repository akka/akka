akka-osgi-sample   :  Clustered DiningHakker
================
This project may be used to test akka bundles in OSGi Frameworks. The build tool (sbt for the moment) provide scripts to run in an OSGi Framework (Karaf only for the moment) a version of the DiningHakkers that runs on several nodes unsing the akka-cluster module.

## Bundle overview

This project provides three Osgi Bundles
 - api  providing an API for the Service exposed by the core and used by the command
 - core  implementing the whole logic: clustered connections, Hakkers, ChopSticks. It finally provide an ActorRef of one created Hakker
 - command use a service to get a Hakker (ActorRef) with its position around the table

Two modules that provision the project into the Karaf OSGi container for experimentation and integration testing:
 - assembly-features  defines the karaf "feature" that allows Karaf to provision the bundles
 - assembly-dist  creates a distribution tar.gz and zip file containing the configured Karaf runtime

## How to use it

### Setup with sbt  --  TODO update this for latest Karaf provisioning changes and other Maven build changes
just run:
```bash
sbt clean
sbt package
sbt osgi-bundle
sbt osgi-prepare
```

### Setup with Maven
```bash
mvn clean install
```

The assembly-dist/target/ directory will now contain a tar.gz file that contains a pre-configured Karaf runtime.
This can be extracted to any location, and bin/karaf executed. The provided karaf.sh script automates this.

### Run
Extract the OSGi Framework from the tar.gz described above into any location, or run:
``./karaf.sh``

Execute the framework by running bin ``/karaf`` from inside the extracted directory.

Then try to restart some bundles, to test the stability of the bundles:

``list`` to get the list of the bundles
``restart #bundle_number`` to restart the bundle using its ID
``exit`` or CTRL-D to exit the Karaf console

Depending on the akka version you're using, you may need to modify the core bundle when deploying on a second machine, to set it's akka.remote.netty.hostname in the application.conf.

# TODO: The VM ips below are specific to one environment, make this more general
The configuration is set to start the fist node on localhost, and to start other nodes on vm running on the same machine for tests (localhost and first vm ip would be 192.168.229.128, but it may differ according to your configuration.
