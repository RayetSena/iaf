/*
   Copyright 2013, 2016 Nationale-Nederlanden, 2020-2022 WeAreFrank!

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
package nl.nn.adapterframework.pipes;

import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import lombok.Getter;
import lombok.Setter;
import nl.nn.adapterframework.configuration.Configuration;
import nl.nn.adapterframework.configuration.ConfigurationException;
import nl.nn.adapterframework.configuration.ConfigurationWarnings;
import nl.nn.adapterframework.core.Adapter;
import nl.nn.adapterframework.core.DummyNamedObject;
import nl.nn.adapterframework.core.IExtendedPipe;
import nl.nn.adapterframework.core.IPipe;
import nl.nn.adapterframework.core.ParameterException;
import nl.nn.adapterframework.core.PipeForward;
import nl.nn.adapterframework.core.PipeLine;
import nl.nn.adapterframework.core.PipeLineExit;
import nl.nn.adapterframework.core.PipeLineSession;
import nl.nn.adapterframework.core.PipeRunException;
import nl.nn.adapterframework.core.PipeRunResult;
import nl.nn.adapterframework.core.PipeStartException;
import nl.nn.adapterframework.core.TransactionAttributes;
import nl.nn.adapterframework.doc.Mandatory;
import nl.nn.adapterframework.monitoring.EventPublisher;
import nl.nn.adapterframework.monitoring.EventThrowing;
import nl.nn.adapterframework.parameters.Parameter;
import nl.nn.adapterframework.parameters.ParameterList;
import nl.nn.adapterframework.parameters.ParameterValueList;
import nl.nn.adapterframework.stream.Message;
import nl.nn.adapterframework.util.AppConstants;
import nl.nn.adapterframework.util.Locker;
import nl.nn.adapterframework.util.SpringUtils;

/**
 * Base class for {@link IPipe Pipe}.
 * A Pipe represents an action to take in a {@link PipeLine Pipeline}. This class is meant to be extended
 * for defining steps or actions to take to complete a request. <br/>
 * The contract is that a pipe is created (by the digester), {@link #setName(String)} is called and
 * other setters are called, and then {@link IPipe#configure()} is called, optionally
 * throwing a {@link ConfigurationException}. <br/>
 * As much as possible, class instantiating should take place in the
 * {@link IPipe#configure()} method.
 * The object remains alive while the framework is running. When the pipe is to be run,
 * the {@link IPipe#doPipe(Message, PipeLineSession) doPipe} method is activated.
 * <p>
 * For the duration of the processing of a message by the {@link PipeLine pipeline} has a {@link PipeLineSession pipeLineSession}.
 * <br/>
 * By this mechanism, pipes may communicate with one another.<br/>
 * However, use this functionality with caution, as it is not desirable to make pipes dependent
 * on each other. If a pipe expects something in a session, it is recommended that
 * the key under which the information is stored is configurable (has a setter for this keyname).
 * Also, the setting of something in the <code>PipeLineSession</code> should be done using
 * this technique (specifying the key under which to store the value by a parameter).
 * </p>
 * <p>Since 4.1 this class also has parameters, so that descendants of this class automatically are parameter-enabled.
 * However, your documentation should say if and how parameters are used!<p>
 * <p> All pipes support a forward named 'exception' which will be followed in the pipeline in case the PipeRunExceptions are not handled by the pipe itself
 *
 * @ff.forward success successful processing of the message of the pipe
 * @ff.forward exception some error happened while processing the message; represents the 'unhappy or error flow' and is not limited to Java Exceptions.
 *
 * @author     Johan Verrips / Gerrit van Brakel
 *
 * @see PipeLineSession
 */
public abstract class AbstractPipe extends TransactionAttributes implements IExtendedPipe, EventThrowing, ApplicationContextAware {
	private @Getter ClassLoader configurationClassLoader = Thread.currentThread().getContextClassLoader();
	private @Getter ApplicationContext applicationContext;

