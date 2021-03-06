/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.table;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import org.h2.api.ErrorCode;
import org.h2.command.Prepared;
import org.h2.command.dml.Query;
import org.h2.engine.Constants;
import org.h2.engine.Database;
import org.h2.engine.DbObject;
import org.h2.engine.Session;
import org.h2.engine.User;
import org.h2.expression.Alias;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionColumn;
import org.h2.expression.ExpressionVisitor;
import org.h2.expression.Parameter;
import org.h2.index.Index;
import org.h2.index.IndexType;
import org.h2.index.ViewIndex;
import org.h2.message.DbException;
import org.h2.result.LocalResult;
import org.h2.result.Row;
import org.h2.result.SortOrder;
import org.h2.schema.Schema;
import org.h2.util.New;
import org.h2.util.StatementBuilder;
import org.h2.util.StringUtils;
import org.h2.value.Value;

/**
 * A view is a virtual table that is defined by a query.
 * @author Thomas Mueller
 * @author Nicolas Fortin, Atelier SIG, IRSTV FR CNRS 24888
 */
/*
         有三种产生TableView的方式:
	1. CREATE VIEW语句

	如: CREATE OR REPLACE FORCE VIEW IF NOT EXISTS my_view COMMENT IS 'my view'(f1,f2) 
			AS SELECT id,name FROM CreateViewTest


	2. 嵌入在FROM中的临时视图

	如: select id,name from (select id,name from CreateViewTest)


	3. WITH RECURSIVE语句

	如: WITH RECURSIVE my_tmp_table(f1,f2) 
			AS (select id,name from CreateViewTest UNION ALL select 1, 2) 
				select f1, f2 from my_tmp_table
	RECURSIVE这个关键字是可选的


	只有2可以带Parameters，它是通过这个方法调用: org.h2.table.TableView.createTempView(Session, User, String, Query, Query)
	只有3的recursive是true 
 */
public class TableView extends Table {

    private static final long ROW_COUNT_APPROXIMATION = 100;

    private String querySQL;
    private ArrayList<Table> tables;
    private Column[] columnTemplates;
    private Query viewQuery;
    private ViewIndex index;
    private boolean recursive;
    private DbException createException;
    private long lastModificationCheck;
    private long maxDataModificationId;
    private User owner;
    private Query topQuery;
    private LocalResult recursiveResult;
    private boolean tableExpression;

    public TableView(Schema schema, int id, String name, String querySQL,
            ArrayList<Parameter> params, Column[] columnTemplates, Session session,
            boolean recursive) {
        super(schema, id, name, false, true);
        init(querySQL, params, columnTemplates, session, recursive);
    }

    /**
     * Try to replace the SQL statement of the view and re-compile this and all
     * dependent views.
     *
     * @param querySQL the SQL statement
     * @param columnNames the column names
     * @param session the session
     * @param recursive whether this is a recursive view
     * @param force if errors should be ignored
     */
    public void replace(String querySQL, String[] columnNames, Session session,
            boolean recursive, boolean force) {
        String oldQuerySQL = this.querySQL;
        Column[] oldColumnTemplates = this.columnTemplates;
        boolean oldRecursive = this.recursive;
        // init里执行了一次initColumnsAndTables，虽然执行了两次initColumnsAndTables，但是init中还建立了ViewIndex
        // 所以这里是必须调用init的
        init(querySQL, null, columnTemplates, session, recursive);
        // recompile里又执行了一次initColumnsAndTables
        DbException e = recompile(session, force, true);
        if (e != null) {
            // 如果失败了，按原来的重新来过
            init(oldQuerySQL, null, oldColumnTemplates, session, oldRecursive);
            recompile(session, true, false);
            throw e;
        }
    }

    private synchronized void init(String querySQL, ArrayList<Parameter> params,
            Column[] columnTemplates, Session session, boolean recursive) {
        this.querySQL = querySQL;
        this.columnTemplates = columnTemplates;
        this.recursive = recursive;
        index = new ViewIndex(this, querySQL, params, recursive);
        initColumnsAndTables(session);
    }

