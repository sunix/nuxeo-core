/*
 * (C) Copyright 2007-2008 Nuxeo SAS (http://nuxeo.com/) and contributors.
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

package org.nuxeo.ecm.core.storage.sql;

import java.io.Serializable;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.utils.StringUtils;
import org.nuxeo.ecm.core.storage.StorageException;
import org.nuxeo.ecm.core.storage.sql.Model.PropertyInfo;

/**
 * The persistence context in use by a session.
 * <p>
 * This class is not thread-safe, it should be tied to a single session and the
 * session itself should not be used concurrently.
 * <p>
 * This class mostly delegates all its work to per-fragment {@link Context}s. It
 * also deals with maintaining information about generated ids.
 *
 * @author Florent Guillaume
 */
public class PersistenceContext {

    private static final Log log = LogFactory.getLog(PersistenceContext.class);

    private final Mapper mapper;

    private final RepositoryImpl.Invalidators invalidators;

    private final Map<String, Context> contexts;

    private final HierarchyContext hierContext;

    private final Model model;

    /**
     * Fragment ids generated but not yet saved. We know that any fragment with
     * one of these ids cannot exist in the database.
     */
    private final Set<Serializable> createdIds;

    /**
     * HACK: if some application code illegally recorded temporary ids (which
     * Nuxeo does), then it's useful to keep around the map to avoid crashing
     * the application.
     * <p>
     * TODO IMPORTANT don't keep it around forever, use some LRU.
     */
    private final HashMap<Serializable, Serializable> oldIdMap;

    PersistenceContext(Mapper mapper, RepositoryImpl.Invalidators invalidators) {
        this.mapper = mapper;
        this.invalidators = invalidators;
        model = mapper.getModel();
        // accessed by invalidator, needs to be concurrent
        contexts = new ConcurrentHashMap<String, Context>();

        // avoid doing tests all the time for this known case
        hierContext = new HierarchyContext(mapper, this);
        contexts.put(model.hierTableName, hierContext);

        // this has to be linked to keep creation order, as foreign keys
        // are used and need this
        createdIds = new LinkedHashSet<Serializable>();
        oldIdMap = new HashMap<Serializable, Serializable>();
    }

    /**
     * Clears all the caches. Called by RepositoryManagement.
     */
    protected int clearCaches() {
        int n = 0;
        for (Context context : contexts.values()) {
            n += context.clearCaches();
        }
        return n;
    }

    // get or return null
    protected Context getContextOrNull(String tableName) {
        return contexts.get(tableName);
    }

    // get or create if missing
    protected Context getContext(String tableName) {
        Context context = contexts.get(tableName);
        if (context == null) {
            context = new Context(tableName, mapper, this);
            contexts.put(tableName, context);
        }
        return context;
    }

    protected HierarchyContext getHierContext() {
        return hierContext;
    }

    public Serializable generateNewId() {
        Serializable id = model.generateNewId();
        createdIds.add(id);
        return id;
    }

    /* Called by Context */
    protected boolean isIdNew(Serializable id) {
        return createdIds.contains(id);
    }

    protected Serializable getRootId(Serializable repositoryId)
            throws StorageException {
        return mapper.getRootId(repositoryId);
    }

    protected void setRootId(Serializable repositoryId, Serializable id)
            throws StorageException {
        mapper.setRootId(repositoryId, id);
    }

    public void close() {
        mapper.close();
        for (Context context : contexts.values()) {
            context.close();
        }
        // don't clean the contexts, we keep the pristine cache around
    }