	private @Getter String name;
	private @Getter String getInputFromSessionKey=null;
	private @Getter String getInputFromFixedValue=null;
	private @Getter String storeResultInSessionKey=null;
	private @Getter boolean preserveInput=false;

	private @Getter int maxThreads = 0;
	private @Getter long durationThreshold = -1;

	private @Getter String chompCharSize = null;
	private @Getter String elementToMove = null;
	private @Getter String elementToMoveSessionKey = null;
	private @Getter String elementToMoveChain = null;
	private @Getter boolean removeCompactMsgNamespaces = true;
	private @Getter boolean restoreMovedElements=false;

	private boolean sizeStatistics = AppConstants.getInstance(configurationClassLoader).getBoolean("statistics.size", false);
	private @Getter Locker locker;
	private @Getter String emptyInputReplacement=null;
	private @Getter boolean writeToSecLog = false;
	private @Getter String secLogSessionKeys = null;
	private @Getter String logIntermediaryResults = null;
	private @Getter String hideRegex = null;

	private Map<String, PipeForward> pipeForwards = new Hashtable<String, PipeForward>();
	private ParameterList parameterList = new ParameterList();
	protected boolean parameterNamesMustBeUnique;
	private @Setter EventPublisher eventPublisher=null;

	private @Getter @Setter PipeLine pipeLine;

	private DummyNamedObject inSizeStatDummyObject=null;
	private DummyNamedObject outSizeStatDummyObject=null;

	public AbstractPipe() {
		inSizeStatDummyObject = new DummyNamedObject();
		outSizeStatDummyObject = new DummyNamedObject();
	}

	/**
	 * <code>configure()</code> is called after the {@link PipeLine Pipeline} is registered
	 * at the {@link Adapter Adapter}. Purpose of this method is to reduce
	 * creating connections to databases etc. in the {@link #doPipe(Message, PipeLineSession) doPipe()} method.
	 * As much as possible class-instantiating should take place in the
	 * <code>configure()</code> method, to improve performance.
	 */
	//For testing purposes the configure method should not require the PipeLine to be present.
	@Override
	public void configure() throws ConfigurationException {
		super.configure();
		if(StringUtils.isNotEmpty(getName()) && getName().contains("/")) {
			throw new ConfigurationException("It is not allowed to have '/' in pipe name ["+getName()+"]");
		}
		ParameterList params = getParameterList();
		if (params!=null) {
			try {
				params.setNamesMustBeUnique(parameterNamesMustBeUnique);
				params.configure();
			} catch (ConfigurationException e) {
				throw new ConfigurationException("while configuring parameters",e);
			}
		}

		if (!StringUtils.isEmpty(getElementToMove()) && !StringUtils.isEmpty(getElementToMoveChain())) {
			throw new ConfigurationException("cannot have both an elementToMove and an elementToMoveChain specified");
		}

		if (getLocker() != null) {
			getLocker().configure();
		}
	}

	/**
	 * final method to ensure nobody overrides this...
	 */
	@Override
	public final void setApplicationContext(ApplicationContext applicationContext) {
		if(!(applicationContext instanceof Configuration)) {
			throw new IllegalArgumentException("ApplicationContext is not instance of Configuration");
		}
		this.applicationContext = applicationContext;
	}

	protected <T> T createBean(Class<T> beanClass) {
		return SpringUtils.createBean(applicationContext, beanClass);
	}


	/**
	 * This is where the action takes place. Pipes may only throw a PipeRunException,
	 * to be handled by the caller of this object.
	 */
	@Override
	public abstract PipeRunResult doPipe (Message message, PipeLineSession session) throws PipeRunException;

	@Override
	public void start() throws PipeStartException {}

	@Override
	public void stop() {}


	/**
	 * Add a parameter to the list of parameters
	 */
	public void addParameter(Parameter param) {
		log.debug("Pipe ["+getName()+"] added parameter ["+param.toString()+"]");
		parameterList.add(param);
	}

