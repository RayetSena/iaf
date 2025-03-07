/*
   Copyright 2013, 2015, 2018, 2019 Nationale-Nederlanden, 2020, 2022 WeAreFrank!

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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import nl.nn.adapterframework.jdbc.JdbcException;
import nl.nn.adapterframework.util.JdbcUtil;

/**
 * @author  Gerrit van Brakel
 */
public class OracleDbmsSupport extends GenericDbmsSupport {

	@Override
	public Dbms getDbms() {
		return Dbms.ORACLE;
	}

	@Override
	public boolean hasSkipLockedFunctionality() {
		return true;
	}

	@Override
	public String getSysDate() {
		return "SYSDATE";
	}

	@Override
	public String getNumericKeyFieldType() {
		return "NUMBER(10)";
	}

	@Override
	public String getFromForTablelessSelect() {
		return "FROM DUAL";
	}

	@Override
	public String getAutoIncrementKeyFieldType() {
		return "NUMBER(10)";
	}

	@Override
	public boolean autoIncrementKeyMustBeInserted() {
		return true;
	}

	@Override
	public String autoIncrementInsertValue(String sequenceName) {
		return sequenceName+".NEXTVAL";
	}

	@Override
	public boolean autoIncrementUsesSequenceObject() {
		return true;
	}

	@Override
	public String getInsertedAutoIncrementValueQuery(String sequenceName) {
		return "SELECT "+sequenceName+".CURRVAL FROM DUAL";
	}

	@Override
	public String getTimestampFieldType() {
		return "TIMESTAMP";
	}

	@Override
	public String getBlobFieldType() {
		return "BLOB";
	}

	@Override
	public boolean mustInsertEmptyBlobBeforeData() {
		return true;
	}
	@Override
	public String getUpdateBlobQuery(String table, String blobField, String keyField) {
		return "SELECT "+blobField+ " FROM "+table+ " WHERE "+keyField+"=?"+ " FOR UPDATE";
	}

	@Override
	public String emptyBlobValue() {
		return "empty_blob()";
	}
	@Override
	public String getTextFieldType() {
		return "VARCHAR2";
	}


	@Override
	public String prepareQueryTextForWorkQueueReading(int batchSize, String selectQuery, int wait) throws JdbcException {
		if (StringUtils.isEmpty(selectQuery) || !selectQuery.toLowerCase().startsWith(KEYWORD_SELECT)) {
			throw new JdbcException("query ["+selectQuery+"] must start with keyword ["+KEYWORD_SELECT+"]");
		}
		/*
		 * see:
		 * http://www.psoug.org/reference/deadlocks.html
		 * http://www.psoug.org/reference/select.html
		 * http://www.ss64.com/ora/select.html
		 * http://forums.oracle.com/forums/thread.jspa?threadID=664986
		 */
		if (wait < 0) {
			return selectQuery+" FOR UPDATE SKIP LOCKED";
		} else {
			return selectQuery+" FOR UPDATE WAIT " + wait;
		}
	}


	@Override
	public String getFirstRecordQuery(String tableName) throws JdbcException {
		String query="select * from "+tableName+" where ROWNUM=1";
		return query;
	}

	@Override
	public String provideIndexHintAfterFirstKeyword(String tableName, String indexName) {
		return " /*+ INDEX ( "+tableName+ " "+indexName+" ) */ ";
	}

	@Override
	public String provideFirstRowsHintAfterFirstKeyword(int rowCount) {
		return " /*+ FIRST_ROWS( "+rowCount+" ) */ ";
	}

	@Override
	public void updateClob(ResultSet rs, int column, Object clobUpdateHandle) throws SQLException {
		// updateClob is not required for Oracle
		// rs.updateClob(column, (Clob)clobUpdateHandle);
	}
	@Override
	public void updateClob(ResultSet rs, String column, Object clobUpdateHandle) throws SQLException {
		// updateClob is not required for Oracle
		// rs.updateClob(column, (Clob)clobUpdateHandle);
	}

	@Override
	public void updateBlob(ResultSet rs, int column, Object blobUpdateHandle) throws SQLException {
		// updateBlob is not required for Oracle
		// rs.updateBlob(column, (Blob)blobUpdateHandle);
	}
	@Override
	public void updateBlob(ResultSet rs, String column, Object blobUpdateHandle) throws SQLException {
		// updateBlob is not required for Oracle
		// rs.updateBlob(column, (Blob)blobUpdateHandle);
	}

	@Override
	public String getSchema(Connection conn) throws JdbcException {
		return JdbcUtil.executeStringQuery(conn, "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL");
	}

