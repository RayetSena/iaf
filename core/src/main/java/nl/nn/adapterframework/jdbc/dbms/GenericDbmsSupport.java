/*
   Copyright 2013, 2015, 2018, 2019 Nationale-Nederlanden, 2020-2022 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package nl.nn.adapterframework.jdbc.dbms;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import nl.nn.adapterframework.core.IMessageBrowser;
import nl.nn.adapterframework.jdbc.JdbcException;
import nl.nn.adapterframework.jdbc.QueryExecutionContext;
import nl.nn.adapterframework.util.JdbcUtil;
import nl.nn.adapterframework.util.LogUtil;
import nl.nn.adapterframework.util.StringUtil;

/**
 * @author  Gerrit van Brakel
 */
public class GenericDbmsSupport implements IDbmsSupport {
	protected Logger log = LogUtil.getLogger(this.getClass());

	protected static final String KEYWORD_SELECT="select";

	protected static final String TYPE_BLOB = "blob";
	protected static final String TYPE_CLOB = "clob";
	protected static final String TYPE_FUNCTION = "function";

	protected static Map<String,ISqlTranslator> sqlTranslators = new HashMap<>();

	@Override
	public String getDbmsName() {
		return getDbms().getKey();
	}

	@Override
	public Dbms getDbms() {
		return Dbms.GENERIC;
	}

	@Override
	public boolean isParameterTypeMatchRequired() {
		return false;
	}
	@Override
	public boolean hasSkipLockedFunctionality() {
		return false;
	}

	@Override
	public String getSysDate() {
		return "NOW()";
	}
	@Override
	public String getDateAndOffset(String dateValue, int daysOffset) {
		return dateValue+ " + "+daysOffset;
	}

	@Override
	public String getNumericKeyFieldType() {
		return "INT";
	}

	@Override
	public String getFromForTablelessSelect() {
		return "";
	}

	@Override
	public String getAutoIncrementKeyFieldType() {
		return "INT DEFAULT AUTOINCREMENT";
	}

	@Override
	public boolean autoIncrementKeyMustBeInserted() {
		return false;
	}

	@Override
	public String autoIncrementInsertValue(String sequenceName) {
		return null;
	}

	@Override
	public boolean autoIncrementUsesSequenceObject() {
		return false;
	}

	@Override
	public String getInsertedAutoIncrementValueQuery(String sequenceName) {
		return null;
	}

	@Override
	public String getIbisStoreSummaryQuery() {
		// include a where clause, to make MsSqlServerDbmsSupport.prepareQueryTextForNonLockingRead() work
		return "select type, slotid, " + getTimestampAsDate("MESSAGEDATE")+ " msgdate, count(*) msgcount from IBISSTORE where 1=1 group by slotid, type, " + getTimestampAsDate("MESSAGEDATE")+ " order by type, slotid, " + getTimestampAsDate("MESSAGEDATE");
	}


	@Override
	public String getTimestampFieldType() {
		return "TIMESTAMP";
	}
	// method is used in JobDef.cleanupDatabase
	@Override
	public String getDatetimeLiteral(Date date) {
		SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		String formattedDate = formatter.format(date);
		return "TO_TIMESTAMP('" + formattedDate + "', 'YYYY-MM-DD HH24:MI:SS')";
	}
	@Override
	public String getTimestampAsDate(String columnName) {
		return "TO_CHAR("+columnName+",'YYYY-MM-DD')";
	}

	@Override
	public String getClobFieldType() {
		return "CLOB";
	}
	@Override
	public boolean isClobType(final ResultSetMetaData rsmeta, final int colNum) throws SQLException {
		switch (rsmeta.getColumnType(colNum)) {
		case Types.LONGVARCHAR:
		case Types.LONGNVARCHAR:
		case Types.CLOB:
			return true;
		default:
			return false;
		}
	}
	@Override
	public boolean mustInsertEmptyClobBeforeData() {
		return false;
	}
	@Override
	public String getUpdateClobQuery(String table, String clobField, String keyField) {
		return null;
	}
	@Override
	public String emptyClobValue() {
		return "''";
	}