	/**
	 * return the Parameters
	 */
	public ParameterList getParameterList() {
		return parameterList;
	}

	@Override
	public void setLocker(Locker locker) {
		this.locker = locker;
	}

	@Override
	/** Forwards are used to determine the next Pipe to execute in the Pipeline */
	public void registerForward(PipeForward forward) throws ConfigurationException {
		String forwardName = forward.getName();
		if(forwardName != null) {
			PipeForward current = pipeForwards.get(forwardName);
			if (current==null){
				pipeForwards.put(forwardName, forward);
			} else {
				if (forward.getPath()!=null && forward.getPath().equals(current.getPath())) {
					ConfigurationWarnings.add(this, log, "forward ["+forwardName+"] is already registered");
				} else {
					log.info("PipeForward ["+forwardName+"] already registered, pointing to ["+current.getPath()+"]. Ignoring new one, that points to ["+forward.getPath()+"]");
				}
			}
		} else {
			throw new ConfigurationException("forward without a name");
		}
	}

	/**
	 * Looks up a key in the pipeForward hashtable. <br/>
	 * A typical use would be on return from a Pipe: <br/>
	 * <code><pre>
	 * return new PipeRunResult(findForward("success"), result);
	 * </pre></code>
	 * findForward searches:<ul>
	 * <li>All forwards defined in xml under the pipe element of this pipe</li>
	 * <li>All global forwards defined in xml under the PipeLine element</li>
	 * <li>All pipe names with their (identical) path</li>
	 * </ul>
	 */
	public PipeForward findForward(String forward){
		if (StringUtils.isNotEmpty(forward)) {
			if (pipeForwards.containsKey(forward)) {
				return pipeForwards.get(forward);
			}
			if (pipeLine!=null) {
				PipeForward result = pipeLine.getGlobalForwards().get(forward);
				if (result == null) {
					IPipe pipe = pipeLine.getPipe(forward);
					if (pipe!=null) {
						result = new PipeForward(forward, forward);
					}
				}
				if (result == null) {
					PipeLineExit exit = pipeLine.getPipeLineExits().get(forward);
					if (exit!=null) {
						result = new PipeForward(forward, forward);
					}
				}
				if (result!=null) {
					pipeForwards.put(forward, result);
				}
				return result;
			}
		}
		return null;
	}

	@Override
	public Map<String, PipeForward> getForwards(){
		Map<String, PipeForward> forwards = new Hashtable<String, PipeForward>(pipeForwards);
		PipeLine pipeline = getPipeLine();
		if (pipeline==null) {
			return null;
		}

		//Omit global pipeline-forwards and only return local pipe-forwards
		List<IPipe> pipes = pipeline.getPipes();
		for (int i=0; i<pipes.size(); i++) {
			String pipeName = pipes.get(i).getName();
			if(forwards.containsKey(pipeName))
				forwards.remove(pipeName);
		}
		return forwards;
	}

	@Override
	public String getEventSourceName() {
		return getName().trim();
	}

	@Override
	public void registerEvent(String description) {
		if (eventPublisher != null) {
			eventPublisher.registerEvent(this, description);
		}
	}
	@Override
	public void throwEvent(String event) {
		if (eventPublisher != null) {
			eventPublisher.fireEvent(this ,event);
		}
	}

	@Override
	public Adapter getAdapter() {
		if (getPipeLine()!=null) {
			return getPipeLine().getAdapter();
		}
		return null;
	}

	@Override
	public boolean consumesSessionVariable(String sessionKey) {
		return sessionKey.equals(getInputFromSessionKey) || parameterList!=null && parameterList.consumesSessionVariable(sessionKey);
	}

	protected ParameterValueList getParameterValueList(Message input, PipeLineSession session) throws PipeRunException {
		try {
			return getParameterList()!=null ? getParameterList().getValues(input, session) : null;
		} catch (ParameterException e) {
			throw new PipeRunException(this,"cannot determine parameter values", e);
		}
	}