	@Override
	public String getRowNumber(String order, String sort) {
		return "row_number() over (order by "+order+(sort==null?"":" "+sort)+") "+getRowNumberShortName();
	}

	@Override
	public String getRowNumberShortName() {
		return "rn";
	}

	@Override
	public String getBooleanFieldType() {
		return "NUMBER(1)";
	}

	@Override
	public String getBooleanValue(boolean value) {
		return (value) ? "1" : "0";
	}

	@Override
	public ResultSet getTableColumns(Connection conn, String schemaName, String tableName, String columnNamePattern) throws JdbcException {
		return super.getTableColumns(conn, schemaName, tableName.toUpperCase(), columnNamePattern!=null ? columnNamePattern.toUpperCase() : null);
	}

	@Override
	public boolean isTablePresent(Connection conn, String schemaName, String tableName) throws JdbcException {
		return super.isTablePresent(conn, schemaName, tableName.toUpperCase());
	}

	@Override
	public boolean isColumnPresent(Connection conn, String schemaName, String tableName, String columnName) throws JdbcException {
		return super.isColumnPresent(conn, schemaName, tableName.toUpperCase(), columnName.toUpperCase());
	}

	@Override
	public boolean isIndexPresent(Connection conn, String schemaOwner, String tableName, String indexName) {
		String query="select count(*) from all_indexes where owner='"+schemaOwner.toUpperCase()+"' and table_name='"+tableName.toUpperCase()+"' and index_name='"+indexName.toUpperCase()+"'";
		try {
			if (JdbcUtil.executeIntQuery(conn, query)>=1) {
				return true;
			}
			return false;
		} catch (Exception e) {
			log.warn("could not determine presence of index ["+indexName+"] on table ["+tableName+"]",e);
			return false;
		}
	}

	@Override
	public boolean isSequencePresent(Connection conn, String schemaOwner, String tableName, String sequenceName) {
		String query="select count(*) from all_sequences where sequence_owner='"+schemaOwner.toUpperCase()+"' and sequence_name='"+sequenceName.toUpperCase()+"'";
		try {
			if (JdbcUtil.executeIntQuery(conn, query)>=1) {
				return true;
			}
			return false;
		} catch (Exception e) {
			log.warn("could not determine presence of sequence ["+sequenceName+"]",e);
			return false;
		}
	}

//	@Override
//	public boolean isIndexColumnPresent(Connection conn, String schemaOwner, String tableName, String indexName, String columnName) {
//		String query="select count(*) from all_ind_columns where index_owner='"+schemaOwner.toUpperCase()+"' and table_name='"+tableName.toUpperCase()+"' and index_name='"+indexName.toUpperCase()+"' and column_name=?";
//		try {
//			if (JdbcUtil.executeIntQuery(conn, query, columnName.toUpperCase())>=1) {
//				return true;
//			}
//			return false;
//		} catch (Exception e) {
//			log.warn("could not determine correct presence of column ["+columnName+"] of index ["+indexName+"] on table ["+tableName+"]",e);
//			return false;
//		}
//	}
//
//	@Override
//	public int getIndexColumnPosition(Connection conn, String schemaOwner, String tableName, String indexName, String columnName) {
//		String query="select column_position from all_ind_columns where index_owner='"+schemaOwner.toUpperCase()+"' and table_name='"+tableName.toUpperCase()+"' and index_name='"+indexName.toUpperCase()+"' and column_name=?";
//		try {
//			return JdbcUtil.executeIntQuery(conn, query, columnName.toUpperCase());
//		} catch (Exception e) {
//			log.warn("could not determine correct presence of column ["+columnName+"] of index ["+indexName+"] on table ["+tableName+"]",e);
//			return -1;
//		}
//	}

	@Override
	public boolean hasIndexOnColumn(Connection conn, String schemaOwner, String tableName, String columnName) throws JdbcException {
		return super.hasIndexOnColumn(conn, schemaOwner.toUpperCase(), tableName.toUpperCase(), columnName.toUpperCase());
	}

	@Override
	public boolean hasIndexOnColumns(Connection conn, String schemaOwner, String tableName, List<String> columns) {
		List<String> columnsUC = columns.stream().map(c -> c.toUpperCase()).collect(Collectors.toList());
		return doHasIndexOnColumns(conn, schemaOwner.toUpperCase(), tableName.toUpperCase(), columnsUC,
				"all_indexes", "all_ind_columns",
				"TABLE_OWNER", "TABLE_NAME", "INDEX_NAME", "column_name", "column_position");
	}


}