	@Override
	public Object getClobHandle(ResultSet rs, int column) throws SQLException, JdbcException {
		return rs.getClob(column);
	}
	@Override
	public Object getClobHandle(ResultSet rs, String column) throws SQLException, JdbcException {
		return rs.getClob(column);
	}
	@Override
	public Writer getClobWriter(ResultSet rs, int column, Object clobHandle) throws SQLException, JdbcException {
		return ((Clob)clobHandle).setCharacterStream(1L);
	}
	@Override
	public Writer getClobWriter(ResultSet rs, String column, Object clobHandle) throws SQLException, JdbcException {
		return ((Clob)clobHandle).setCharacterStream(1L);
	}
	@Override
	public void updateClob(ResultSet rs, int column, Object clobHandle) throws SQLException, JdbcException {
		rs.updateClob(column, (Clob)clobHandle);
	}
	@Override
	public void updateClob(ResultSet rs, String column, Object clobHandle) throws SQLException, JdbcException {
		rs.updateClob(column, (Clob)clobHandle);
	}


	@Override
	public Object getClobHandle(PreparedStatement stmt, int column) throws SQLException, JdbcException {
		return stmt.getConnection().createClob();
	}
	@Override
	public Writer getClobWriter(PreparedStatement stmt, int column, Object clobHandle) throws SQLException, JdbcException {
		return ((Clob)clobHandle).setCharacterStream(1L);
	}
	@Override
	public void applyClobParameter(PreparedStatement stmt, int column, Object clobHandle) throws SQLException, JdbcException {
		stmt.setClob(column, (Clob)clobHandle);
	}

	@Override
	public Reader getClobReader(ResultSet rs, int column) throws SQLException, JdbcException {
		Clob clob = rs.getClob(column);
		if (clob==null) {
			return null;
		}
		return clob.getCharacterStream();
	}
	@Override
	public Reader getClobReader(ResultSet rs, String column) throws SQLException, JdbcException {
		Clob clob = rs.getClob(column);
		if (clob==null) {
			return null;
		}
		return clob.getCharacterStream();
	}


	@Override
	public String getBlobFieldType() {
		return "BLOB";
	}
	@Override
	public boolean isBlobType(final ResultSetMetaData rsmeta, final int colNum) throws SQLException {
		switch (rsmeta.getColumnType(colNum)) {
		case Types.LONGVARBINARY:
		case Types.VARBINARY:
		case Types.BINARY:
		case Types.BLOB:
			return true;
		default:
			return false;
		}
	}
	@Override
	public boolean mustInsertEmptyBlobBeforeData() {
		return false;
	}
	@Override
	public String getUpdateBlobQuery(String table, String blobField, String keyField) {
		return null;
	}
	@Override
	public String emptyBlobValue() {
		return "''";
	}

	@Override
	public Object getBlobHandle(ResultSet rs, int column) throws SQLException, JdbcException {
		return rs.getBlob(column);
	}
	@Override
	public Object getBlobHandle(ResultSet rs, String column) throws SQLException, JdbcException {
		return rs.getBlob(column);
	}

	protected  OutputStream getBlobOutputStream(ResultSet rs, Object blobUpdateHandle) throws SQLException, JdbcException {
		return ((Blob)blobUpdateHandle).setBinaryStream(1L);
	}
	@Override
	public OutputStream getBlobOutputStream(ResultSet rs, int column, Object blobUpdateHandle) throws SQLException, JdbcException {
		return getBlobOutputStream(rs,blobUpdateHandle);
	}
	@Override
	public OutputStream getBlobOutputStream(ResultSet rs, String column, Object blobUpdateHandle) throws SQLException, JdbcException {
		return getBlobOutputStream(rs,blobUpdateHandle);
	}
	@Override
	public void updateBlob(ResultSet rs, int column, Object blobUpdateHandle) throws SQLException, JdbcException {
		rs.updateBlob(column, (Blob)blobUpdateHandle);
	}
	@Override
	public void updateBlob(ResultSet rs, String column, Object blobUpdateHandle) throws SQLException, JdbcException {
		rs.updateBlob(column, (Blob)blobUpdateHandle);
	}