	protected <T> T getParameterOverriddenAttributeValue(ParameterValueList pvl, String parameterName, T attributeValue) {
		T result = pvl!=null ? (T)pvl.get(parameterName) : null;
		return result!=null ? result : attributeValue;
	}

	/**
	 * The functional name of this pipe. Can be referenced by the <code>path</code> attribute of a {@link PipeForward}.
	 */
	@Override
	@Mandatory
	public void setName(String name) {
		this.name=name;
		inSizeStatDummyObject.setName(getName() + " (in)");
		outSizeStatDummyObject.setName(getName() + " (out)");
	}

	@Override
	public void setGetInputFromSessionKey(String string) {
		getInputFromSessionKey = string;
	}

	@Override
	public void setGetInputFromFixedValue(String string) {
		getInputFromFixedValue = string;
	}

	@Override
	public void setEmptyInputReplacement(String string) {
		emptyInputReplacement = string;
	}

	@Override
	public void setPreserveInput(boolean preserveInput) {
		this.preserveInput = preserveInput;
	}

	/**
	 * If set, the pipe result is copied to a session key that has the name defined by this attribute. The
	 * pipe result is still written as the output message as usual.
	 */
	@Override
	public void setStoreResultInSessionKey(String string) {
		storeResultInSessionKey = string;
	}

	/**
	 * The maximum number of threads that may {@link #doPipe process messages} simultaneously.
	 * A value of 0 indicates an unlimited number of threads.
	 * @ff.default 0
	 */
	public void setMaxThreads(int newMaxThreads) {
		maxThreads = newMaxThreads;
	}

	@Override
	public void setChompCharSize(String string) {
		chompCharSize = string;
	}

	@Override
	public void setElementToMove(String string) {
		elementToMove = string;
	}

	@Override
	public void setElementToMoveSessionKey(String string) {
		elementToMoveSessionKey = string;
	}

	@Override
	public void setElementToMoveChain(String string) {
		elementToMoveChain = string;
	}

	@Override
	public void setDurationThreshold(long maxDuration) {
		this.durationThreshold = maxDuration;
	}

	@Override
	public void setRemoveCompactMsgNamespaces(boolean b) {
		removeCompactMsgNamespaces = b;
	}

	@Override
	public void setRestoreMovedElements(boolean restoreMovedElements) {
		this.restoreMovedElements = restoreMovedElements;
	}



	public void setSizeStatistics(boolean sizeStatistics) {
		this.sizeStatistics = sizeStatistics;
	}
	@Override
	public boolean hasSizeStatistics() {
		return sizeStatistics;
	}

	public DummyNamedObject getInSizeStatDummyObject() {
		return inSizeStatDummyObject;
	}

	public DummyNamedObject getOutSizeStatDummyObject() {
		return outSizeStatDummyObject;
	}

	/** when set to <code>true</code> a record is written to the security log when the pipe has finished successfully */
	@Override
	public void setWriteToSecLog(boolean b) {
		writeToSecLog = b;
	}

	/** (only used when <code>writeToSecLog=true</code>) comma separated list of keys of session variables that is appended to the security log record */
	@Override
	public void setSecLogSessionKeys(String string) {
		secLogSessionKeys = string;
	}

	/** when set, the value in AppConstants is overwritten (for this pipe only) */
	public void setLogIntermediaryResults(String string) {
		logIntermediaryResults = string;
	}

	/**
	 * Regular expression to mask strings in the log. For example, the regular expression <code>(?&lt;=&lt;password&gt;).*?(?=&lt;/password&gt;)</code>
	 * will replace every character between keys '&lt;password&gt;' and '&lt;/password&gt;'. <b>note:</b> this feature is used at adapter level,
	 * so one pipe affects all pipes in the pipeline (and multiple values in different pipes are merged)
	 */
	public void setHideRegex(String hideRegex) {
		this.hideRegex = hideRegex;
	}
}