    private static Query compileViewQuery(Session session, String sql) {
        Prepared p;
        session.setParsingView(true);
        try {
            p = session.prepare(sql);
        } finally {
            session.setParsingView(false);
        }
        if (!(p instanceof Query)) {
            throw DbException.getSyntaxError(sql, 0);
        }
        return (Query) p;
    }

    /**
     * Re-compile the view query and all views that depend on this object.
     *
     * @param session the session
     * @param force if exceptions should be ignored
     * @param clearIndexCache if we need to clear view index cache
     * @return the exception if re-compiling this or any dependent view failed
     *         (only when force is disabled)
     */
    public synchronized DbException recompile(Session session, boolean force,
            boolean clearIndexCache) {
        try {
            compileViewQuery(session, querySQL);
        } catch (DbException e) {
            if (!force) {
                return e;
            }
        }
        
        //如下:
        //CREATE OR REPLACE FORCE VIEW my_view(f1,f2) AS SELECT id,name FROM CreateViewTest
		//CREATE OR REPLACE FORCE VIEW view1 AS SELECT f1 FROM my_view
		//CREATE OR REPLACE FORCE VIEW view2 AS SELECT f2 FROM my_view
        //view1和view2是建立在my_view这个视图之上，当要recompile my_view时，因为view1和view2依赖了my_view
        //所以要对他们重新recompile，这里getViews()返回view1和view2
        ArrayList<TableView> views = getViews();
        if (views != null) {
            views = New.arrayList(views);
        }
        initColumnsAndTables(session);
        if (views != null) {
            for (TableView v : views) {
                DbException e = v.recompile(session, force, false);
                if (e != null && !force) {
                    return e;
                }
            }
        }
        if (clearIndexCache) {
            clearIndexCaches(database);
        }
        return force ? null : createException;
    }

    private void initColumnsAndTables(Session session) {
        Column[] cols;
        removeViewFromTables();
        try {
            Query query = compileViewQuery(session, querySQL); //重新对select语句进行解析和prepare
            this.querySQL = query.getPlanSQL();
            tables = New.arrayList(query.getTables());
            ArrayList<Expression> expressions = query.getExpressions();
            ArrayList<Column> list = New.arrayList();
            
    		//select字段个数比view字段多的情况，多出来的按select字段原来的算
    		//这里实际是f1、name
    		//sql = "CREATE OR REPLACE FORCE VIEW my_view COMMENT IS 'my view'(f1) " //
    		//		+ "AS SELECT id,name FROM CreateViewTest";

    		//select字段个数比view字段少的情况，view中少的字段被忽略
    		//这里实际是f1，而f2被忽略了，也不提示错误
    		//sql = "CREATE OR REPLACE FORCE VIEW my_view COMMENT IS 'my view'(f1, f2) " //
    		//		+ "AS SELECT id FROM CreateViewTest";

    		//不管加不加FORCE，跟上面也一样
    		//sql = "CREATE OR REPLACE VIEW my_view COMMENT IS 'my view'(f1, f2) " //
    		//		+ "AS SELECT id FROM CreateViewTest";
            
            //expressions.size有可能大于query.getColumnCount()，因为query.getColumnCount()不包含group by等额外加进来的表达式
            for (int i = 0, count = query.getColumnCount(); i < count; i++) {
                Expression expr = expressions.get(i);
                String name = null;
                //如CREATE OR REPLACE FORCE VIEW IF NOT EXISTS my_view AS SELECT id,name FROM CreateViewTest
                //没有为视图指定字段的时候，用select字段列表中的名字，此时columnTemplates==null
                int type = Value.UNKNOWN;
                if (columnTemplates != null && columnTemplates.length > i) {
                    name = columnTemplates[i].getName();
                    type = columnTemplates[i].getType();
                }
                if (name == null) {
                    name = expr.getAlias();
                }
                if (type == Value.UNKNOWN) {
                    type = expr.getType();
                }
                long precision = expr.getPrecision();
                int scale = expr.getScale();
                int displaySize = expr.getDisplaySize();
                Column col = new Column(name, type, precision, scale, displaySize);
                col.setTable(this, i);
                // Fetch check constraint from view column source
                ExpressionColumn fromColumn = null;
                if (expr instanceof ExpressionColumn) {
                    fromColumn = (ExpressionColumn) expr;
                } else if (expr instanceof Alias) {
                    Expression aliasExpr = expr.getNonAliasExpression();
                    if (aliasExpr instanceof ExpressionColumn) {
                        fromColumn = (ExpressionColumn) aliasExpr;
                    }
                }
                if (fromColumn != null) {
                    Expression checkExpression = fromColumn.getColumn()
                            .getCheckConstraint(session, name);
                    if (checkExpression != null) {
                        col.addCheckConstraint(session, checkExpression);
                    }
                }
                list.add(col);
            }
            cols = new Column[list.size()];
            list.toArray(cols);
            createException = null;
            viewQuery = query;
        } catch (DbException e) {
            e.addSQL(getCreateSQL());
            createException = e;
            // if it can't be compiled, then it's a 'zero column table'
            // this avoids problems when creating the view when opening the
            // database
            tables = New.arrayList();
            cols = new Column[0];
            if (recursive && columnTemplates != null) {
                cols = new Column[columnTemplates.length];
                for (int i = 0; i < columnTemplates.length; i++) {
                    cols[i] = columnTemplates[i].getClone();
                }
                index.setRecursive(true);
                createException = null;
            }
        }
        setColumns(cols);
        if (getId() != 0) {
            addViewToTables();
        }
    }