	@Override
	public Object getBlobHandle(PreparedStatement stmt, int column) throws SQLException, JdbcException {
		return stmt.getConnection().createBlob();
	}
	@Override
	public OutputStream getBlobOutputStream(PreparedStatement stmt, int column, Object blobInsertHandle) throws SQLException, JdbcException {
		return ((Blob)blobInsertHandle).setBinaryStream(1L);
	}
	@Override
	public void applyBlobParameter(PreparedStatement stmt, int column, Object blobInsertHandle) throws SQLException, JdbcException {
		stmt.setBlob(column, (Blob)blobInsertHandle);
	}


	@Override
	public InputStream getBlobInputStream(ResultSet rs, int column) throws SQLException, JdbcException {
		Blob blob = rs.getBlob(column);
		if (blob==null) {
			return null;
		}
		return blob.getBinaryStream();
	}
	@Override
	public InputStream getBlobInputStream(ResultSet rs, String column) throws SQLException, JdbcException{
		Blob blob = rs.getBlob(column);
		if (blob==null) {
			return null;
		}
		return blob.getBinaryStream();
	}



	@Override
	public String getTextFieldType() {
		return "VARCHAR";
	}


	@Override
	public String prepareQueryTextForWorkQueueReading(int batchSize, String selectQuery) throws JdbcException {
		return prepareQueryTextForWorkQueueReading(batchSize, selectQuery, -1);
	}

	@Override
	public String prepareQueryTextForWorkQueueReading(int batchSize, String selectQuery, int wait) throws JdbcException {
		if (StringUtils.isEmpty(selectQuery) || !selectQuery.toLowerCase().startsWith(KEYWORD_SELECT)) {
			throw new JdbcException("query ["+selectQuery+"] must start with keyword ["+KEYWORD_SELECT+"]");
		}
		log.warn("don't know how to perform prepareQueryTextForWorkQueueReading for this database type, doing a guess...");
		return selectQuery+" FOR UPDATE";
	}

	@Override
	public String prepareQueryTextForWorkQueuePeeking(int batchSize, String selectQuery) throws JdbcException {
		return prepareQueryTextForWorkQueuePeeking(batchSize, selectQuery, -1);
	}
	@Override
	public String prepareQueryTextForWorkQueuePeeking(int batchSize, String selectQuery, int wait) throws JdbcException {
		return selectQuery;
	}

	@Override
	public String getFirstRecordQuery(String tableName) throws JdbcException {
		log.warn("don't know how to perform getFirstRecordQuery for this database type, doing a guess...");
		String query="select * from "+tableName+" where ROWNUM=1";
		return query;
	}

	@Override
	public String prepareQueryTextForNonLockingRead(String selectQuery) throws JdbcException {
		return selectQuery;
	}
	@Override
	public JdbcSession prepareSessionForNonLockingRead(Connection conn) throws JdbcException {
		return null;
	}


	@Override
	public String provideIndexHintAfterFirstKeyword(String tableName, String indexName) {
		return "";
	}

	@Override
	public String provideFirstRowsHintAfterFirstKeyword(int rowCount) {
		return "";
	}
	@Override
	public String provideTrailingFirstRowsHint(int rowCount) {
		return "";
	}


	@Override
	public String getSchema(Connection conn) throws JdbcException {
		return null;
	}

	@Override
	public ResultSet getTableColumns(Connection conn, String tableName) throws JdbcException {
		return getTableColumns(conn, null, tableName);
	}

	@Override
	public ResultSet getTableColumns(Connection conn, String schemaName, String tableName) throws JdbcException {
		return getTableColumns(conn, schemaName, tableName, null);
	}

