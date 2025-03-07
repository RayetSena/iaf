/*
   Copyright 2015-2018, 2020 Nationale-Nederlanden, 2022 WeAreFrank!

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
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;

import nl.nn.adapterframework.configuration.ConfigurationException;
import nl.nn.adapterframework.core.PipeLineSession;
import nl.nn.adapterframework.core.PipeRunException;
import nl.nn.adapterframework.core.PipeRunResult;
import nl.nn.adapterframework.http.RestListener;
import nl.nn.adapterframework.parameters.Parameter;
import nl.nn.adapterframework.parameters.ParameterList;
import nl.nn.adapterframework.stream.Message;
import nl.nn.adapterframework.util.AppConstants;
import nl.nn.adapterframework.util.DateUtils;
import nl.nn.adapterframework.util.DomBuilderException;
import nl.nn.adapterframework.util.Misc;
import nl.nn.adapterframework.util.ProcessMetrics;
import nl.nn.adapterframework.util.XmlBuilder;
import nl.nn.adapterframework.util.XmlUtils;

/**
 * Create a view for {@link RestListener}.
 *
 * <p>
 * <b>expected format after performing the XSLT transformation:</b>
 * <code>
 * <pre>
 *   &lt;page title="..."&gt;
 *      ...
 *   &lt;/page&gt;
 * </pre>
 * </code>
 * </p>
 * <p>
 * <b>example:</b>
 * <code>
 * <pre>
 *   &lt;page title="Generate WSDL"&gt;
 *      &lt;script type="text/javascript"&gt;
 *         //&amp;lt;![CDATA[
 *         function changeBg(obj,isOver) {
 *            var color1="#8D0022";
 *            var color2="#b4e2ff";
 *            if (isOver) {
 *               obj.style.backgroundColor=color1;
 *               obj.style.color=color2;
 *            } else {
 *               obj.style.backgroundColor=color2;
 *               obj.style.color=color1;
 *            }
 *         }
 *         //]]&amp;gt;
 *      &lt;/script&gt;
 *      &lt;form method="post" action="" enctype="multipart/form-data"&gt;
 *         &lt;table border="0" width="100%"&gt;
 *            &lt;tr&gt;
 *               &lt;td&gt;Upload xsd/zip file&lt;/td&gt;
 *               &lt;td&gt;
 *                  &lt;input type="file" name="file" value=""/&gt;
 *               &lt;/td&gt;
 *            &lt;/tr&gt;
 *             &lt;tr&gt;
 *                &lt;td/&gt;
 *                &lt;td&gt;
 *                   &lt;input type="submit" onmouseover="changeBg(this,true);" onmouseout="changeBg(this,false);" value="send"/&gt;
 *                &lt;/td&gt;
 *             &lt;/tr&gt;
 *         &lt;/table&gt;
 *      &lt;/form&gt;
 *   &lt;/page&gt;
 * </pre>
 * </code>
 * </p>
 * <p>
 * <b>example:</b>
 * <code>
 * <pre>
 *   &lt;page title="Show Generated WSDL"&gt;
 *      &lt;table&gt;
 *         &lt;caption class="caption"&gt;Files&lt;/caption&gt;
 *         &lt;tr&gt;
 *            &lt;th class="colHeader"&gt;Name&lt;/th&gt;
 *            &lt;th class="colHeader"&gt;Size&lt;/th&gt;
 *            &lt;th class="colHeader"&gt;Date&lt;/th&gt;
 *            &lt;th class="colHeader"&gt;Time&lt;/th&gt;
 *            &lt;th class="colHeader"&gt;as&lt;/th&gt;
 *         &lt;/tr&gt;
 *         &lt;tr class="filterRow"&gt;
 *            &lt;td class="filterRow"&gt;GetCollectionDisbursementAccountInformationOnPolicy_2_concrete.wsdl&lt;/td&gt;
 *            &lt;td class="filterRow"&gt;25269&lt;/td&gt;
 *            &lt;td class="filterRow"&gt;28-05-15&lt;/td&gt;
 *            &lt;td class="filterRow"&gt;11:33:30&lt;/td&gt;
 *            &lt;td class="filterRow"&gt;
 *               &lt;a href="../FileViewerServlet?resultType=xml&amp;amp;fileName=C:\Temp\GetCollectionDisbursementAccountInformationOnPolicy_2_concrete.wsdl"&gt;xml&lt;/a&gt;
 *               &lt;a href="../FileViewerServlet?resultType=text&amp;amp;fileName=C:\Temp\GetCollectionDisbursementAccountInformationOnPolicy_2_concrete.wsdl"&gt;text&lt;/a&gt;
 *            &lt;/td&gt;
 *         &lt;/tr&gt;
 *         &lt;tr class="rowEven"&gt;
 *            &lt;td class="filterRow"&gt;GetCollectionDisbursementAccountInformationOnPolicy_2_concrete.zip&lt;/td&gt;
 *            &lt;td class="filterRow"&gt;5759&lt;/td&gt;
 *            &lt;td class="filterRow"&gt;28-05-15&lt;/td&gt;
 *            &lt;td class="filterRow"&gt;11:33:30&lt;/td&gt;
 *            &lt;td class="filterRow"&gt;
 *               &lt;a href="../FileViewerServlet?resultType=zip&amp;amp;fileName=C:\Temp\GetCollectionDisbursementAccountInformationOnPolicy_2_concrete.zip"&gt;zip&lt;/a&gt;
 *            &lt;/td&gt;
 *         &lt;/tr&gt;
 *      &lt;/table&gt;
 *   &lt;/page&gt;
 * </pre>
 * </code>
 * </p>
 * <p>
 *
 * @author Peter Leeuwenburgh
 */

