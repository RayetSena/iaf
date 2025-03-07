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
package nl.nn.adapterframework.management.bus;

import javax.annotation.security.RolesAllowed;

import org.springframework.messaging.Message;
import org.springframework.messaging.support.GenericMessage;

import nl.nn.adapterframework.configuration.ConfigurationException;
import nl.nn.adapterframework.stream.StreamingException;

@BusAware("frank-management-bus")
public class BusTestEndpoints {
	public enum ExceptionTestTypes {
		MESSAGE, MESSAGE_WITH_CAUSE, CAUSE
	}

	//Test authorization
	@TopicSelector(BusTopic.DEBUG)
	@ActionSelector(BusAction.MANAGE)
	@RolesAllowed({"IbisAdmin", "IbisTester"})
	public Message<String> handleIbisAction(Message<?> message) {
		String isAdmin = ""+BusMessageUtils.hasAnyRole("IbisTester");
		return new GenericMessage<String>(isAdmin);
	}

	//Test exceptions
	@TopicSelector(BusTopic.DEBUG)
	@ActionSelector(BusAction.WARNINGS)
	public Message<String> throwException(Message<?> message) {
		ExceptionTestTypes type = BusMessageUtils.getEnumHeader(message, "type", ExceptionTestTypes.class);
		Exception cause = new StreamingException("cannot stream",
			new ConfigurationException("cannot configure",
				new IllegalStateException("something is wrong")));
		switch (type) {
		case MESSAGE:
			throw new BusException("message with a cause");
		case CAUSE:
			throw new IllegalStateException("uncaught exception", cause);
		case MESSAGE_WITH_CAUSE:
		default:
			throw new BusException("message with a cause", cause);
		}
	}
}
