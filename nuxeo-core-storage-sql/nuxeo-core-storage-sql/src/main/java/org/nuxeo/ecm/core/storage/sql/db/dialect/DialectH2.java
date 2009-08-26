/*
 * (C) Copyright 2008-2009 Nuxeo SA (http://nuxeo.com/) and contributors.
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

package org.nuxeo.ecm.core.storage.sql.db.dialect;

import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.hibernate.dialect.H2Dialect;
import org.nuxeo.ecm.core.storage.StorageException;
import org.nuxeo.ecm.core.storage.sql.Model;
import org.nuxeo.ecm.core.storage.sql.RepositoryDescriptor;
import org.nuxeo.ecm.core.storage.sql.Model.FulltextInfo;
import org.nuxeo.ecm.core.storage.sql.db.Column;
import org.nuxeo.ecm.core.storage.sql.db.Database;
import org.nuxeo.ecm.core.storage.sql.db.Table;

/**
 * H2-specific dialect.
 *
 * @author Florent Guillaume
 */
public class DialectH2 extends Dialect {

    private static final String DEFAULT_FULLTEXT_ANALYZER = "org.apache.lucene.analysis.standard.StandardAnalyzer";

    public DialectH2(DatabaseMetaData metadata,
            RepositoryDescriptor repositoryDescriptor) throws StorageException {
        super(new H2Dialect(), metadata);
    }

    @Override
    public String getCreateFulltextIndexSql(String indexName, String tableName,
            List<String> columnNames) {
        return null; // no SQL index for H2
    }

    @Override
    public String[] getFulltextMatch(String indexName, String fulltextQuery,
            Column mainColumn, Model model, Database database) {
        String phftname = database.getTable(model.FULLTEXT_TABLE_NAME).getName(); // physical
        String fullIndexName = "PUBLIC_" + phftname + "_" + indexName;
        String queryTable = String.format("NXFT_SEARCH('%s', ?)", fullIndexName);
        String whereExpr = String.format("%%s.KEY = %s",
                mainColumn.getFullQuotedName());
        return new String[] { (queryTable + " %s"), fulltextQuery, whereExpr,
                null };
    }

    @Override
    public int getFulltextIndexedColumns() {
        return 0;
    }

    @Override
    public boolean supportsUpdateFrom() {
        return false; // check this, unused
    }

    @Override
    public boolean doesUpdateFromRepeatSelf() {
        return true;
    }

    @Override
    public String getClobCast(boolean inOrderBy) {
        if (!inOrderBy) {
            return "CAST(%s AS VARCHAR)";
        }
        return null;
    }

    @Override
    public String getSecurityCheckSql(String idColumnName) {
        return String.format("NX_ACCESS_ALLOWED(%s, ?, ?)", idColumnName);
    }

    @Override
    public String getInTreeSql(String idColumnName) {
        return String.format("NX_IN_TREE(%s, ?)", idColumnName);
    }

    @Override
    public boolean isFulltextTableNeeded() {
        return false;
    }

    @Override
    public boolean supportsArrays() {
        return false;
    }

    private static final String h2Functions = "org.nuxeo.ecm.core.storage.sql.db.H2Functions";

    private static final String h2Fulltext = "org.nuxeo.ecm.core.storage.sql.db.H2Fulltext";

