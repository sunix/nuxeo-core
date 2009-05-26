/*
 * (C) Copyright 2006-2009 Nuxeo SAS (http://nuxeo.com/) and contributors.
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
 *     Nuxeo - initial API and implementation
 *
 * $Id$
 */
package org.nuxeo.ecm.core.event.jmx;

/**
 * Monitoring interface
 *
 * @author Thierry Delprat
 *
 */
public interface EventMonitoringMBean {

    /**
     * Get the number of events in process or waiting for available slots
     *
     * @return
     */
    int getEventsInQueueCount();

    /**
     * Get number of active threads
     *
     * @return
     */
    int getActiveThreadsCount();

    /**
     * Tells if time tracking is active for Async event handlers
     *
     * @return
     */
    boolean isAsyncHandlersTrackingEnabled();


    /**
     * Enables or disables time tracking for Async event handlers
     *
     * @return
     */
    void setAsyncHandlersTrackingEnabled(boolean collectAsyncHandlersExecTime);


    /**
     * Tells if time tracking is active for Sync event handlers
     *
     * @return
     */
    boolean isSyncHandlersTrackingEnabled();

    /**
     * Enables or disables time tracking for Sync event handlers
     *
     * @return
     */
    void setSyncHandlersTrackingEnabled(boolean collectSyncHandlersExecTime);

    /**
     * Returns the statistics for Async Handlers
     *
     * @return
     */
    String getAsyncHandlersExecTime();

    /**
     * Returns the statistics for Sync Handlers
     *
     * @return
     */
    String getSyncHandlersExecTime();

    /**
     * reset the statistics
     */
    void resetHandlersExecTime();


    /**
     * Tells if async handlers execution is blocked
     *
     * @return
     */
    boolean isBlockAsyncHandlers();

    /**
     * Blocks or enables async handlers execution
     *
     * @return
     */
    void setBlockAsyncHandlers(boolean blockAsyncHandlers);

    /**
     * Tells if post-commit sync handlers execution is blocked
     *
     * @return
     */
    boolean isBlockSyncPostCommitHandlers();

    /**
     * Blocks or enables post-commit sync handlers execution
     *
     * @return
     */
    void setBlockSyncPostCommitHandlers(boolean blockSyncPostCommitHandlers);

    /**
     * get a summary of all registred listeners
     * (name - type - enabled)
     *
     * @return
     */
    String getListenersConfig();

    /**
     * Enables or disables a listener by it's name
     *
     * @param listenerName
     * @param enabled
     */
    void setListenerEnabledFlag(String listenerName, boolean enabled);

    /**
     * getter for bulkMode processing
     * Bulk mode may be used by listeners to drop some processing
     *
     * @return
     */
    boolean isBulkModeEnabled();

    /**
     * Enables/Disables bulkMode processing
     *
     * @param bulkModeEnabled
     */
    void setBulkModeEnabled(boolean bulkModeEnabled);

}
