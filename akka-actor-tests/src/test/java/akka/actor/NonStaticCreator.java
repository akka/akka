package akka.actor;

import akka.japi.Creator;

public class NonStaticCreator implements Creator<UntypedActor> {
    @Override
    public UntypedActor create() throws Exception {
        return null;
    }
}