	@Override
	public ResultSet getTableColumns(Connection conn, String schemaName, String tableName, String columnNamePattern) throws JdbcException {
		try {
			return conn.getMetaData().getColumns(null, schemaName, tableName, columnNamePattern);
		} catch (SQLException e) {
			throw new JdbcException("exception retrieving columns for table [" + tableName + "]", e);
		}
	}

	@Override
	public boolean isTablePresent(Connection conn, String tableName) throws JdbcException {
		return isTablePresent(conn, null, tableName);
	}

	@Override
	public boolean isTablePresent(Connection conn, String schemaName, String tableName) throws JdbcException {
		try (ResultSet rs = conn.getMetaData().getTables(null, schemaName, tableName, null)) {
			return rs.next(); // rs.isAfterLast() does not work properly when rs.next() has not yet been called
		} catch (SQLException e) {
			throw new JdbcException("exception checking for existence of table [" + tableName + "]"+(schemaName==null?"":" with schema ["+schemaName+"]"), e);
		}
	}

	@Override
	public boolean isColumnPresent(Connection conn, String tableName, String columnName) throws JdbcException {
		return this.isColumnPresent(conn, null, tableName, columnName);
	}

	@Override
	public boolean isColumnPresent(Connection conn, String schemaName, String tableName, String columnName) throws JdbcException {
		try (ResultSet rs = conn.getMetaData().getColumns(null, schemaName, tableName, columnName)) {
			return rs.next(); // rs.isAfterLast() does not work properly when rs.next() has not yet been called
		} catch(SQLException e) {
			throw new JdbcException("exception checking for existence of column ["+columnName+"] in table ["+tableName+"]"+(schemaName==null?"":" with schema ["+schemaName+"]"), e);
		}
	}

	@Override
	public boolean hasIndexOnColumn(Connection conn, String schemaName, String tableName, String columnName) throws JdbcException {
		try (ResultSet rs = conn.getMetaData().getIndexInfo(null, schemaName, tableName, false, true)) {
			while (rs.next()) {
				if (tableName.equalsIgnoreCase(rs.getString("TABLE_NAME")) && columnName.equalsIgnoreCase(rs.getString("COLUMN_NAME")) && rs.getInt("ORDINAL_POSITION")==1) {
					return true;
				}
			}
			return false;
		} catch(SQLException e) {
			throw new JdbcException("exception checking for existence of column ["+columnName+"] in table ["+tableName+"]"+(schemaName==null?"":" with schema ["+schemaName+"]"), e);
		}
	}

	protected boolean doHasIndexOnColumns(Connection conn, String schemaOwner, String tableName, List<String> columns, String indexTableName, String indexColumnTableName,
			String tableOwnerColumnName, String tableNameColumnName, String indexNameColumnName, String columnNameColumnName, String columPositionColumnName) {
		StringBuilder query= new StringBuilder("select count(*) from "+indexTableName+" ai");
		for (int i=1;i<=columns.size();i++) {
			query.append(", "+indexColumnTableName+" aic"+i);
		}
		query.append(" where ai."+tableNameColumnName+"='"+tableName+"'");
		if (tableOwnerColumnName!=null) {
			query.append(" and ai."+tableOwnerColumnName+"='"+schemaOwner+"'");
		}
		for (int i=1;i<=columns.size();i++) {
//			query.append(" and ai."+indexOwnerColumnName+"=aic"+i+"."+indexOwnerColumnName);
			query.append(" and ai."+indexNameColumnName+"=aic"+i+"."+indexNameColumnName);
			query.append(" and aic"+i+"."+columnNameColumnName+"='"+columns.get(i-1)+"'");
			query.append(" and aic"+i+"."+columPositionColumnName+"="+i);
		}
		try {
			return JdbcUtil.executeIntQuery(conn, query.toString())>=1;
		} catch (Exception e) {
			log.warn("could not determine presence of index columns on table ["+tableName+"] using query ["+query+"]",e);
			return false;
		}
	}

