package akka.http.javadsl.server;

import akka.japi.pf.FI;
import akka.japi.pf.PFBuilder;

public class ExceptionHandlerBuilder {
    private final PFBuilder<Throwable, Route> delegate;
    
    public ExceptionHandlerBuilder() {
        this(new PFBuilder<>());
    }
    
    private ExceptionHandlerBuilder(PFBuilder<Throwable, Route> delegate) {
        this.delegate = delegate;
    }
    
    /**
     * Add a new case statement to this builder.
     *
     * @param type  a type to match the argument against
     * @param apply an action to apply to the argument if the type matches
     * @return a builder with the case statement added
     */
    public <P extends Throwable> ExceptionHandlerBuilder match(final Class<P> type, FI.Apply<P, Route> apply) {
        delegate.match(type, apply);
        return this;
    }

    /**
     * Add a new case statement to this builder.
     *
     * @param type      a type to match the argument against
     * @param predicate a predicate that will be evaluated on the argument if the type matches
     * @param apply     an action to apply to the argument if the type matches and the predicate returns true
     * @return a builder with the case statement added
     */
    public <P extends Throwable> ExceptionHandlerBuilder match(final Class<P> type,
                                     final FI.TypedPredicate<P> predicate,
                                     final FI.Apply<P, Route> apply) {
        delegate.match(type, predicate, apply);
        return this;
    }
    
    /**
     * Add a new case statement to this builder.
     *
     * @param object the object to compare equals with
     * @param apply  an action to apply to the argument if the object compares equal
     * @return a builder with the case statement added
     */
    public <P extends Throwable> ExceptionHandlerBuilder matchEquals(final P object,
                                           final FI.Apply<P, Route> apply) {
        delegate.matchEquals(object, apply);
        return this;
    }
    
    /**
     * Add a new case statement to this builder, that matches any argument.
     *
     * @param apply an action to apply to the argument
     * @return a builder with the case statement added
     */
    public ExceptionHandlerBuilder matchAny(final FI.Apply<Throwable, Route> apply) {
        delegate.matchAny(apply);
        return this;
    }
    
    public ExceptionHandler build() {
        return ExceptionHandler.of(delegate.build());
    }
}
