/*
 * (C) Copyright 2006-2008 Nuxeo SAS (http://nuxeo.com/) and contributors.
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
 *     bstefanescu
 *
 * $Id$
 */

package org.nuxeo.ecm.core.repository.jcr;

import org.apache.jackrabbit.JcrConstants;
import org.joda.time.DateTime;
import org.nuxeo.common.utils.Path;
import org.nuxeo.ecm.core.query.QueryException;
import org.nuxeo.ecm.core.query.QueryParseException;
import org.nuxeo.ecm.core.query.sql.SQLQueryParser;
import org.nuxeo.ecm.core.query.sql.model.DateLiteral;
import org.nuxeo.ecm.core.query.sql.model.Expression;
import org.nuxeo.ecm.core.query.sql.model.FromClause;
import org.nuxeo.ecm.core.query.sql.model.Function;
import org.nuxeo.ecm.core.query.sql.model.IntegerLiteral;
import org.nuxeo.ecm.core.query.sql.model.Literal;
import org.nuxeo.ecm.core.query.sql.model.LiteralList;
import org.nuxeo.ecm.core.query.sql.model.Operand;
import org.nuxeo.ecm.core.query.sql.model.Operator;
import org.nuxeo.ecm.core.query.sql.model.Reference;
import org.nuxeo.ecm.core.query.sql.model.ReferenceList;
import org.nuxeo.ecm.core.query.sql.model.SQLQuery;
import org.nuxeo.ecm.core.query.sql.model.StringLiteral;

/**
 * @author <a href="mailto:bs@nuxeo.com">Bogdan Stefanescu</a>
 */
// TODO: fdate literals need special handling - make a xpathLiteral() method to be
// able to intercept date literals to adapt them
public class XPathBuilder implements QueryConstants {

    public static String fromNXQL(String query) throws QueryException {
        return fromNXQL(SQLQueryParser.parse(query));
    }

    public static String fromNXQL(SQLQuery query) throws QueryException {
        XPathQuery xq = new XPathQuery();
        buildElementPart(xq, query);
        whereClause(xq, query);
        orderBy(xq, query);
        return xq.toString();
    }

    /**
     * Builds the element part of the XPATH query. Example:
     * {@code element(*, ecmdt:Document)} for {@code SELECT * FROM Document}.
     *
     * @param builder
     */
    static void buildElementPart(XPathQuery xq, SQLQuery query) {
        if (query.from.elements.size() != 1) {
            throw new QueryParseException("Invalid query");
        }

        String from = query.from.elements.get(0);
        if (query.from.getType() == FromClause.LOCATION) {
            // TODO NXQL parser doesn't support this
            xq.type = NodeConstants.ECM_NT_DOCUMENT.rawname;
            StringBuilder buf = new StringBuilder(1024);
            String name = buildPathPattern(buf, from, false);
            if (name != null) {
                xq.name = name;
            }
        } else if ("document".equals(from) || "*".equals(from)) {
            xq.type = NodeConstants.ECM_NT_DOCUMENT.rawname;
        } else if ("publishedVersions".equals(from)) {
            // a proxy search - need special handling to deref. proxies
            xq.type = NodeConstants.ECM_NT_DOCUMENT_PROXY.rawname;
            xq.isProxyQuery = true;
        } else {
            xq.type = TypeAdapter.docType2Jcr(from);
        }
    }

    static void orderBy(XPathQuery xq, SQLQuery query) {
        if (query.orderBy == null) {
            return;
        }
        ReferenceList refs = query.orderBy.elements;
        if (refs.isEmpty()) {
            return;
        }
        xq.orderBy.append(" order by ");
        int size = refs.size() - 1;
        for (int i = 0; i < size; i++) {
            Reference ref = refs.get(i);
            reference(xq.orderBy, ref);
            xq.orderBy.append(", ");
        }
        Reference ref = refs.get(size);
        reference(xq.orderBy, ref);
        if (query.orderBy.isDescendent) {
            xq.orderBy.append(" descending");
        } else {
            xq.orderBy.append(" ascending");
        }
    }

