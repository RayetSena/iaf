/*
   Copyright 2013, 2018 Nationale-Nederlanden, 2020-2022 WeAreFrank!

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
package nl.nn.adapterframework.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import lombok.Getter;
import lombok.Setter;
import nl.nn.adapterframework.configuration.ConfigurationException;
import nl.nn.adapterframework.core.HasTransactionAttribute;
import nl.nn.adapterframework.core.IbisTransaction;
import nl.nn.adapterframework.core.TransactionAttribute;
import nl.nn.adapterframework.core.TransactionAttributes;
import nl.nn.adapterframework.doc.Mandatory;
import nl.nn.adapterframework.jdbc.JdbcException;
import nl.nn.adapterframework.jdbc.JdbcFacade;
import nl.nn.adapterframework.task.TimeoutGuard;
import nl.nn.adapterframework.util.MessageKeeper.MessageKeeperLevel;

/**
 * Locker of scheduler jobs and pipes.
 *
 * Tries to set a lock (by inserting a record in the database table IbisLock) and only if this is done
 * successfully the job is executed.
 *
 * For an Oracle database the following objects are used:
 *  <pre>
	CREATE TABLE &lt;schema_owner&gt;.IBISLOCK
	(
	OBJECTID VARCHAR2(100 CHAR),
	TYPE CHAR(1 CHAR),
	HOST VARCHAR2(100 CHAR),
	CREATIONDATE TIMESTAMP(6),
	EXPIRYDATE TIMESTAMP(6)
	CONSTRAINT PK_IBISLOCK PRIMARY KEY (OBJECTID)
	);

	CREATE INDEX &lt;schema_owner&gt;.IX_IBISLOCK ON &lt;schema_owner&gt;.IBISLOCK
	(EXPIRYDATE);

	GRANT DELETE, INSERT, SELECT, UPDATE ON &lt;schema_owner&gt;.IBISLOCK TO &lt;rolename&gt;;
	GRANT SELECT ON SYS.DBA_PENDING_TRANSACTIONS TO &lt;rolename&gt;;

	COMMIT;
 *  </pre>
 *
 * @author  Peter Leeuwenburgh
 */
public class Locker extends JdbcFacade implements HasTransactionAttribute {
	private static final String LOCK_IGNORED="%null%";

	private @Getter String objectId;
	private @Getter LockType type = LockType.T;
	private @Getter String dateFormatSuffix;
	private @Getter int retention = -1;
	private String insertQuery = "INSERT INTO IBISLOCK (objectId, type, host, creationDate, expiryDate) VALUES (?, ?, ?, ?, ?)";
	private String deleteQuery = "DELETE FROM IBISLOCK WHERE objectId=?";
	private String selectQuery = "SELECT type, host, creationDate, expiryDate FROM IBISLOCK WHERE objectId=?";
	private SimpleDateFormat formatter;
	private @Getter int numRetries = 0;
	private @Getter int firstDelay = 0;
	private @Getter int retryDelay = 10000;
	private @Getter boolean ignoreTableNotExist = false;

	private @Getter @Setter TransactionAttribute transactionAttribute=TransactionAttribute.SUPPORTS;
	private @Getter @Setter int transactionTimeout = 0;
	private @Getter int lockWaitTimeout = 0;

	private @Getter @Setter PlatformTransactionManager txManager;
	private @Getter TransactionDefinition txDef = null;

	public enum LockType {
		/** Temporary */
		T,
		/** Permanent */
		P
	}

	@Override
	public void configure() throws ConfigurationException {
		super.configure();
		txDef = TransactionAttributes.configureTransactionAttributes(log, getTransactionAttribute(), getTransactionTimeout());
		if (StringUtils.isEmpty(getObjectId())) {
			throw new ConfigurationException(getLogPrefix()+ "an objectId must be specified");
		}
		if (StringUtils.isNotEmpty(getDateFormatSuffix())) {
			try {
				formatter = new SimpleDateFormat(getDateFormatSuffix());
			} catch (IllegalArgumentException ex){
				throw new ConfigurationException(getLogPrefix()+"has an illegal value for dateFormat", ex);
			}
		}
		if (retention<0) {
			if (getType()==LockType.T) {
				retention = 4;
			} else {
				retention = 30;
			}
		}
	}

