Guice Integration
=================

Module stability: **STABLE**

All Typed Actors support dependency injection using `Guice <http://code.google.com/p/google-guice/>`_ annotations (such as ‘@Inject’ etc.).
The ‘TypedActorManager’ class understands Guice and will do the wiring for you.

External Guice modules
----------------------

You can also plug in external Guice modules and have not-actors wired up as part of the configuration.
Here is an example:

.. code-block:: java

  import static akka.config.Supervision.*;
  import static akka.config.SupervisorConfig.*;

  TypedActorConfigurator manager = new TypedActorConfigurator();

  manager.configure(
    new AllForOneStrategy(new Class[]{Exception.class}, 3, 1000),
      new SuperviseTypedActor[] {
        new SuperviseTypedActor(
          Foo.class,
          FooImpl.class,
          temporary(),
          1000),
        new SuperviseTypedActor(
          Bar.class,
          BarImpl.class,
          permanent(),
          1000)
    })
  .addExternalGuiceModule(new AbstractModule() {
    protected void configure() {
      bind(Ext.class).to(ExtImpl.class).in(Scopes.SINGLETON);
    }})
  .configure()
  .inject()
  .supervise();

Retrieve the external Guice dependency
--------------------------------------

The external dependency can be retrieved like this:

.. code-block:: java

  Ext ext = manager.getExternalDependency(Ext.class);