    static void whereClause(XPathQuery xq, SQLQuery sqlQuery)
            throws QueryException {
        if (sqlQuery.where != null) {
            Expression expr = sqlQuery.where.predicate;
            if (expr != null) {
                if (expr.isPathExpression()) {
                    pathExpression(xq, expr);
                } else {
                    expression(xq, expr);
                }
            }
        }
    }

    static Operand lookaheadPathExpression(XPathQuery xq, Expression expr)
            throws QueryException {
        if (expr.lvalue instanceof Expression) {
            Expression lexpr = (Expression) expr.lvalue;
            if (lexpr.isPathExpression()) {
                pathExpression(xq, lexpr);
                return expr.rvalue;
            }
        }
        if (expr.rvalue instanceof Expression) {
            Expression rexpr = (Expression) expr.rvalue;
            if (rexpr.isPathExpression()) {
                pathExpression(xq, rexpr);
                return expr.lvalue;
            }
        }
        return null;
    }

    static void pathExpression(XPathQuery xq, Expression expr)
            throws QueryException {
        if (xq.path != null) { // path already set
            throw new QueryException(
                    "Invalid query:  multiple path constraint are not supported");
        }
        boolean startsWith;
        if (expr.operator == Operator.LIKE) {
            startsWith = false;
        } else if (expr.operator == Operator.STARTSWITH) {
            startsWith = true;
        } else {
            throw new QueryException(
                    "Invalid query:  ecm:path can only be compared using LIKE or STARTSWITH operators");
        }
        StringBuilder buf = new StringBuilder(1024);
        String docName = buildPathPattern(buf,
                ((StringLiteral) expr.rvalue).value, startsWith);
        if (docName != null) {
            xq.name = docName;
        }
        xq.path = buf.toString();
    }

    public static String operator(Operator op) {
        return op == Operator.NOTEQ ? "!=" : op.toString();
    }