	public String acquire() throws JdbcException, SQLException, InterruptedException {
		return acquire(null);
	}

	/**
	 * Obtain the lock. If successful, the non-null lockId is returned.
	 * If the lock cannot be obtained within the lock-timeout because it is held by another party, null is returned.
	 * The lock wait timeout of the database can be overridden by setting lockWaitTimeout.
	 * A wait timeout beyond the basic lockWaitTimeout and transactionTimeout can be set using numRetries in combination with retryDelay.
	 *
	 */
	public String acquire(MessageKeeper messageKeeper) throws JdbcException, SQLException, InterruptedException {

		try (Connection conn = getConnection()) {
			if (!getDbmsSupport().isTablePresent(conn, "IBISLOCK")) {
				if (isIgnoreTableNotExist()) {
					log.info("table [IBISLOCK] does not exist, ignoring lock");
					return LOCK_IGNORED;
				}
				throw new JdbcException("table [IBISLOCK] does not exist");
			}
		}

		String objectIdWithSuffix = null;
		int r = -1;
		while (objectIdWithSuffix == null && (numRetries == -1 || r < numRetries)) {
			r++;
			if (r == 0 && firstDelay > 0) {
				Thread.sleep(firstDelay);
			}
			if (r > 0) {
				Thread.sleep(retryDelay);
			}
			IbisTransaction itx = IbisTransaction.getTransaction(getTxManager(), getTxDef(), "locker ["+getName()+"]");
			try {
				Date date = new Date();
				objectIdWithSuffix = getObjectId();
				if (StringUtils.isNotEmpty(getDateFormatSuffix())) {
					String formattedDate = formatter.format(date);
					objectIdWithSuffix = objectIdWithSuffix.concat(formattedDate);
				}

				boolean timeout = false;
				log.debug("preparing to set lock [" + objectIdWithSuffix + "]");
				try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(insertQuery)) {
					stmt.clearParameters();
					stmt.setString(1,objectIdWithSuffix);
					stmt.setString(2,getType().name());
					stmt.setString(3,Misc.getHostname());
					stmt.setTimestamp(4, new Timestamp(date.getTime()));
					Calendar cal = Calendar.getInstance();
					cal.setTime(date);
					if (getType()==LockType.T) {
						cal.add(Calendar.HOUR_OF_DAY, getRetention());
					} else {
						cal.add(Calendar.DAY_OF_MONTH, getRetention());
					}
					stmt.setTimestamp(5, new Timestamp(cal.getTime().getTime()));
					TimeoutGuard timeoutGuard = null;
					if (lockWaitTimeout > 0) {
						timeoutGuard = new TimeoutGuard(lockWaitTimeout, "lockWaitTimeout") {

							@Override
							protected void abort() {
								try {
									stmt.cancel();
								} catch (SQLException e) {
									log.warn("Could not cancel statement",e);
								}
							}
						};
					}
					try {
						log.debug("lock ["+objectIdWithSuffix+"] inserting...");
						stmt.executeUpdate();
						log.debug("lock ["+objectIdWithSuffix+"] inserted executed");
					} finally {
						if (timeoutGuard!=null && timeoutGuard.cancel()) {
							log.warn("Timeout obtaining lock ["+objectId+"]");
							if(itx != null) {
								itx.setRollbackOnly();
							}
							timeout=true;
							return null;
						}
					}
				} catch (Exception e) {
					if(itx != null) {
						itx.setRollbackOnly();
					}
					log.debug(getLogPrefix()+"error executing insert query (as part of locker): ",e);
					if (numRetries == -1 || r < numRetries) {
						log.debug(getLogPrefix()+"will try again");
					} else {
						log.debug(getLogPrefix()+"will not try again");

						if (timeout || e instanceof SQLTimeoutException || e instanceof SQLException && getDbmsSupport().isConstraintViolation((SQLException)e)) {
							String msg = "could not obtain lock "+getLockerInfo(objectIdWithSuffix)+" ("+e.getClass().getTypeName()+"): " + e.getMessage();
							if(messageKeeper != null) {
								messageKeeper.add(msg, MessageKeeperLevel.INFO);
							}
							log.info(getLogPrefix()+msg);
						} else {
							throw e;
						}
					}
					return null;
				}
			} finally {
				if(itx != null) {
					itx.commit();
				}
			}
		}
		return objectIdWithSuffix;
	}

	public void release(String objectIdWithSuffix) throws JdbcException, SQLException {
		if (LOCK_IGNORED.equals(objectIdWithSuffix)) {
			log.info("lock not set, ignoring unlock");
		} else {
			if (getType()==LockType.T) {
				log.debug("preparing to remove lock [" + objectIdWithSuffix + "]");
				IbisTransaction itx = IbisTransaction.getTransaction(getTxManager(), getTxDef(), "locker ["+getName()+"]");

				try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(deleteQuery)) {
					stmt.clearParameters();
					stmt.setString(1,objectIdWithSuffix);
					stmt.executeUpdate();
					log.debug("lock ["+objectIdWithSuffix+"] removed");
				} catch(JdbcException | SQLException e) {
					if(itx != null) {
						itx.setRollbackOnly();
					}
					throw e;
				} finally {
					if(itx != null) {
						itx.commit();
					}
				}
			}
		}
	}

	public String getLockerInfo(String objectIdWithSuffix) {
		try {
			String query = getDbmsSupport().prepareQueryTextForNonLockingRead(selectQuery);
			try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(query)) {
				stmt.clearParameters();
				stmt.setString(1,objectIdWithSuffix);

				try (ResultSet rs = stmt.executeQuery()) {
					if (rs.next()) {
						String info = "objectId ["+objectId+"] of type ["+rs.getString(1)+"]. Process locked by host ["+rs.getString(2)+"] at ["+DateUtils.format(rs.getTimestamp(3))+"] with expiry date ["+DateUtils.format(rs.getTimestamp(4))+"]";
						return info;
					}
					return "(no locker info found)";
				}
			}
		} catch (Exception e) {
			return "(cannot get locker info: ("+ClassUtils.nameOf(e)+") "+e.getMessage()+")";
		}
	}

	@Override
	protected String getLogPrefix() {
		return getName()+" ";
	}

	@Override
	public String toString() {
		return getLogPrefix()+" type ["+getType()+"] objectId ["+getObjectId()+"] transactionAttribute ["+getTransactionAttribute()+"]";
	}


	/** Identifier for this lock */
	@Mandatory
	public void setObjectId(String objectId) {
		this.objectId = objectId;
	}

	/**
	 * Type for this lock: P(ermanent) or T(emporary). A temporary lock is released after the job has completed
	 * @ff.default T
	 */
	public void setType(LockType type) {
		this.type = type;
	}

	/** Format for date which is added after <code>objectid</code> (e.g. yyyyMMdd to be sure the job is executed only once a day) */
	public void setDateFormatSuffix(String dateFormatSuffix) {
		this.dateFormatSuffix = dateFormatSuffix;
	}

	/**
	 * The time (for type=P in days and for type=T in hours) to keep the record in the database before making it eligible for deletion by a cleanup process
	 * @ff.default 30 days (type=P), 4 hours (type=T)
	 */
	public void setRetention(int retention) {
		this.retention = retention;
	}

	/**
	 * The number of times an attempt should be made to acquire a lock, after this many times an exception is thrown when no lock could be acquired, when -1 the number of retries is unlimited
	 * @ff.default 0
	 */
	public void setNumRetries(int numRetries) {
		this.numRetries = numRetries;
	}

	/**
	 * The time in ms to wait before the first attempt to acquire a lock is made
	 * @ff.default 0
	 */
	public void setFirstDelay(int firstDelay) {
		this.firstDelay = firstDelay;
	}

	/**
	 * The time in ms to wait before another attempt to acquire a lock is made
	 * @ff.default 10000
	 */
	public void setRetryDelay(int retryDelay) {
		this.retryDelay = retryDelay;
	}

	/**
	 * If > 0: The time in s to wait before the INSERT statement to obtain the lock is canceled. N.B. On Oracle hitting this lockWaitTimeout may cause the error: (SQLRecoverableException) SQLState [08003], errorCode [17008] connection closed
	 * @ff.default 0
	 */
	public void setLockWaitTimeout(int i) {
		lockWaitTimeout = i;
	}

	/** If set <code>true</code> and the IBISLOCK table does not exist in the database, the process continues as if the lock was obtained */
	public void setIgnoreTableNotExist(boolean b) {
		ignoreTableNotExist = b;
	}

}