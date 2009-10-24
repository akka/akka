package se.scalablesolutions.akka.stm;

import org.multiverse.templates.AbortedException;
import org.multiverse.api.Stm;
import org.multiverse.api.Transaction;
import org.multiverse.api.TransactionStatus;
import org.multiverse.api.exceptions.CommitFailureException;
import org.multiverse.api.exceptions.LoadException;
import org.multiverse.api.exceptions.RetryError;
import org.multiverse.api.exceptions.TooManyRetriesException;
import static org.multiverse.api.GlobalStmInstance.getGlobalStmInstance;
import static org.multiverse.utils.TransactionThreadLocal.getThreadLocalTransaction;
import static org.multiverse.utils.TransactionThreadLocal.setThreadLocalTransaction;

import static java.lang.String.format;
import java.util.logging.Logger;

/**
 * A Template that handles the boilerplate code for transactions. A transaction will be placed if none is available
 * around a section and if all goes right, commits at the end.
 * <p/>
 * example:
 * <pre>
 * new AtomicTemplate(){
 *    Object execute(Transaction t){
 *        queue.push(1);
 *        return null;
 *    }
 * }.execute();
 * </pre>
 * <p/>
 * It could also be that the transaction is retried (e.g. caused by optimistic locking failures). This is also a task
 * for template. In the future this retry behavior will be customizable.
 * <p/>
 * If a transaction already is available on the TransactionThreadLocal, no new transaction is started and essentially
 * the whole AtomicTemplate is ignored.
 * <p/>
 * If no transaction is available on the TransactionThreadLocal, a new one will be created and used during the execution
 * of the AtomicTemplate and will be removed once the AtomicTemplate finishes.
 * <p/>
 * All uncaught throwable's lead to a rollback of the transaction.
 * <p/>
 * AtomicTemplates are not thread-safe to use.
 * <p/>
 * AtomicTemplates can completely work without threadlocals. See the {@link AtomicTemplate#AtomicTemplate(org.multiverse.api.Stm
 * ,String, boolean, boolean, int)} for more information.
 *
 * @author Peter Veentjer
 */
public abstract class AtomicTemplate<E> {

    private final static Logger logger = Logger.getLogger(AtomicTemplate.class.getName());

    private final Stm stm;
    private final boolean ignoreThreadLocalTransaction;
    private final int retryCount;
    private final boolean readonly;
    private int attemptCount;
    private final String familyName;

    /**
     * Creates a new AtomicTemplate that uses the STM stored in the GlobalStm and works the the {@link
     * org.multiverse.utils.TransactionThreadLocal}.
     */
    public AtomicTemplate() {
        this(getGlobalStmInstance());
    }

    public AtomicTemplate(boolean readonly) {
        this(getGlobalStmInstance(), null, false, readonly, Integer.MAX_VALUE);
    }

    /**
     * Creates a new AtomicTemplate using the provided stm. The transaction used is stores/retrieved from the {@link
     * org.multiverse.utils.TransactionThreadLocal}.
     *
     * @param stm the stm to use for transactions.
     * @throws NullPointerException if stm is null.
     */
    public AtomicTemplate(Stm stm) {
        this(stm, null, false, false, Integer.MAX_VALUE);
    }

    public AtomicTemplate(String familyName, boolean readonly, int retryCount) {
        this(getGlobalStmInstance(), familyName, false, readonly, retryCount);
    }

    /**
     * Creates a new AtomicTemplate that uses the provided STM. This method is provided to make Multiverse easy to
     * integrate with environment that don't want to depend on threadlocals.
     *
     * @param stm                          the stm to use for transactions.
     * @param ignoreThreadLocalTransaction true if this Template should completely ignore the ThreadLocalTransaction.
     *                                     This is useful for using the AtomicTemplate in other environments that don't
     *                                     want to depend on threadlocals but do want to use the AtomicTemplate.
     * @throws NullPointerException if stm is null.
     */
    public AtomicTemplate(Stm stm, String familyName, boolean ignoreThreadLocalTransaction, boolean readonly,
                          int retryCount) {
        if (stm == null) {
            throw new NullPointerException();
        }
        if (retryCount < 0) {
            throw new IllegalArgumentException();
        }
        this.stm = stm;
        this.ignoreThreadLocalTransaction = ignoreThreadLocalTransaction;
        this.readonly = readonly;
        this.retryCount = retryCount;
        this.familyName = familyName;
    }

    public String getFamilyName() {
        return familyName;
    }

    /**
     * Returns the current attempt. Value will always be larger than zero and increases everytime the transaction needs
     * to be retried.
     *
     * @return the current attempt count.
     */
    public final int getAttemptCount() {
        return attemptCount;
    }

    /**
     * Returns the number of retries that this AtomicTemplate is allowed to do. The returned value will always be equal
     * or larger than 0.
     *
     * @return the number of retries.
     */
    public final int getRetryCount() {
        return retryCount;
    }

    /**
     * Returns the {@link Stm} used by this AtomicTemplate to execute transactions on.
     *
     * @return the Stm used by this AtomicTemplate.
     */
    public final Stm getStm() {
        return stm;
    }