    @Override
    public boolean isView() {
        return true;
    }

    /**
     * Check if this view is currently invalid.
     *
     * @return true if it is
     */
    public boolean isInvalid() {
        return createException != null;
    }

    @Override
    public PlanItem getBestPlanItem(Session session, int[] masks,
            TableFilter[] filters, int filter, SortOrder sortOrder,
            HashSet<Column> allColumnsSet) {
        final CacheKey cacheKey = new CacheKey(masks, this);
        Map<Object, ViewIndex> indexCache = session.getViewIndexCache(topQuery != null);
        ViewIndex i = indexCache.get(cacheKey);
        if (i == null || i.isExpired()) {
            i = new ViewIndex(this, index, session, masks, filters, filter, sortOrder);
            indexCache.put(cacheKey, i);
        }
        PlanItem item = new PlanItem();
        item.cost = i.getCost(session, masks, filters, filter, sortOrder, allColumnsSet);
        item.setIndex(i);
        return item;
    }

    @Override
    public boolean isQueryComparable() {
        if (!super.isQueryComparable()) {
            return false;
        }
        for (Table t : tables) {
            if (!t.isQueryComparable()) {
                return false;
            }
        }
        if (topQuery != null &&
                !topQuery.isEverything(ExpressionVisitor.QUERY_COMPARABLE_VISITOR)) {
            return false;
        }
        return true;
    }

    public Query getTopQuery() {
        return topQuery;
    }

    @Override
    public String getDropSQL() {
        return "DROP VIEW IF EXISTS " + getSQL() + " CASCADE";
    }

    @Override
    public String getCreateSQLForCopy(Table table, String quotedName) {
        return getCreateSQL(false, true, quotedName);
    }


    @Override
    public String getCreateSQL() {
        return getCreateSQL(false, true);
    }

    /**
     * Generate "CREATE" SQL statement for the view.
     *
     * @param orReplace if true, then include the OR REPLACE clause
     * @param force if true, then include the FORCE clause
     * @return the SQL statement
     */
    public String getCreateSQL(boolean orReplace, boolean force) {
        return getCreateSQL(orReplace, force, getSQL());
    }

