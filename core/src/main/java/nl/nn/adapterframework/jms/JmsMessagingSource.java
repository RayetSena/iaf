/*
   Copyright 2013 Nationale-Nederlanden

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
package nl.nn.adapterframework.jms;

import java.util.Map;

import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.Session;
import javax.jms.XAConnectionFactory;
import javax.naming.Context;
import javax.naming.NamingException;

import org.apache.commons.lang3.StringUtils;
import org.springframework.jms.connection.TransactionAwareConnectionFactoryProxy;

import bitronix.tm.resource.jms.PoolingConnectionFactory;
import nl.nn.adapterframework.util.ClassUtils;

/**
 * {@link MessagingSource} for JMS connections.
 * 
 * @author 	Gerrit van Brakel
 * @since   4.4
 */
public class JmsMessagingSource extends MessagingSource {
	private String jndiContextPrefix;
	private Map<String, String> proxiedDestinationNames;

	public JmsMessagingSource(String connectionFactoryName, String jndiContextPrefix, Context context,
			ConnectionFactory connectionFactory, Map<String,MessagingSource> messagingSourceMap,
			String authAlias, boolean createDestination, Map<String, String> proxiedDestinationNames, boolean useJms102) {
		super(connectionFactoryName, context, connectionFactory, messagingSourceMap, authAlias, createDestination, useJms102);
		this.jndiContextPrefix = jndiContextPrefix;
		this.proxiedDestinationNames = proxiedDestinationNames;
	}

	public Destination lookupDestination(String destinationName) throws JmsException, NamingException {
		Destination dest=null;
		if (createDestination()) {
			try {
				dest = lookupDestinationInJndi(destinationName);
			} catch (Exception e) {
				log.warn("could not lookup destination in jndi, will try to create it ("+e.getClass()+"): "+ e.getMessage());
			}
			if (dest==null) {
				log.debug(getLogPrefix() + "looking up destination by creating it [" + destinationName + "]");
				if (proxiedDestinationNames != null) {
					String proxiedDestinationName = proxiedDestinationNames.get(destinationName);
					if (proxiedDestinationName != null) {
						log.debug(getLogPrefix() + "replacing destination name with proxied destination name [" + proxiedDestinationName + "]");
						destinationName = proxiedDestinationName;
					}
				}
				dest = createDestination(destinationName);
			}
		} else {
			dest = lookupDestinationInJndi(destinationName);
		}
		return dest;
	}

	private Destination lookupDestinationInJndi(String destinationName) throws NamingException {
		String prefixedDestinationName = getJndiContextPrefix() + destinationName;
		log.debug(getLogPrefix() + "looking up destination [" + prefixedDestinationName + "]");
		if (StringUtils.isNotEmpty(getJndiContextPrefix())) {
			log.debug(getLogPrefix() + "using JNDI context prefix [" + getJndiContextPrefix() + "]");
		}
		return (Destination)getContext().lookup(prefixedDestinationName);
	}

	public Destination createDestination(String destinationName) throws JmsException {
		Destination dest = null;
		Session session = null;
		try {
			session = createSession(false, Session.AUTO_ACKNOWLEDGE);
			dest = session.createQueue(destinationName);
		} catch (Exception e) {
			throw new JmsException("cannot create destination ["+destinationName+"]", e);
		} finally {
			releaseSession(session);
		}
		return dest;
	}

	@Override
	protected ConnectionFactory getConnectionFactoryDelegate() throws IllegalArgumentException, SecurityException, IllegalAccessException, NoSuchFieldException {
		ConnectionFactory result = getConnectionFactory();
		if (result instanceof TransactionAwareConnectionFactoryProxy) {
			result = (ConnectionFactory)ClassUtils.getDeclaredFieldValue(result,"targetConnectionFactory");
		}
		if (result instanceof PoolingConnectionFactory) {
			XAConnectionFactory xaConnectionFactory =  ((PoolingConnectionFactory)result).getXaConnectionFactory();
			if (xaConnectionFactory instanceof ConnectionFactory) {
				result = (ConnectionFactory)xaConnectionFactory;
			}
		}
		try {
			return (ConnectionFactory)ClassUtils.getDeclaredFieldValue(result,"wrapped");
		} catch (NoSuchFieldException e) {
			return result;
		}
	}

	private String getJndiContextPrefix() {
		return jndiContextPrefix;
	}

}
