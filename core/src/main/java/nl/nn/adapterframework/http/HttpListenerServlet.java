/*
   Copyright 2013 Nationale-Nederlanden, 2022 WeAreFrank!

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
package nl.nn.adapterframework.http;

import java.io.IOException;
import java.util.Enumeration;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.Logger;

import nl.nn.adapterframework.core.ISecurityHandler;
import nl.nn.adapterframework.core.ListenerException;
import nl.nn.adapterframework.core.PipeLineSession;
import nl.nn.adapterframework.lifecycle.IbisInitializer;
import nl.nn.adapterframework.receivers.ServiceDispatcher;
import nl.nn.adapterframework.util.LogUtil;
import nl.nn.adapterframework.util.StreamUtil;

/**
 * Servlet that listens for HTTP GET or POSTS, and handles them over to the ServiceDispatcher
 *
 * @author  Gerrit van Brakel
 * @since   4.4.x (still experimental)
 */
@IbisInitializer
public class HttpListenerServlet extends HttpServletBase {
	protected Logger log=LogUtil.getLogger(this);

	public final String SERVICE_ID_PARAM = "service";
	public final String MESSAGE_PARAM = "message";

	private ServiceDispatcher sd=null;

	@Override
	public void init() throws ServletException {
		super.init();
		if (sd==null) {
			sd= ServiceDispatcher.getInstance();
		}
	}

	public void invoke(String message, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
		ISecurityHandler securityHandler = new HttpSecurityHandler(request);
		try (PipeLineSession messageContext= new PipeLineSession()) {
			messageContext.setSecurityHandler(securityHandler);
			messageContext.put("httpListenerServletRequest", request);
			messageContext.put("httpListenerServletResponse", response);
			String service=request.getParameter(SERVICE_ID_PARAM);
			Enumeration<String> paramnames=request.getParameterNames();
			while (paramnames.hasMoreElements()) {
				String paramname = paramnames.nextElement();
				String paramvalue = request.getParameter(paramname);
				if (log.isDebugEnabled()) {
					log.debug("HttpListenerServlet setting parameter ["+paramname+"] to ["+paramvalue+"]");
				}
				messageContext.put(paramname, paramvalue);
			}
			try {
				log.debug("HttpListenerServlet calling service ["+service+"]");
				String result=sd.dispatchRequest(service, message, messageContext);
				response.getWriter().print(result);
			} catch (ListenerException e) {
				log.warn("HttpListenerServlet caught exception, will rethrow as ServletException",e);
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,e.getMessage());
			}
		}
	}

	@Override
	public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
		String message=request.getParameter(MESSAGE_PARAM);
		invoke(message,request,response);
	}

	@Override
	public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
		String message= StreamUtil.streamToString(request.getInputStream(),"\n",false);
		invoke(message,request,response);
	}

	@Override
	public String[] getAccessGrantingRoles() {
		return IBIS_FULL_SERVICE_ACCESS_ROLES;
	}

	@Override
	public String getUrlMapping() {
		return "/HttpListener";
	}
}