    /**
     * Update fulltext.
     */
    protected void updateFulltext(Session session) throws StorageException {
        Set<Serializable> dirtyStrings = new HashSet<Serializable>();
        Set<Serializable> dirtyBinaries = new HashSet<Serializable>();
        for (Context context : contexts.values()) {
            context.findDirtyDocuments(dirtyStrings, dirtyBinaries);
        }
        Set<Serializable> dirtyDocuments = new HashSet<Serializable>(
                dirtyStrings);
        dirtyDocuments.addAll(dirtyBinaries);
        if (dirtyDocuments.isEmpty()) {
            return;
        }

        log.debug("Computing fulltext");
        for (Serializable docId : dirtyDocuments) {
            boolean doStrings = dirtyStrings.contains(docId);
            boolean doBinaries = dirtyBinaries.contains(docId);
            Node document = session.getNodeById(docId);
            if (document == null) {
                // cannot happen
                continue;
            }
            Queue<Node> queue = new LinkedList<Node>();
            queue.add(document);

            // collect strings on all the document's nodes recursively
            List<String> strings = new LinkedList<String>();
            while (!queue.isEmpty()) {
                Node node = queue.remove();
                // recurse into complex properties
                // TODO could avoid recursion if no know fulltext properties
                // there
                queue.addAll(session.getChildren(node, null, true));

                if (doStrings) {
                    Map<String, PropertyInfo> infos = model.getFulltextStringPropertyInfos(node.getPrimaryType());
                    if (infos != null) {
                        for (Entry<String, PropertyInfo> entry : infos.entrySet()) {
                            PropertyInfo info = entry.getValue();
                            String name = entry.getKey();
                            if (info.propertyType == PropertyType.STRING) {
                                String v = node.getSimpleProperty(name).getString();
                                if (v != null) {
                                    strings.add(v);
                                }
                            } else /* ARRAY_STRING */{
                                for (Serializable v : node.getCollectionProperty(
                                        name).getValue()) {
                                    if (v != null) {
                                        strings.add((String) v);
                                    }
                                }
                            }
                        }
                    }

                }
                if (doBinaries) {
                    Map<String, PropertyInfo> infos = model.getFulltextBinaryPropertyInfos(node.getPrimaryType());
                    if (infos != null) {
                        /* BINARY */
                        // log.debug("Process binary TODO");
                    }

                }
            }

            if (doStrings) {
                // set the computed full text
                // on INSERT/UPDATE a trigger will change the actual fulltext
                document.setSingleProperty(model.FULLTEXT_SIMPLETEXT_PROP,
                        StringUtils.join(strings, " "));
            }
        }
        log.debug("End of fulltext");
    }

    /**
     * Saves all the data to persistent storage.
     */
    public void save() throws StorageException {
        log.debug("Saving persistence context");
        /*
         * First, create the main rows to get final ids for each.
         */
        assert !model.separateMainTable;
        Map<Serializable, Serializable> idMap = hierContext.saveCreated(createdIds);
        createdIds.clear();

        /*
         * Then save all other rows, taking the map of ids into account.
         */
        for (Context context : contexts.values()) {
            context.save(idMap);
        }
        // no need to clear the contexts, they'd get reallocate soon anyway

        // HACK: remember the idMap
        oldIdMap.putAll(idMap);

        log.debug("End of save");
    }

    /**
     * Pre-transaction invalidations processing.
     */
    protected void processInvalidations() {
        for (Context context : contexts.values()) {
            context.processInvalidations();
        }
    }

    /**
     * Post-transaction invalidations notification.
     */
    protected void notifyInvalidations() {
        for (Context context : contexts.values()) {
            context.notifyInvalidations();
        }
    }

    /**
     * Invalidate in other sessions.
     */
    protected void invalidateOthers(Context context) {
        invalidators.getInvalidator(context.getTableName()).invalidate(context);
    }

    /**
     * Find out if this old temporary id has been mapped to something permanent.
     * <p>
     * This is a workaround for incorrect application code.
     */
    protected Serializable getOldId(Serializable id) {
        return oldIdMap.get(id);
    }

    /**
     * Creates a new row in the context, for a new id (not yet saved).
     *
     * @param tableName the table name
     * @param id the new id
     * @param map the fragments map, or {@code null}
     * @return the created row
     * @throws StorageException if the row is already in the context
     */
    public SimpleFragment createSimpleFragment(String tableName,
            Serializable id, Map<String, Serializable> map)
            throws StorageException {
        return getContext(tableName).create(id, map);
    }

    /**
     * Gets a fragment given a table name and an id.
     * <p>
     * If the fragment is not in the context, fetch it from the mapper. If it's
     * not in the database, returns {@code null} or an absent fragment.
     *
     * @param tableName the fragment table name
     * @param id the fragment id
     * @param allowAbsent {@code true} to return an absent fragment as an object
     *            instead of {@code null}
     * @return the fragment, or {@code null} if none is found and {@value
     *         allowAbsent} was {@code false}
     * @throws StorageException
     */
    public Fragment get(String tableName, Serializable id, boolean allowAbsent)
            throws StorageException {
        return getContext(tableName).get(id, allowAbsent);
    }

    /**
     * Finds a row in the hierarchy table given its parent id and name. If the
     * row is not in the context, fetch it from the mapper.
     *
     * @param parentId the parent id
     * @param name the name
     * @param complexProp whether to get complex properties or real children
     * @return the hierarchy fragment, or {@code null} if none is found
     * @throws StorageException
     */
    public SimpleFragment getChildByName(Serializable parentId, String name,
            boolean complexProp) throws StorageException {
        return hierContext.getChildByName(parentId, name, complexProp);
    }