    /**
     * Process special expressions. If the expression is not a special one
     * return false so that the expression will be processed in the default way.
     * Otherwise process it and return true.
     *
     * @param xq
     * @param expr
     * @return
     * @throws QueryException
     */
    static boolean specialExpression(XPathQuery xq, Expression expr)
            throws QueryException {
        if (expr.lvalue instanceof Reference) { // TODO remove this
            String name = ((Reference) expr.lvalue).name;
            if (name.equals(ECM_FULLTEXT)) {
                if (expr.rvalue.getClass() != StringLiteral.class) {
                    throw new QueryException(
                            "Invalid query:  ecm:fulltext can only be compared against string values");
                }
                xq.predicate.append("jcr:contains(., '").append(
                        ((StringLiteral) expr.rvalue).value).append("')");
                return true;
            } else if (name.equals(ECM_TYPE)) {
                if (expr.rvalue.getClass() != StringLiteral.class) {
                    throw new QueryException(
                            "Invalid query:  ecm:fulltext can only be compared against string values");
                }
                String type = ((StringLiteral) expr.rvalue).value;
                type = TypeAdapter.docType2Jcr(type);
                xq.predicate.append("@").append(JcrConstants.JCR_PRIMARYTYPE);
                xq.predicate.append(" ").append(operator(expr.operator)).append(" '")
                .append(type).append("'");
                return true;
            } else if (name.equals(ECM_NAME)) {
                if (expr.rvalue.getClass() != StringLiteral.class) {
                    throw new QueryException(
                            "Invalid query:  ecm:fulltext can only be compared against string values");
                }
                xq.predicate.append("fn:name() ").append(
                        expr.operator.toString()).append(" '").append(
                        ((StringLiteral) expr.rvalue).value).append("'");
                return true;
            } else if (name.equals(ECM_IS_CHECKED_IN_VERSION)
                    || name.equals(ECM_VERSION)) {
                boolean eq;
                if (expr.operator == Operator.EQ) {
                    eq = true;
                } else if (expr.operator == Operator.NOTEQ) {
                    eq = false;
                } else {
                    throw new QueryException(
                            "Invalid query: ecm:isCheckedInVersion support only = and != operators");
                }
                boolean value;
                if (expr.rvalue.getClass() == IntegerLiteral.class) {
                    value = ((IntegerLiteral) expr.rvalue).value == 0 ? false
                            : true;
                } else {
                    throw new QueryException(
                            "Invalid query: ecm:isCheckedInVersion support integer values");
                }
                if ((eq && value) || (!eq && !value)) { // we need to find
                    // versions
                    xq.predicate.append(" @").append(ECM_FROZEN_NODE);
                } else { // avoid versions
                    xq.predicate.append(" not(@").append(ECM_FROZEN_NODE).append(
                            ") ");
                }
                return true;
            } else if (expr.rvalue.getClass() == DateLiteral.class) { // dates
                // *[@dc:created > "2008-06-03T00:00:00.000+01:00" and
                // @dc:created < xs:dateTime("2008-06-04T00:00:00.000+01:00")]
                // xs:date seems to not be correctly handled in jackrabbit .
                // see
                // https://issues.apache.org/jira/browse/JCR-1386?page=com.atlassian.jira.plugin.system.issuetabpanels:all-tabpanel
                DateLiteral dl = (DateLiteral) expr.rvalue;
                Reference ref = (Reference) expr.lvalue;
                if (dl.onlyDate) {
                    if (expr.operator == Operator.EQ) {
                        DateTime d0 = dl.value;
                        DateTime d1 = d0.plusDays(1);
                        int month = d0.getMonthOfYear();
                        int day = d0.getDayOfMonth();
                        xq.predicate.append("(");
                        reference(xq.predicate, ref);
                        xq.predicate.append(" >= xs:dateTime('").append(
                                d0.getYear()).append("-");
                        if (month < 10) {
                            xq.predicate.append("0").append(month);
                        } else {
                            xq.predicate.append(month);
                        }
                        xq.predicate.append("-");
                        if (day < 10) {
                            xq.predicate.append("0").append(day);
                        } else {
                            xq.predicate.append(day);
                        }
                        xq.predicate.append("T00:00:00.000Z') and ");

                        month = d1.getMonthOfYear();
                        day = d1.getDayOfMonth();
                        reference(xq.predicate, ref);
                        xq.predicate.append(" < xs:dateTime('").append(
                                d1.getYear()).append("-");
                        if (month < 10) {
                            xq.predicate.append("0").append(month);
                        } else {
                            xq.predicate.append(month);
                        }
                        xq.predicate.append("-");
                        if (day < 10) {
                            xq.predicate.append("0").append(day);
                        } else {
                            xq.predicate.append(day);
                        }
                        xq.predicate.append("T00:00:00.000Z'))");
                    } else if (expr.operator == Operator.GTEQ) {
                        DateTime date = dl.value;
                        compareDate(xq.predicate, ref, expr.operator, date);
                    } else if (expr.operator == Operator.GT) {
                        DateTime date = dl.value.plusDays(1);
                        compareDate(xq.predicate, ref, Operator.GTEQ, date);
                    } else if (expr.operator == Operator.LT) {
                        DateTime date = dl.value;
                        compareDate(xq.predicate, ref, expr.operator, date);
                    } else if (expr.operator == Operator.LTEQ) {
                        DateTime date = dl.value.plusDays(1);
                        compareDate(xq.predicate, ref, Operator.LT, date);
                    }
                } else {
                    reference(xq.predicate, ref);
                    operator(xq.predicate, expr.operator);
                    xq.predicate.append("xs:dateTime('"
                            + DateLiteral.dateTime(dl) + "')");
                }
                return true;
            }
        }
        return false;
    }

    static void compareDate(StringBuilder buf, Reference ref,
            Operator operator, DateTime date) {
        int month = date.getMonthOfYear();
        int day = date.getDayOfMonth();
        reference(buf, ref);
        operator(buf, operator);
        buf.append("xs:dateTime('").append(date.getYear()).append("-");
        if (month < 10) {
            buf.append("0").append(month);
        } else {
            buf.append(month);
        }
        buf.append("-");
        if (day < 10) {
            buf.append("0").append(day);
        } else {
            buf.append(day);
        }
        buf.append("T00:00:00.000Z')");
    }

    static void between(XPathQuery xq, Operand lvalue, Operand rvalue)
            throws QueryException {
        String name = ((Reference) lvalue).name;
        xq.predicate.append(" (").append(name).append(" >= ");
        LiteralList list = (LiteralList) rvalue;
        Literal min = list.get(0);
        Literal max = list.get(1);
        literal(xq.predicate, min);
        xq.predicate.append(" and ").append(name).append(" <= ");
        literal(xq.predicate, max);
        xq.predicate.append(")");
    }