    private String getCreateSQL(boolean orReplace, boolean force,
            String quotedName) {
        StatementBuilder buff = new StatementBuilder("CREATE ");
        if (orReplace) {
            buff.append("OR REPLACE ");
        }
        if (force) {
            buff.append("FORCE ");
        }
        buff.append("VIEW ");
        buff.append(quotedName);
        if (comment != null) {
            buff.append(" COMMENT ").append(StringUtils.quoteStringSQL(comment));
        }
        if (columns != null && columns.length > 0) {
            buff.append('(');
            for (Column c : columns) {
                buff.appendExceptFirst(", ");
                buff.append(c.getSQL());
            }
            buff.append(')');
        } else if (columnTemplates != null) {
            buff.append('(');
            for (Column c : columnTemplates) {
                buff.appendExceptFirst(", ");
                buff.append(c.getName());
            }
            buff.append(')');
        }
        return buff.append(" AS\n").append(querySQL).toString();
    }

    @Override
    public void checkRename() {
        // ok
    }

    @Override
    public boolean lock(Session session, boolean exclusive, boolean forceLockEvenInMvcc) {
        // exclusive lock means: the view will be dropped
        return false;
    }

    @Override
    public void close(Session session) {
        // nothing to do
    }

    @Override
    public void unlock(Session s) {
        // nothing to do
    }

    @Override
    public boolean isLockedExclusively() {
        return false;
    }

    @Override
    public Index addIndex(Session session, String indexName, int indexId,
            IndexColumn[] cols, IndexType indexType, boolean create,
            String indexComment) {
        throw DbException.getUnsupportedException("VIEW");
    }

    @Override
    public void removeRow(Session session, Row row) {
        throw DbException.getUnsupportedException("VIEW");
    }

    @Override
    public void addRow(Session session, Row row) {
        throw DbException.getUnsupportedException("VIEW");
    }

    @Override
    public void checkSupportAlter() {
        throw DbException.getUnsupportedException("VIEW");
    }

    @Override
    public void truncate(Session session) {
        throw DbException.getUnsupportedException("VIEW");
    }

    @Override
    public long getRowCount(Session session) {
        throw DbException.throwInternalError();
    }

    @Override
    public boolean canGetRowCount() {
        // TODO view: could get the row count, but not that easy
        return false;
    }

    @Override
    public boolean canDrop() {
        return true;
    }

    @Override
    public String getTableType() {
        return Table.VIEW;
    }

    @Override
    public void removeChildrenAndResources(Session session) {
        removeViewFromTables();
        super.removeChildrenAndResources(session);
        database.removeMeta(session, getId());
        querySQL = null;
        index = null;
        clearIndexCaches(database);
        invalidate();
    }

    /**
     * Clear the cached indexes for all sessions.
     *
     * @param database the database
     */
    public static void clearIndexCaches(Database database) {
        for (Session s : database.getSessions(true)) {
            s.clearViewIndexCache();
        }
    }

    @Override
    public String getSQL() {
        if (isTemporary()) {
            return "(\n" + StringUtils.indent(querySQL) + ")";
        }
        return super.getSQL();
    }

    public String getQuery() {
        return querySQL;
    }

    @Override
    public Index getScanIndex(Session session) {
        return getBestPlanItem(session, null, null, -1, null, null).getIndex();
    }

    @Override
    public Index getScanIndex(Session session, int[] masks,
            TableFilter[] filters, int filter, SortOrder sortOrder,
            HashSet<Column> allColumnsSet) {
        if (createException != null) {
            String msg = createException.getMessage();
            throw DbException.get(ErrorCode.VIEW_IS_INVALID_2,
                    createException, getSQL(), msg);
        }
        PlanItem item = getBestPlanItem(session, masks, filters, filter, sortOrder, allColumnsSet);
        return item.getIndex();
    }

    @Override
    public boolean canReference() {
        return false;
    }

    @Override
    public ArrayList<Index> getIndexes() {
        return null;
    }