    /**
     * Finds all the children given a parent id.
     *
     * @param parentId the parent id
     * @param name the name of the children, or {@code null} for all
     * @param complexProp whether to get complex properties or real children
     * @return the collection of hierarchy fragments
     * @throws StorageException
     */
    public List<SimpleFragment> getChildren(Serializable parentId, String name,
            boolean complexProp) throws StorageException {
        return hierContext.getChildren(parentId, name, complexProp);
    }

    /**
     * Move a hierarchy fragment to a new parent with a new name.
     *
     * @param source the source
     * @param parentId the destination parent id
     * @param name the new name
     * @throws StorageException
     */
    public void move(Node source, Serializable parentId, String name)
            throws StorageException {
        hierContext.moveChild(source, parentId, name);
    }

    /**
     * Copy a hierarchy (and its children) to a new parent with a new name.
     *
     * @param source the source of the copy
     * @param parentId the destination parent id
     * @param name the new name
     * @return the id of the copy
     * @throws StorageException
     */
    public Serializable copy(Node source, Serializable parentId, String name)
            throws StorageException {
        return hierContext.copyChild(source, parentId, name);
    }

    /**
     * Removes a row.
     *
     * @param row
     * @throws StorageException
     */
    public void remove(Fragment row) throws StorageException {
        String tableName = row.getTableName();
        Context context = contexts.get(tableName);
        if (context == null) {
            log.error("Removing row not in a context: " + row);
            return;
        }
        context.remove(row);
    }

    /**
     * Checks in a node.
     *
     * @param node the node to check in
     * @param label the version label
     * @param description the version description
     * @return the created version id
     * @throws StorageException
     */
    public Serializable checkIn(Node node, String label, String description)
            throws StorageException {
        Boolean checkedIn = (Boolean) node.mainFragment.get(model.MAIN_CHECKED_IN_KEY);
        if (Boolean.TRUE.equals(checkedIn)) {
            throw new StorageException("Already checked in");
        }
        /*
         * Do the copy without non-complex children, with null parent.
         */
        Serializable id = node.getId();
        String typeName = node.getPrimaryType();
        Serializable newId = mapper.copyHierarchy(id, typeName, null, null,
                null, null, this);
        get(model.hierTableName, newId, false); // adds version as a new child
        // of its parent
        /*
         * Create a "version" row for our new version.
         */
        Map<String, Serializable> map = new HashMap<String, Serializable>();
        map.put(model.VERSION_VERSIONABLE_KEY, id);
        map.put(model.VERSION_CREATED_KEY, new GregorianCalendar()); // now
        map.put(model.VERSION_LABEL_KEY, label);
        map.put(model.VERSION_DESCRIPTION_KEY, description);
        SimpleFragment versionRow = createSimpleFragment(
                model.VERSION_TABLE_NAME, newId, map);
        /*
         * Update the original node to reflect that it's checked in.
         */
        node.mainFragment.put(model.MAIN_CHECKED_IN_KEY, Boolean.TRUE);
        node.mainFragment.put(model.MAIN_BASE_VERSION_KEY, newId);
        /*
         * Save to reflect changes immediately in database.
         */
        save();
        return newId;
    }

    /**
     * Checks out a node.
     *
     * @param node the node to check out
     * @throws StorageException
     */
    public void checkOut(Node node) throws StorageException {
        Boolean checkedIn = (Boolean) node.mainFragment.get(model.MAIN_CHECKED_IN_KEY);
        if (!Boolean.TRUE.equals(checkedIn)) {
            throw new StorageException("Already checked out");
        }
        /*
         * Update the node to reflect that it's checked out.
         */
        node.mainFragment.put(model.MAIN_CHECKED_IN_KEY, Boolean.FALSE);
    }