    static void inclusion(XPathQuery xq, Operand lvalue, Operand rvalue) {
        String name = ((Reference) lvalue).name;
        xq.predicate.append(" (");
        LiteralList list = (LiteralList) rvalue;
        int size = list.size() - 1;
        for (int i = 0; i < size; i++) {
            Literal literal = list.get(i);
            xq.predicate.append(name).append(" = ").append(literal)
                    .append(" or ");
        }
        xq.predicate.append(name).append(" = ").append(list.get(size))
                .append(") ");
    }

    static void expression(XPathQuery xq, Expression expr)
            throws QueryException {
        // look ahead for path expressions
        if (xq.path == null) {
            Operand remaining = lookaheadPathExpression(xq, expr);
            if (remaining != null) { // was a path expr
                operand(xq, remaining);
                return;
            }
        }
        // test for a special expression
        if (specialExpression(xq, expr)) {
            // special expression are exception from the general rule and should
            // be processed separately
            return;
        }
        // default processing
        if (expr.operator == Operator.AND) {
            operand(xq, expr.lvalue);
            xq.predicate.append(" and ");
            operand(xq, expr.rvalue);
        } else if (expr.operator == Operator.OR) {
            xq.initPath();
            operand(xq, expr.lvalue);
            xq.predicate.append(" or ");
            operand(xq, expr.rvalue);
        } else if (expr.operator == Operator.NOT) {
            xq.initPath();
            xq.predicate.append(" not(");
            operand(xq, expr.lvalue);
            xq.predicate.append(") ");
        } else if (expr.operator == Operator.LIKE) {
            xq.predicate.append(" jcr:like(");
            reference(xq.predicate, (Reference) expr.lvalue); // reference
            xq.predicate.append(", ");
            literal(xq.predicate, (Literal) expr.rvalue); // literal
            xq.predicate.append(") ");
        } else if (expr.operator == Operator.NOTLIKE) {
            xq.predicate.append(" not(jcr:like(");
            reference(xq.predicate, (Reference) expr.lvalue); // reference
            xq.predicate.append(", ");
            literal(xq.predicate, (Literal) expr.rvalue); // literal
            xq.predicate.append(")) ");
        } else if (expr.operator == Operator.IN) {
            inclusion(xq, expr.lvalue, expr.rvalue);
        } else if (expr.operator == Operator.BETWEEN) {
            between(xq, expr.lvalue, expr.rvalue);
        } else if (expr.operator == Operator.NOTBETWEEN) {
            xq.predicate.append(" not(");
            between(xq, expr.lvalue, expr.rvalue);
            xq.predicate.append(") ");
        } else if (expr.operator == Operator.NOTIN) {
            xq.predicate.append(" not(");
            inclusion(xq, expr.lvalue, expr.rvalue);
            xq.predicate.append(") ");
        } else if (expr.rvalue != null) { // other binary operation
            operand(xq, expr.lvalue);
            operator(xq.predicate, expr.operator);
            operand(xq, expr.rvalue);
        } else { // other unary operation
            operator(xq.predicate, expr.operator);
            xq.predicate.append(" (");
            operand(xq, expr.lvalue);
            xq.predicate.append(") ");
        }
        return;
    }

    static void operator(StringBuilder buf, Operator operator) {
        buf.append(" ").append(operator.toString()).append(" ");
    }

    static void operand(XPathQuery xq, Operand operand) throws QueryException {
        StringBuilder buf = xq.predicate;
        if (operand instanceof Expression) {
            buf.append("(");
            expression(xq, (Expression) operand);
            buf.append(")");
        } else if (operand instanceof Reference) {
            reference(buf, (Reference) operand);
        } else if (operand instanceof Literal) {
            literal(buf, (Literal) operand);
        } else if (operand instanceof Function) {
            function(buf, (Function) operand);
        } else {
            throw new UnsupportedOperationException(
                    "Operand type not supported: " + operand);
        }
    }

