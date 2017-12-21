# Cluster

TODO

## Dependency

sbt
:   @@@vars
    ```
    "com.typesafe.akka" %% "akka-cluster-typed" % "$akka.version$"
    ```
    @@@

Gradle
:   @@@vars
    ```
    dependencies {
      compile group: 'com.typesafe.akka', name: 'akka-cluster-typed_2.11', version: '$akka.version$'
    }
    ```
    @@@

Maven
:   @@@vars
    ```
    <dependency>
      <groupId>com.typesafe.akka</groupId>
      <artifactId>akka-cluster-typed_$scala.binary_version$</artifactId>
      <version>$akka.version$</version>
    </dependency>
    ```
    @@@