    /**
     * Restores a node by label.
     * <p>
     * The restored node is checked in.
     *
     * @param node the node
     * @param label the version label to restore
     * @throws StorageException
     */
    public void restoreByLabel(Node node, String label) throws StorageException {
        String typeName = node.getPrimaryType();
        /*
         * Find the version.
         */
        Serializable versionableId = node.getId();
        Context versionsContext = getContext(model.VERSION_TABLE_NAME);
        Serializable versionId = mapper.getVersionByLabel(versionableId, label,
                versionsContext);
        if (versionId == null) {
            throw new StorageException("Unknown version: " + label);
        }
        /*
         * Clear complex properties.
         */
        List<SimpleFragment> children = getChildren(versionableId, null, true);
        // copy to avoid concurrent modifications
        for (Fragment child : children.toArray(new Fragment[children.size()])) {
            remove(child); // will cascade deletes
        }
        save(); // flush deletes
        /*
         * Copy the version values.
         */
        Map<String, Serializable> overwriteMap = new HashMap<String, Serializable>();
        SimpleFragment versionHier = (SimpleFragment) hierContext.get(
                versionId, false);
        for (String key : model.getFragmentKeysType(model.hierTableName).keySet()) {
            if (key.equals(model.HIER_PARENT_KEY) ||
                    key.equals(model.HIER_CHILD_NAME_KEY) ||
                    key.equals(model.HIER_CHILD_POS_KEY) ||
                    key.equals(model.HIER_CHILD_ISPROPERTY_KEY) ||
                    key.equals(model.MAIN_PRIMARY_TYPE_KEY) ||
                    key.equals(model.MAIN_CHECKED_IN_KEY) ||
                    key.equals(model.MAIN_BASE_VERSION_KEY)) {
                continue;
            }
            overwriteMap.put(key, versionHier.get(key));
        }
        overwriteMap.put(model.MAIN_CHECKED_IN_KEY, Boolean.TRUE);
        overwriteMap.put(model.MAIN_BASE_VERSION_KEY, versionId);
        mapper.copyHierarchy(versionId, typeName, node.getParentId(), null,
                versionableId, overwriteMap, this);
    }

    /**
     * Gets a version id given a versionable id and a version label.
     *
     * @param versionableId the versionable id
     * @param label the version label
     * @return the version id, or {@code null} if not found
     * @throws StorageException
     */
    public Serializable getVersionByLabel(Serializable versionableId,
            String label) throws StorageException {
        Context versionsContext = getContext(model.VERSION_TABLE_NAME);
        return mapper.getVersionByLabel(versionableId, label, versionsContext);
    }

    /**
     * Gets the the last version id given a versionable id.
     *
     * @param versionableId the versionabel id
     * @return the version id, or {@code null} if not found
     * @throws StorageException
     */
    public Serializable getLastVersion(Serializable versionableId)
            throws StorageException {
        Context versionsContext = getContext(model.VERSION_TABLE_NAME);
        return mapper.getLastVersion(versionableId, versionsContext).getId();
    }

    /**
     * Gets all the versions given a versionable id.
     *
     * @param versionableId the versionable id
     * @return the list of version fragments
     * @throws StorageException
     */
    public List<SimpleFragment> getVersions(Serializable versionableId)
            throws StorageException {
        Context versionsContext = getContext(model.VERSION_TABLE_NAME);
        return mapper.getVersions(versionableId, versionsContext);
    }

    /**
     * Finds the proxies for a document. If the parent is not {@code null}, the
     * search will be limited to its direct children.
     * <p>
     * If the document is a version, then only proxies to that version will be
     * looked up.
     * <p>
     * If the document is a proxy, then all similar proxies (pointing to any
     * version of the same versionable) are retrieved.
     *
     * @param document the document
     * @param parent the parent, or {@code null}
     * @return the list of proxies fragments
     * @throws StorageException
     */
    public List<SimpleFragment> getProxies(Node document, Node parent)
            throws StorageException {
        /*
         * Find the versionable id.
         */
        boolean byTarget;
        Serializable searchId;
        if (document.isVersion()) {
            byTarget = true;
            searchId = document.getId();
        } else {
            byTarget = false;
            if (document.isProxy()) {
                searchId = document.getSimpleProperty(
                        model.PROXY_VERSIONABLE_PROP).getString();
            } else {
                searchId = document.getId();
            }
        }
        Serializable parentId;
        if (parent == null) {
            parentId = null;
        } else {
            parentId = parent.getId();
        }
        Context proxiesContext = getContext(model.PROXY_TABLE_NAME);
        return mapper.getProxies(searchId, byTarget, parentId, proxiesContext);
    }

    /**
     * Finds the id of the enclosing non-complex-property node.
     *
     * @param id the id
     * @return the id of the containing document, or {@code null} if there is no
     *         parent or the parent has been deleted.
     */
    protected Serializable getContainingDocument(Serializable id)
            throws StorageException {
        return hierContext.getContainingDocument(id);
    }

}