    /**
     * Check if this AtomicTemplate ignores the ThreadLocalTransaction.
     *
     * @return true if this AtomicTemplate ignores the ThreadLocalTransaction, false otherwise.
     */
    public final boolean isIgnoreThreadLocalTransaction() {
        return ignoreThreadLocalTransaction;
    }

    /**
     * Checks if this AtomicTemplate executes readonly transactions.
     *
     * @return true if it executes readonly transactions, false otherwise.
     */
    public final boolean isReadonly() {
        return readonly;
    }

    /**
     * This is the method can be overridden to do pre-start tasks.
     */
    public void preStart() {
    }

    /**
     * This is the method can be overridden to do post-start tasks.
     * 
     * @param t the transaction used for this execution.
     */
    public void postStart(Transaction t) {
    }

    /**
     * This is the method can be overridden to do pre-commit tasks.
     */
    public void preCommit() {
    }

    /**
     * This is the method can be overridden to do post-commit tasks.
     */
    public void postCommit() {
    }

    /**
     * This is the method that needs to be implemented.
     *
     * @param t the transaction used for this execution.
     * @return the result of the execution.
     *
     * @throws Exception the Exception thrown
     */
    public abstract E execute(Transaction t) throws Exception;

    /**
     * Executes the template.
     *
     * @return the result of the {@link #execute(org.multiverse.api.Transaction)} method.
     *
     * @throws InvisibleCheckedException if a checked exception was thrown while executing the {@link
     *                                   #execute(org.multiverse.api.Transaction)} method.
     * @throws AbortedException          if the exception was explicitly aborted.
     * @throws TooManyRetriesException   if the template retried the transaction too many times. The cause of the last
     *                                   failure (also an exception) is included as cause. So you have some idea where
     *                                   to look for problems
     */
    public final E execute() {
        try {
            return executeChecked();
        } catch (Exception ex) {
            if (ex instanceof RuntimeException) {
                throw (RuntimeException) ex;
            } else {
                throw new AtomicTemplate.InvisibleCheckedException(ex);
            }
        }
    }

    /**
     * Executes the Template and rethrows the checked exception instead of wrapping it in a InvisibleCheckedException.
     *
     * @return the result
     *
     * @throws Exception               the Exception thrown inside the {@link #execute(org.multiverse.api.Transaction)}
     *                                 method.
     * @throws AbortedException        if the exception was explicitly aborted.
     * @throws TooManyRetriesException if the template retried the transaction too many times. The cause of the last
     *                                 failure (also an exception) is included as cause. So you have some idea where to
     *                                 look for problems
     */
    public final E executeChecked() throws Exception {
        preStart();
        Transaction t = getTransaction();
        if (noUsableTransaction(t)) {
            t = startTransaction();
            setTransaction(t);
            postStart(t);
            try {
                attemptCount = 1;
                Exception lastRetryCause = null;
                while (attemptCount - 1 <= retryCount) {
                    boolean abort = true;
                    boolean reset = false;
                    try {
                        E result = execute(t);
                        if (t.getStatus().equals(TransactionStatus.aborted)) {
                            String msg = format("Transaction with familyname %s is aborted", t.getFamilyName());
                            throw new AbortedException(msg);
                        }
                        preCommit();
                        t.commit();
                        abort = false;
                        reset = false;
                        postCommit();
                        return result;
                    } catch (RetryError e) {
                        t.abortAndWaitForRetry();
                        //since the abort is already done, no need to do it again.
                        abort = false;
                    } catch (CommitFailureException ex) {
                        lastRetryCause = ex;
                        reset = true;
                        //ignore, just retry the transaction
                    } catch (LoadException ex) {
                        lastRetryCause = ex;
                        reset = true;
                        //ignore, just retry the transaction
                    } finally {
                        if (abort) {
                            t.abort();
                            if (reset) {
                                t.reset();
                            }
                        }
                    }
                    attemptCount++;
                }

                throw new TooManyRetriesException("Too many retries", lastRetryCause);
            } finally {
                setTransaction(null);
            }
        } else {
            return execute(t);
        }
    }

    private Transaction startTransaction() {
        return readonly ? stm.startReadOnlyTransaction(familyName) : stm.startUpdateTransaction(familyName);
    }

    private boolean noUsableTransaction(Transaction t) {
        return t == null || t.getStatus() != TransactionStatus.active;
    }

    /**
     * Gets the current Transaction stored in the TransactionThreadLocal.
     * <p/>
     * If the ignoreThreadLocalTransaction is set, the threadlocal stuff is completeley ignored.
     *
     * @return the found transaction, or null if none is found.
     */
    private Transaction getTransaction() {
        return ignoreThreadLocalTransaction ? null : getThreadLocalTransaction();
    }

    /**
     * Stores the transaction in the TransactionThreadLocal.
     * <p/>
     * This call is ignored if the ignoreThreadLocalTransaction is true.
     *
     * @param t the transaction to set (is allowed to be null).
     */
    private void setTransaction(Transaction t) {
        if (!ignoreThreadLocalTransaction) {
            setThreadLocalTransaction(t);
        }
    }

    public static class InvisibleCheckedException extends RuntimeException {

        public InvisibleCheckedException(Exception cause) {
            super(cause);
        }

        @Override
        public Exception getCause() {
            return (Exception) super.getCause();
        }
    }
}