public class CreateRestViewPipe extends XsltPipe {
	private static final String CONTENTTYPE = "contentType";
	private static final String SRCPREFIX = "srcPrefix";

	private String contentType = "text/html";
	private AppConstants appConstants;

	@Override
	public void configure() throws ConfigurationException {
		ParameterList parameterList = getParameterList();
		if (parameterList==null || parameterList.findParameter(SRCPREFIX) == null) {
			Parameter p = new Parameter();
			p.setName(SRCPREFIX);
			p.setSessionKey(SRCPREFIX);
			addParameter(p);
		}
		appConstants = AppConstants.getInstance(getConfigurationClassLoader());
		super.configure();
	}

	@Override
	public PipeRunResult doPipe(Message input, PipeLineSession session) throws PipeRunException {
		HttpServletRequest httpServletRequest = (HttpServletRequest) session.get(PipeLineSession.HTTP_REQUEST_KEY);
		String requestURL = httpServletRequest.getRequestURL().toString();
		String servletPath = httpServletRequest.getServletPath();
		String uri = StringUtils.substringAfter(requestURL, servletPath);
		int countSrcPrefix = StringUtils.countMatches(uri, "/");
		String srcPrefix = StringUtils.repeat("../", countSrcPrefix);
		session.put(SRCPREFIX, srcPrefix);
		log.debug("stored [{}] in pipeLineSession under key [{}]", srcPrefix, SRCPREFIX);

		PipeRunResult prr = super.doPipe(input, session);
		Message result = prr.getResult();

		log.debug("transforming page [" + result + "] to view");

		String newResult = null;
		ServletContext servletContext = (ServletContext) session.get(PipeLineSession.SERVLET_CONTEXT_KEY);

		try {
			Map<String,Object> parameters = retrieveParameters(httpServletRequest, servletContext, srcPrefix);
			newResult = XmlUtils.getAdapterSite(result.asString(), parameters);
		} catch (Exception e) {
			throw new PipeRunException(this, "Exception on transforming page to view", e);
		}

		session.put(CONTENTTYPE, getContentType());
		log.debug("stored [{}] in pipeLineSession under key [{}]", getContentType(), CONTENTTYPE);

		return new PipeRunResult(getSuccessForward(), newResult);
	}