	/**
	 * Alternative implementation of isTablePresent(), that can be used by descender classes if the implementation via metadata does not work for that driver.
	 */
	protected boolean doIsTablePresent(Connection conn, String tablesTable, String schemaColumn, String tableNameColumn, String schemaName, String tableName) throws JdbcException {
		String query="select count(*) from "+tablesTable+" where upper("+tableNameColumn+")=?";
		if (StringUtils.isNotEmpty(schemaName)) {
			if (StringUtils.isNotEmpty(schemaColumn)) {
				query+=" and upper("+schemaColumn+")='"+schemaName.toUpperCase()+"'";
			} else {
				throw new JdbcException("no schemaColumn present in table ["+tablesTable+"] to test for presence of table ["+tableName+"] in schema ["+schemaName+"]");
			}
		}
		try {
			return JdbcUtil.executeIntQuery(conn, query, tableName.toUpperCase())>=1;
		} catch (Exception e) {
			log.warn("could not determine presence of table ["+tableName+"]",e);
			return false;
		}
	}

	/**
	 * Alternative implementation of isColumnPresent(), that can be used by descender classes if the implementation via metadata does not work for that driver.
	 */
	protected boolean doIsColumnPresent(Connection conn, String columnsTable, String schemaColumn, String tableNameColumn, String columnNameColumn, String schemaName, String tableName, String columnName) throws JdbcException {
		String query="select count(*) from "+columnsTable+" where upper("+tableNameColumn+")=? and upper("+columnNameColumn+")=?";
		if (StringUtils.isNotEmpty(schemaName)) {
			if (StringUtils.isNotEmpty(schemaColumn)) {
				query+=" and upper("+schemaColumn+")='"+schemaName.toUpperCase()+"'";
			} else {
				throw new JdbcException("no schemaColumn present in table ["+columnsTable+"] to test for presence of column ["+columnName+"] of table ["+tableName+"] in schema ["+schemaName+"]");
			}
		}
		try {
			return JdbcUtil.executeIntQuery(conn, query, tableName.toUpperCase(), columnName.toUpperCase())>=1;
		} catch (Exception e) {
			log.warn("could not determine correct presence of column ["+columnName+"] of table ["+tableName+"]",e);
			return false;
		}
	}

	@Override
	public boolean isIndexPresent(Connection conn, String schemaOwner, String tableName, String indexName) {
		log.warn("could not determine presence of index ["+indexName+"] on table ["+tableName+"]");
		return true;
	}

	@Override
	public boolean isSequencePresent(Connection conn, String schemaOwner, String tableName, String sequenceName) {
		log.warn("could not determine presence of sequence ["+sequenceName+"]");
		return true;
	}

	@Override
	public boolean hasIndexOnColumns(Connection conn, String schemaOwner, String tableName, List<String> columns) throws JdbcException {
		log.warn("could not determine presence of index columns on table ["+tableName+"]");
		return true;
	}

	@Override
	public String getSchemaOwner(Connection conn) throws SQLException, JdbcException {
		log.warn("could not determine current schema");
		return "";
	}

	@Override
	public boolean isConstraintViolation(SQLException e) {
		String sqlState = e.getSQLState();
		return sqlState!=null && sqlState.startsWith("23");
	}

	@Override
	public String getRowNumber(String order, String sort) {
		return "";
	}

	@Override
	public String getRowNumberShortName() {
		return "";
	}

	@Override
	public String getLength(String column) {
		return "LENGTH("+column+")";
	}

	@Override
	public String getBooleanFieldType() {
		return "BOOLEAN";
	}

	@Override
	public String getBooleanValue(boolean value) {
		return (""+value).toUpperCase();
	}

	protected ISqlTranslator createTranslator(String source, String target) throws JdbcException {
		return new SqlTranslator(source, target);
	}