    @Override
    public long getMaxDataModificationId() {
        if (createException != null) {
            return Long.MAX_VALUE;
        }
        if (viewQuery == null) {
            return Long.MAX_VALUE;
        }
        // if nothing was modified in the database since the last check, and the
        // last is known, then we don't need to check again
        // this speeds up nested views
        long dbMod = database.getModificationDataId();
        if (dbMod > lastModificationCheck && maxDataModificationId <= dbMod) {
            maxDataModificationId = viewQuery.getMaxDataModificationId();
            lastModificationCheck = dbMod;
        }
        return maxDataModificationId;
    }

    @Override
    public Index getUniqueIndex() {
        return null;
    }

    private void removeViewFromTables() {
        if (tables != null) {
            for (Table t : tables) {
                t.removeView(this);
            }
            tables.clear();
        }
    }

    private void addViewToTables() {
        for (Table t : tables) {
            t.addView(this);
        }
    }

    private void setOwner(User owner) {
        this.owner = owner;
    }

    public User getOwner() {
        return owner;
    }

    /**
     * Create a temporary view out of the given query.
     *
     * @param session the session
     * @param owner the owner of the query
     * @param name the view name
     * @param query the query
     * @param topQuery the top level query
     * @return the view table
     */
    public static TableView createTempView(Session session, User owner,
            String name, Query query, Query topQuery) {
        Schema mainSchema = session.getDatabase().getSchema(Constants.SCHEMA_MAIN);
        String querySQL = query.getPlanSQL();
        TableView v = new TableView(mainSchema, 0, name,
                querySQL, query.getParameters(), null, session,
                false);
        if (v.createException != null) {
            throw v.createException;
        }
        v.setTopQuery(topQuery);
        v.setOwner(owner);
        v.setTemporary(true);
        return v;
    }

    private void setTopQuery(Query topQuery) {
        this.topQuery = topQuery;
    }

    @Override
    public long getRowCountApproximation() {
        return ROW_COUNT_APPROXIMATION;
    }

    @Override
    public long getDiskSpaceUsed() {
        return 0;
    }

    /**
     * Get the index of the first parameter.
     *
     * @param additionalParameters additional parameters
     * @return the index of the first parameter
     */
    public int getParameterOffset(ArrayList<Parameter> additionalParameters) {
        int result = topQuery == null ? -1 : getMaxParameterIndex(topQuery.getParameters());
        if (additionalParameters != null) {
            result = Math.max(result, getMaxParameterIndex(additionalParameters));
        }
        return result + 1;
    }

    private static int getMaxParameterIndex(ArrayList<Parameter> parameters) {
        int result = -1;
        for (Parameter p : parameters) {
            result = Math.max(result, p.getIndex());
        }
        return result;
    }

    public boolean isRecursive() {
        return recursive;
    }

    @Override
    public boolean isDeterministic() {
        if (recursive || viewQuery == null) {
            return false;
        }
        return viewQuery.isEverything(ExpressionVisitor.DETERMINISTIC_VISITOR);
    }

    public void setRecursiveResult(LocalResult value) {
        if (recursiveResult != null) {
            recursiveResult.close();
        }
        this.recursiveResult = value;
    }

    public LocalResult getRecursiveResult() {
        return recursiveResult;
    }

    public void setTableExpression(boolean tableExpression) {
        this.tableExpression = tableExpression;
    }

    public boolean isTableExpression() {
        return tableExpression;
    }

    @Override
    public void addDependencies(HashSet<DbObject> dependencies) {
        super.addDependencies(dependencies);
        if (tables != null) {
            for (Table t : tables) {
                if (!Table.VIEW.equals(t.getTableType())) {
                    t.addDependencies(dependencies);
                }
            }
        }
    }

    /**
     * The key of the index cache for views.
     */
    private static final class CacheKey {

        private final int[] masks;
        private final TableView view;

        public CacheKey(int[] masks, TableView view) {
            this.masks = masks;
            this.view = view;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + Arrays.hashCode(masks);
            result = prime * result + view.hashCode();
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            CacheKey other = (CacheKey) obj;
            if (view != other.view) {
                return false;
            }
            if (!Arrays.equals(masks, other.masks)) {
                return false;
            }
            return true;
        }
    }

}