	private Map<String,Object> retrieveParameters(HttpServletRequest httpServletRequest, ServletContext servletContext, String srcPrefix) throws DomBuilderException {
		Map<String,Object> parameters = new Hashtable<>();
		String requestInfoXml = "<requestInfo>" + "<servletRequest>"
				+ "<serverInfo><![CDATA[" + servletContext.getServerInfo()
				+ "]]></serverInfo>" + "<serverName>"
				+ httpServletRequest.getServerName() + "</serverName>"
				+ "</servletRequest>" + "</requestInfo>";
		parameters.put("requestInfo", XmlUtils.buildNode(requestInfoXml));
		parameters.put("upTime", XmlUtils.buildNode("<upTime>" + getUptime() + "</upTime>"));
		String machineNameXml = "<machineName>" + Misc.getHostname() + "</machineName>";
		parameters.put("machineName", XmlUtils.buildNode(machineNameXml));
		String fileSystemXml = "<fileSystem>" + "<totalSpace>"
				+ Misc.getFileSystemTotalSpace() + "</totalSpace>"
				+ "<freeSpace>" + Misc.getFileSystemFreeSpace()
				+ "</freeSpace>" + "</fileSystem>";
		parameters.put("fileSystem", XmlUtils.buildNode(fileSystemXml));
		String applicationConstantsXml = appConstants.toXml(true);
		parameters.put("applicationConstants", XmlUtils.buildNode(applicationConstantsXml));
		String processMetricsXml = ProcessMetrics.toXml();
		parameters.put("processMetrics", XmlUtils.buildNode(processMetricsXml));
		parameters.put("menuBar", XmlUtils.buildNode(retrieveMenuBarParameter(srcPrefix)));
		parameters.put(SRCPREFIX, srcPrefix);

		return parameters;
	}

	private String getUptime() {
		return DateUtils.format(getApplicationContext().getStartupDate(), DateUtils.FORMAT_GENERICDATETIME);
	}

	private String retrieveMenuBarParameter(String srcPrefix) {
		XmlBuilder menuBar = new XmlBuilder("menuBar");
		XmlBuilder imagelinkMenu = new XmlBuilder("imagelinkMenu");
		imagelinkMenu.addSubElement(createImagelinkElement(srcPrefix, "larva", "larva", "Larva Test Tool"));
		imagelinkMenu.addSubElement(createImagelinkElement(srcPrefix, "testtool", "ladybug", "Ladybug Test Tool"));
		imagelinkMenu.addSubElement(createImagelinkElement(srcPrefix, "javascript:void(0)", "info", "Information"));
		if (appConstants.getBoolean("console.active", false)) {
			imagelinkMenu.addSubElement(createImagelinkElement(srcPrefix, "iaf/gui", "theme", "Try our new GUI 3.0 and leave feedback!"));
		}
		menuBar.addSubElement(imagelinkMenu);
		return menuBar.toXML();
	}

	private XmlBuilder createImagelinkElement(String srcPrefix, String href, String type, String alt) {
		XmlBuilder imagelink = new XmlBuilder("imagelink");
		if (StringUtils.startsWithIgnoreCase(href, "javascript:") || StringUtils.startsWithIgnoreCase(href, "?")) {
			imagelink.addAttribute("href", href);
		} else {
			imagelink.addAttribute("href", srcPrefix + href);
		}
		imagelink.addAttribute("type", type);
		imagelink.addAttribute("alt", alt);
		return imagelink;
	}

	/**
	 * content type of the servlet response
	 * @ff.default text/html
	 */
	public void setContentType(String string) {
		contentType = string;
	}

	public String getContentType() {
		return contentType;
	}
}