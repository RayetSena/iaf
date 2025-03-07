/*
   Copyright 2022 WeAreFrank!

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
package nl.nn.adapterframework.management.bus.endpoints;

import javax.annotation.Nonnull;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.http.MediaType;
import org.springframework.util.MimeType;

import nl.nn.adapterframework.configuration.Configuration;
import nl.nn.adapterframework.configuration.IbisManager;
import nl.nn.adapterframework.core.Adapter;
import nl.nn.adapterframework.core.IPipe;
import nl.nn.adapterframework.management.bus.BusException;
import nl.nn.adapterframework.receivers.Receiver;
import nl.nn.adapterframework.util.FilenameUtils;
import nl.nn.adapterframework.util.LogUtil;
import nl.nn.adapterframework.util.SpringUtils;

public class BusEndpointBase implements ApplicationContextAware, InitializingBean {
	protected Logger log = LogUtil.getLogger(this);
	private Logger secLog = LogUtil.getLogger("SEC");
	private ApplicationContext applicationContext;
	private IbisManager ibisManager;

	@Override
	public final void setApplicationContext(ApplicationContext ac) {
		this.applicationContext = ac;
	}

	protected final ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	protected final IbisManager getIbisManager() {
		return ibisManager;
	}

	protected final <T> T createBean(Class<T> beanClass) {
		return SpringUtils.createBean(applicationContext, beanClass);
	}

	protected final <T> T getBean(String beanName, Class<T> beanClass) {
		return applicationContext.getBean(beanName, beanClass);
	}

	@Override
	public final void afterPropertiesSet() {
		if(applicationContext == null) {
			throw new BusException("ApplicationContext not set");
		}

		ibisManager = applicationContext.getBean("ibisManager", IbisManager.class);
		doAfterPropertiesSet();
	}

	protected void doAfterPropertiesSet() {
		//Override to initialize bean
	}

	protected final void log2SecurityLog(String message, String issuedBy) {
		String logMessage = (StringUtils.isEmpty(issuedBy)) ? message : message+" issued by "+issuedBy;
		log.info(logMessage);
		secLog.info(logMessage);
	}

	@Nonnull
	protected MimeType getMediaTypeFromName(String name) {
		String ext = FilenameUtils.getExtension(name);
		switch (ext) {
		case "xml":
			return MediaType.APPLICATION_XML;
		case "json":
			return MediaType.APPLICATION_JSON;
		default:
			return MediaType.TEXT_PLAIN;
		}
	}

	@Nonnull
	protected Configuration getConfigurationByName(String configurationName) {
		if(StringUtils.isEmpty(configurationName)) {
			throw new BusException("no configuration name specified");
		}
		Configuration configuration = getIbisManager().getConfiguration(configurationName);
		if(configuration == null) {
			throw new BusException("configuration ["+configurationName+"] does not exists");
		}
		return configuration;
	}

	@Nonnull
	protected Adapter getAdapterByName(String configurationName, String adapterName) {
		if(IbisManager.ALL_CONFIGS_KEY.equals(configurationName)) {
			return getIbisManager().getRegisteredAdapter(adapterName);
		}

		Configuration config = getConfigurationByName(configurationName);
		Adapter adapter = config.getRegisteredAdapter(adapterName);

		if(adapter == null){
			throw new BusException("adapter ["+adapterName+"] does not exist");
		}

		return adapter;
	}

	@Nonnull
	protected Receiver<?> getReceiverByName(Adapter adapter, String receiverName) {
		Receiver<?> receiver = adapter.getReceiverByName(receiverName);
		if(receiver == null) {
			throw new BusException("receiver ["+receiverName+"] does not exist");
		}
		return receiver;
	}

	@Nonnull
	protected IPipe getPipeByName(Adapter adapter, String pipeName) {
		IPipe pipe = adapter.getPipeLine().getPipe(pipeName);
		if(pipe == null) {
			throw new BusException("pipe ["+pipeName+"] does not exist");
		}
		return pipe;
	}
}