    @Override
    public Collection<ConditionalStatement> getConditionalStatements(
            Model model, Database database) {
        String methodSuffix;
        switch (model.idGenPolicy) {
        case APP_UUID:
            methodSuffix = "String";
            break;
        case DB_IDENTITY:
            methodSuffix = "Long";
            break;
        default:
            throw new AssertionError(model.idGenPolicy);
        }
        Table ft = database.getTable(model.FULLTEXT_TABLE_NAME);

        List<ConditionalStatement> statements = new LinkedList<ConditionalStatement>();

        statements.add(makeFunction("NX_IN_TREE", //
                "isInTree" + methodSuffix));

        statements.add(makeFunction("NX_ACCESS_ALLOWED", //
                "isAccessAllowed" + methodSuffix));

        statements.add(makeFunction("NX_CLUSTER_INVAL", //
                "clusterInvalidate" + methodSuffix));

        statements.add(makeFunction("NX_CLUSTER_GET_INVALS", //
                "getClusterInvalidations" + methodSuffix));

        statements.add(new ConditionalStatement( //
                false, // late
                Boolean.FALSE, // no drop
                null, //
                null, //
                String.format(
                        "CREATE ALIAS IF NOT EXISTS NXFT_INIT FOR \"%s.init\"; "
                                + "CALL NXFT_INIT()", h2Fulltext)));

        FulltextInfo fti = model.getFulltextInfo();
        for (String indexName : fti.indexNames) {
            String analyzer = fti.indexAnalyzer.get(indexName);
            if (analyzer == null) {
                analyzer = DEFAULT_FULLTEXT_ANALYZER;
            }
            String fullIndexName = String.format("PUBLIC_%s_%s", ft.getName(),
                    indexName);
            String suffix = indexName.equals(Model.FULLTEXT_DEFAULT_INDEX) ? ""
                    : '_' + indexName;
            Column ftst = ft.getColumn(model.FULLTEXT_SIMPLETEXT_KEY + suffix);
            Column ftbt = ft.getColumn(model.FULLTEXT_BINARYTEXT_KEY + suffix);
            statements.add(new ConditionalStatement(
                    false, // late
                    Boolean.FALSE, // no drop
                    null, //
                    null, //
                    String.format(
                            "CALL NXFT_CREATE_INDEX('%s', 'PUBLIC', '%s', ('%s', '%s'), '%s')",
                            fullIndexName, ft.getName(),
                            ftst.getPhysicalName(), ftbt.getPhysicalName(),
                            analyzer)));
        }
        return statements;
    }

    private ConditionalStatement makeFunction(String functionName,
            String methodName) {
        return new ConditionalStatement( //
                true, // early
                Boolean.TRUE, // always drop
                null, //
                String.format("DROP ALIAS IF EXISTS %s", functionName), //
                String.format("CREATE ALIAS %s FOR \"%s.%s\"", functionName,
                        h2Functions, methodName));
    }

    @Override
    public int getClusterNodeType() {
        return Types.INTEGER;
    }

    @Override
    public int getClusterFragmentsType() {
        return Types.VARCHAR;
    }

    @Override
    public String getCleanupClusterNodesSql(Model model, Database database) {
        Table cln = database.getTable(model.CLUSTER_NODES_TABLE_NAME);
        Column clnid = cln.getColumn(model.CLUSTER_NODES_NODEID_KEY);
        // delete nodes for sessions don't exist anymore, and old node for this
        // session (session ids are recycled)
        return String.format(
                "DELETE FROM %s C WHERE "
                        + "NOT EXISTS(SELECT * FROM INFORMATION_SCHEMA.SESSIONS S WHERE C.%s = S.ID) "
                        + "OR C.%<s = SESSION_ID()", cln.getQuotedName(),
                clnid.getQuotedName());
    }

    @Override
    public String getCreateClusterNodeSql(Model model, Database database) {
        Table cln = database.getTable(model.CLUSTER_NODES_TABLE_NAME);
        Column clnid = cln.getColumn(model.CLUSTER_NODES_NODEID_KEY);
        Column clncr = cln.getColumn(model.CLUSTER_NODES_CREATED_KEY);
        return String.format(
                "INSERT INTO %s (%s, %s) VALUES (SESSION_ID(), CURRENT_TIMESTAMP)",
                cln.getQuotedName(), clnid.getQuotedName(),
                clncr.getQuotedName());
    }

    @Override
    public String getRemoveClusterNodeSql(Model model, Database database) {
        Table cln = database.getTable(model.CLUSTER_NODES_TABLE_NAME);
        Column clnid = cln.getColumn(model.CLUSTER_NODES_NODEID_KEY);
        return String.format("DELETE FROM %s WHERE %s = SESSION_ID()",
                cln.getQuotedName(), clnid.getQuotedName());
    }

    @Override
    public String getClusterInsertInvalidations() {
        return "CALL NX_CLUSTER_INVAL(?, ?, ?)";
    }

    @Override
    public String getClusterGetInvalidations() {
        return "SELECT * FROM NX_CLUSTER_GET_INVALS()";
    }
}
