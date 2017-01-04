/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.persistence;

import akka.persistence.UntypedPersistentActor;

public class PersistenceMultiDocTest {

    //#default-plugins
    abstract class ActorWithDefaultPlugins extends UntypedPersistentActor {
        @Override
        public String persistenceId() { return "123"; }
    }
    //#default-plugins

    //#override-plugins
    abstract class ActorWithOverridePlugins extends UntypedPersistentActor {
        @Override
        public String persistenceId() { return "123"; }
        // Absolute path to the journal plugin configuration entry in the `reference.conf`
        @Override
        public String journalPluginId() { return "akka.persistence.chronicle.journal"; }
        // Absolute path to the snapshot store plugin configuration entry in the `reference.conf`
        @Override
        public String snapshotPluginId() { return "akka.persistence.chronicle.snapshot-store"; }
    }
    //#override-plugins

}