    static void literal(StringBuilder buf, Literal literal) {
        Class<?> klass = literal.getClass();
        if (klass == StringLiteral.class) {
            buf.append("'").append(literal.asString()).append("'");
        } else if (klass == DateLiteral.class) {
            // *[@dc:created > "2008-06-03T00:00:00.000+01:00" and @dc:created <
            // xs:dateTime("2008-06-04T00:00:00.000+01:00")]
            // xs:date seems to not be correctly handled in jackrabbit .
            // see
            // https://issues.apache.org/jira/browse/JCR-1386?page=com.atlassian.jira.plugin.system.issuetabpanels:all-tabpanel
            buf.append("xs:dateTime('"
                    + DateLiteral.dateTime((DateLiteral) literal) + "')");
        } else {
            buf.append(literal.asString());
        }
    }

    static void function(StringBuilder buf, Function function) {
        buf.append(function.toString()); // TODO: expand fucntion args too
    }

    static void reference(StringBuilder buf, Reference ref) {
        String name = ref.name;
        if (ref.isPathReference()) {
            int p = name.lastIndexOf('/');
            if (p > 0) {
                String base = name.substring(0, p);
                String lastSegment = name.substring(p + 1);
                buf.append(base).append("/@").append(lastSegment);
            }
        } else {
            if (ECM_PATH.equals(name)) {
                name = JcrConstants.JCR_PATH;
            } else if (ECM_ID.equals(name)) {
                name = JcrConstants.JCR_UUID;
                // } else if (ECM_SCHEMA.equals(name)) {
                // name = JcrConstants.JCR_MIXINTYPES;
            }
            buf.append("@").append(name);
        }
    }

    /**
     * LIKE path:
     * </ul>
     * <li> if path ends with / only children
     * <li> if path ends with /% all descendants
     * <li> otherwise the last element will be the document name to find
     * <li> if path begins with %/ - any sub tree containing that patch will
     * match otherwise the absolute path will be used
     * </ul>
     * <p>
     * Note: descendants or self is not supported by Jackrabbit.
     * <p>
     * STARTSWITH path is the same as LIKE "path/%"
     *
     *
     * @param docPath
     * @param buf the buffer to fill with the computed path. The path will
     *            always end with a /
     * @return the document name if any was computed from the path expression or
     *         null otherwise
     */
    public static String buildPathPattern(StringBuilder buf, String docPath,
            boolean startsWith) {
        // StringBuilder buf = new StringBuilder(1024);
        int len = docPath.length();
        if (len == 0) {
            return null;
        }
        String docName = null;
        Path path = new Path(docPath);
        int cnt = path.segmentCount();
        if (cnt == 0) { // "/"
            if (startsWith) {
                buf.append("/jcr:root/ecm:root/ecm:children//");
            } else {
                buf.append("/jcr:root/");
                docName = "ecm:root";
            }
            return docName;
        }
        String segment = path.segment(0);
        if (cnt == 1) {
            if (segment.length() == 1 && segment.charAt(0) == '%') { // "%"
                buf.append("//");
                docName = "*";
            } else {
                buf.append("/jcr:root/ecm:root/ecm:children/");
                if (startsWith) {
                    buf.append(segment).append("/ecm:children//");
                } else if (path.hasTrailingSeparator()) {
                    buf.append(segment).append("/ecm:children/");
                } else {
                    docName = segment;
                }
            }
            return docName;
        }
        // we have more than one segment
        if (segment.length() == 1 && segment.charAt(0) == '%') { // "%/..."
            buf.append("//").append(segment).append("/ecm:children/");
        } else { // "/..."
            buf.append("/jcr:root/ecm:root/ecm:children/").append(segment)
                    .append("/ecm:children/");
        }
        segment = path.lastSegment();
        if (segment.length() == 1 && segment.charAt(0) == '%') { // "../%"
            startsWith = true;
            cnt--;
        } else if (startsWith || path.hasTrailingSeparator()) { // "/.../"
            docName = null;
        } else { // /...
            docName = segment;
            cnt--;
        }
        for (int i = 1; i < cnt; i++) {
            segment = path.segment(i);
            buf.append(segment).append("/ecm:children/");
        }
        if (startsWith) {
            buf.append("/");
        }
        return docName;
    }

}