	@Override
	public void convertQuery(QueryExecutionContext queryExecutionContext, String sqlDialectFrom) throws SQLException, JdbcException {
		if (isQueryConversionRequired(sqlDialectFrom)) {
			String translatorKey = sqlDialectFrom+"->"+getDbmsName();
			ISqlTranslator translator = sqlTranslators.get(translatorKey);
			if (translator==null) {
				if (sqlTranslators.containsKey(sqlDialectFrom)) {
					// if translator==null, but the key is present in the map,
					// then we already tried to setup this translator, and did not succeed.
					// No need to try again.
					warnConvertQuery(sqlDialectFrom);
					return;
				}
				try {
					translator = createTranslator(sqlDialectFrom, getDbmsName());
				} catch (IllegalArgumentException e) {
					warnConvertQuery(sqlDialectFrom);
					sqlTranslators.put(translatorKey, null);
					return;
				} catch (Exception e) {
					throw new JdbcException("Could not translate sql query from " + sqlDialectFrom + " to " + getDbmsName(), e);
				}
				if (!translator.canConvert(sqlDialectFrom, getDbmsName())) {
					warnConvertQuery(sqlDialectFrom);
					sqlTranslators.put(sqlDialectFrom, null); // avoid trying to set up the translator again the next time
					return;
				}
				sqlTranslators.put(translatorKey, translator);
			}
			List<String> multipleQueries = splitQuery(queryExecutionContext.getQuery());
			StringBuilder convertedQueries = null;
			for (String singleQuery : multipleQueries) {
				String convertedQuery = translator.translate(singleQuery);
				if (convertedQuery != null) {
					if (convertedQueries==null) {
						convertedQueries = new StringBuilder();
					} else {
						convertedQueries.append("\n");
					}
					convertedQueries.append(convertedQuery);
				}
			}
			queryExecutionContext.setQuery(convertedQueries!=null?convertedQueries.toString():"");
		}
	}

	protected void warnConvertQuery(String sqlDialectFrom) {
		log.warn("don't know how to convert queries from [" + sqlDialectFrom + "] to [" + getDbmsName() + "]");
	}

	protected boolean isQueryConversionRequired(String sqlDialectFrom) {
		return StringUtils.isNotEmpty(sqlDialectFrom) && StringUtils.isNotEmpty(getDbmsName()) && !sqlDialectFrom.equalsIgnoreCase(getDbmsName());
	}

	protected List<String> splitQuery(String query) {
		// A query can contain multiple queries separated by a semicolon
		List<String> splittedQueries = new ArrayList<>();
		if (!query.contains(";")) {
			splittedQueries.add(query);
		} else {
			int i = 0;
			int j = 0;
			while (j < query.length()) {
				if (query.charAt(j) == ';') {
					String line = query.substring(i, j + 1);
					// A semicolon between single quotes is ignored (number of single quotes in the query must be zero or an even number)
					int countApos = StringUtils.countMatches(line, "'");
					// A semicolon directly after 'END' is ignored when there is also a 'BEGIN' in the query
					int countBegin = StringUtil.countRegex(line.toUpperCase().replaceAll("\\s+", "  "), "\\sBEGIN\\s");
					int countEnd = StringUtil.countRegex(line.toUpperCase().replaceAll(";", "; "), "\\sEND;");
					if ((countApos == 0 || (countApos & 1) == 0) && countBegin==countEnd) {
						splittedQueries.add(line.trim());
						i = j + 1;
					}
				}
				j++;
			}
			if (j > i)
				splittedQueries.add(query.substring(i, j).trim());
		}
		return splittedQueries;
	}

	@Override
	public String getCleanUpIbisstoreQuery(String tableName, String keyField, String typeField, String expiryDateField, int maxRows) {
		String query = ("DELETE FROM " + tableName + " WHERE " + keyField + " IN (SELECT " + keyField + " FROM " + tableName
		+ " WHERE " + typeField + " IN ('" + IMessageBrowser.StorageType.MESSAGELOG_PIPE.getCode() + "','" + IMessageBrowser.StorageType.MESSAGELOG_RECEIVER.getCode()
		+ "') AND " + expiryDateField + " < ?"+(maxRows>0?" FETCH FIRST "+maxRows+ " ROWS ONLY":"")+")");
		return query;
	}
}
