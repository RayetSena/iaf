/*
   Copyright 2019-2022 WeAreFrank!

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
package nl.nn.adapterframework.stream;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import nl.nn.adapterframework.core.PipeLineSession;
import nl.nn.adapterframework.jta.IThreadConnectableTransactionManager;
import nl.nn.adapterframework.jta.TransactionConnector;
import nl.nn.adapterframework.logging.IbisMaskingLayout;
import nl.nn.adapterframework.util.LogUtil;

public class ThreadConnector<T> implements AutoCloseable {
	protected Logger log = LogUtil.getLogger(this);

	private ThreadLifeCycleEventListener<T> threadLifeCycleEventListener;
	private Thread parentThread;
	private Thread childThread;
	private Map<String,String> savedThreadContext;
	private T threadInfo;
	private Set<String> hideRegex;

	private enum ThreadState {
		ANNOUNCED,
		CREATED,
		FINISHED;
	};

	private ThreadState threadState=ThreadState.ANNOUNCED;
	private TransactionConnector<?,?> transactionConnector;


	public ThreadConnector(Object owner, String description, ThreadLifeCycleEventListener<T> threadLifeCycleEventListener, IThreadConnectableTransactionManager txManager, String correlationId) {
		super();
		this.threadLifeCycleEventListener=threadLifeCycleEventListener;
		threadInfo=threadLifeCycleEventListener!=null?threadLifeCycleEventListener.announceChildThread(owner, correlationId):null;
		log.trace("[{}] announced thread [{}] for owner [{}] correlationId [{}]", this, threadInfo, owner, correlationId);
		parentThread=Thread.currentThread();
		hideRegex= IbisMaskingLayout.getThreadLocalReplace();
		transactionConnector = TransactionConnector.getInstance(txManager, owner, description);
		saveThreadContext();
	}
	public ThreadConnector(Object owner, String description, ThreadLifeCycleEventListener<T> threadLifeCycleEventListener, IThreadConnectableTransactionManager txManager, PipeLineSession session) {
		this(owner, description, threadLifeCycleEventListener, txManager, session==null?null:session.getCorrelationId());
	}

	protected void saveThreadContext() {
		savedThreadContext = ThreadContext.getContext();
		log.trace("saved ThreadContext [{}]", savedThreadContext);
	}

	protected void restoreThreadContext() {
		if (savedThreadContext!=null) {
			log.trace("restoring ThreadContext [{}]", savedThreadContext);
			ThreadContext.putAll(savedThreadContext);
			savedThreadContext = null;
		}
	}

	public <R> R startThread(R input) {
		childThread = Thread.currentThread();
		if (childThread!=parentThread) {
			restoreThreadContext();
		}
		if (transactionConnector!=null) {
			transactionConnector.beginChildThread();
		}
		if (childThread!=parentThread) {
			childThread.setName(parentThread.getName()+"/"+childThread.getName());
			IbisMaskingLayout.addToThreadLocalReplace(hideRegex);
			if (threadLifeCycleEventListener!=null) {
				threadState = ThreadState.CREATED;
				log.trace("[{}] start thread [{}]", this, threadInfo);
				return threadLifeCycleEventListener.threadCreated(threadInfo, input);
			}
		} else {
			if (threadLifeCycleEventListener!=null) {
				log.trace("[{}] cancel thread [{}]", this, threadInfo);
				threadLifeCycleEventListener.cancelChildThread(threadInfo);
				threadLifeCycleEventListener=null;
			}
		}
		return input;
	}

	public <R> R endThread(R response) {
		Thread currentThread = Thread.currentThread();
		if (currentThread != childThread) {
			throw new IllegalStateException("endThread() must be called from childThread");
		}
		R result;
		saveThreadContext();
		try {
			try {
				if (transactionConnector!=null) {
					transactionConnector.endChildThread();
				}
			} finally {
				if (threadLifeCycleEventListener!=null) {
					threadState = ThreadState.FINISHED;
					log.trace("[{}] end thread [{}]", this, threadInfo);
					result = threadLifeCycleEventListener.threadEnded(threadInfo, response);
				} else {
					result = response;
				}
			}
		} finally {
			IbisMaskingLayout.removeThreadLocalReplace();
		}
		return result;
	}

	public Throwable abortThread(Throwable t) {
		Thread currentThread = Thread.currentThread();
		if (currentThread != childThread) {
			throw new IllegalStateException("abortThread() must be called from childThread");
		}
		Throwable result = t;
		saveThreadContext();
		try {
			try {
				if (transactionConnector!=null) {
					transactionConnector.endChildThread();
				}
			} finally {
				if (threadLifeCycleEventListener!=null) {
					threadState = ThreadState.FINISHED;
					log.trace("[{}] abort thread [{}]", this, threadInfo);
					result = threadLifeCycleEventListener.threadAborted(threadInfo, t);
					if (result==null) {
						log.warn("Exception ignored by threadLifeCycleEventListener ("+t.getClass().getName()+"): "+t.getMessage());
					}
				}
			}
		} finally {
			IbisMaskingLayout.removeThreadLocalReplace();
		}
		return result;
	}

	@Override
	public void close() throws IOException {
		restoreThreadContext();
		try {
			if (transactionConnector!=null) {
				transactionConnector.close();
			}
		} finally {
			if (threadLifeCycleEventListener!=null) {
				switch (threadState) {
				case ANNOUNCED:
					log.trace("[{}] cancel thread [{}] in close", this, threadInfo);
					threadLifeCycleEventListener.cancelChildThread(threadInfo);
					break;
				case CREATED:
					log.warn("thread was not properly closed");
					log.trace("[{}] end thread [{}] in close", this, threadInfo);
					threadLifeCycleEventListener.threadEnded(threadInfo, null);
					break;
				case FINISHED:
					break;
				default:
					throw new IllegalStateException("Unknown ThreadState ["+threadState+"]");
				}
			}
		}
	}

}
