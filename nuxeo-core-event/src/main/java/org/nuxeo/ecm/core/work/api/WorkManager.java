/*
 * (C) Copyright 2012 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.work.api;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.nuxeo.ecm.core.work.api.Work.State;

/**
 * A {@link WorkManager} executes {@link Work} instances asynchronously.
 * <p>
 * A {@link Work} can be scheduled by calling {@link #schedule}.
 * <p>
 * Work is executed in a thread pool and a work queue that depends on the work's
 * category.
 *
 * @since 5.6
 */
public interface WorkManager {

    /**
     * The scheduling policy to use when adding a work instance using
     * {@link #schedule(Work, Scheduling)}.
     */
    enum Scheduling {
        /**
         * Always schedule the work.
         */
        ENQUEUE,
        /**
         * Any other scheduled work equals to this one is removed from
         * scheduling and canceled first, before this work is scheduled.
         */
        CANCEL_SCHEDULED,
        /**
         * If there is a scheduled work equals to this one, then don't schedule
         * the work.
         */
        IF_NOT_SCHEDULED(State.SCHEDULED),
        /**
         * If there is a running work equals to this one, then don't schedule
         * the work.
         */
        IF_NOT_RUNNING(State.RUNNING),
        /**
         * If there is a running or scheduled work equals to this one, then
         * don't schedule the work.
         */
        IF_NOT_RUNNING_OR_SCHEDULED;

        public final State state;

        private Scheduling() {
            state = null;
        }

        private Scheduling(State state) {
            this.state = state;
        }
    }

    /**
     * Lists the ids of the existing work queues.
     *
     * @return the list of queue ids
     */
    List<String> getWorkQueueIds();

    /**
     * Gets the queue id used for a given work category.
     *
     * @param category the category
     * @return the queue id
     */
    String getCategoryQueueId(String category);

    /**
     * Gets the work queue descriptor for a given queue id.
     *
     * @param queueId the queue id
     * @return the work queue descriptor, or {@code null}
     */
    WorkQueueDescriptor getWorkQueueDescriptor(String queueId);

    /**
     * Starts up this {@link WorkManager} and attempts to resume work previously
     * suspended and saved at {@link #shutdown} time.
     */
    void init();

    /**
     * Shuts down a work queue and attempts to suspend and save the running and
     * scheduled work instances.
     *
     * @param queueId the queue id
     * @param timeout the time to wait
     * @param unit the timeout unit
     * @return {@code true} if shutdown is done, {@code false} if there are
     *         still some threads executing after the timeout
     */
    boolean shutdownQueue(String queueId, long timeout, TimeUnit unit)
            throws InterruptedException;

    /**
     * Shuts down this {@link WorkManager} and attempts to suspend and save the
     * running and scheduled work instances.
     *
     * @param timeout the time to wait
     * @param unit the timeout unit
     * @return {@code true} if shutdown is done, {@code false} if there are
     *         still some threads executing after the timeout
     */
    boolean shutdown(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Schedules work for execution at a later time.
     *
     * @param work the work to execute
     */
    void schedule(Work work);

    /**
     * Schedules work for execution at a later time, with a specific
     * {@linkplain Scheduling scheduling} policy.
     *
     * @param work the work to execute
     * @param scheduling the scheduling policy
     * @see #schedule(Work)
     */
    void schedule(Work work, Scheduling scheduling);

    /**
     * Finds a work instance by equality or identity.
     * <p>
     * Note that an instance requested as RUNNING could be found SUSPENDING or
     * SUSPENDED, and an instance requested as COMPLETED could be found FAILED.
     * <p>
     * Also, due to the asynchronous nature of work execution, the
     * {@linkplain Work#getState state} of the work could be different than
     * what's expected if the work started or finished in the middle of the
     * call.
     *
     * @param work the work to find
     * @param state the state defining the state to look into,
     *            {@link State#SCHEDULED SCHEDULED}, {@link State#RUNNING
     *            RUNNING}, {@link State#COMPLETED COMPLETED}, or {@code null}
     *            for non-completed
     * @param useEquals if {@code true} then use {@link Work#equals} to find the
     *            work instance, otherwise use object identity
     * @param pos a 1-element array to return the position in the internal queue
     * @return the found work instance, or {@code null} if not found
     */
    Work find(Work work, State state, boolean useEquals, int[] pos);

    /**
     * Lists the work instances in a given queue in a defined state.
     * <p>
     * Note that an instance requested as RUNNING could be found SUSPENDING or
     * SUSPENDED, and an instance requested as COMPLETED could be found FAILED.
     *
     * @param queueId the queue id
     * @param state the state defining the state to look into,
     *            {@link State#SCHEDULED SCHEDULED}, {@link State#RUNNING
     *            RUNNING}, {@link State#COMPLETED COMPLETED}, or {@code null}
     *            for non-completed
     * @return the list of work instances in the given state
     */
    List<Work> listWork(String queueId, State state);

    /**
     * Gets the size of the non-completed work (scheduled + running) for a give
     * queue.
     *
     * @param queueId the queue id
     * @return the number of non-completed work instances
     */
    int getNonCompletedWorkSize(String queueId);

    /**
     * Waits for completion of work in a given queue.
     *
     * @param queueId the queue id
     * @param timeout the time to wait
     * @param unit the timeout unit
     * @return {@code true} if all work completed in the queue, or {@code false}
     *         if there is still some non-completed work after the timeout
     */
    boolean awaitCompletion(String queueId, long timeout, TimeUnit unit)
            throws InterruptedException;

    /**
     * Waits for completion of all work.
     *
     * @param timeout the time to wait
     * @param unit the timeout unit
     * @return {@code true} if all work completed, or {@code false} if there is
     *         still some non-completed work after the timeout
     */
    boolean awaitCompletion(long timeout, TimeUnit unit)
            throws InterruptedException;

    /**
     * Clears the list of completed work instances for a given queue.
     *
     * @param queueId the queue id
     */
    void clearCompletedWork(String queueId);

    /**
     * Clears the list of completed work instances older than the given time.
     *
     * @param completionTime the completion time (milliseconds since epoch)
     *            before which completed work instances are cleared, or
     *            {@code 0} for all
     */
    void clearCompletedWork(long completionTime);

    /**
     * Clears the list of completed work instances older than what's configured
     * for each queue.
     */
    void cleanup();

}
