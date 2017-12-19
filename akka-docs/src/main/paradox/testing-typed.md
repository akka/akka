# Testing 

## Dependency

sbt
:   @@@vars
    ```
    "com.typesafe.akka" %% "akka-testkit-typed" % "$akka.version$"
    ```
    @@@

Gradle
:   @@@vars
    ```
    dependencies {
      compile group: 'com.typesafe.akka', name: 'akka-testkit-typed_2.11', version: '$akka.version$'
    }
    ```
    @@@

Maven
:   @@@vars
    ```
    <dependency>
      <groupId>com.typesafe.akka</groupId>
      <artifactId>akka-testkit-typed_$scala.binary_version$</artifactId>
      <version>$akka.version$</version>
    </dependency>
    ```
    @@@
    
TODO