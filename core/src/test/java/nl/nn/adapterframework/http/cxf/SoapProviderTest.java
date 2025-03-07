/*
   Copyright 2018 Nationale-Nederlanden

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
package nl.nn.adapterframework.http.cxf;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Iterator;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.xml.soap.AttachmentPart;
import javax.xml.soap.MessageFactory;
import javax.xml.soap.MimeHeader;
import javax.xml.soap.SOAPConstants;
import javax.xml.soap.SOAPMessage;
import javax.xml.transform.Source;
import javax.xml.ws.WebServiceContext;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.w3c.dom.Element;

import nl.nn.adapterframework.core.PipeLineSession;
import nl.nn.adapterframework.http.InputStreamDataSource;
import nl.nn.adapterframework.stream.Message;
import nl.nn.adapterframework.stream.MessageContext;
import nl.nn.adapterframework.stream.UrlMessage;
import nl.nn.adapterframework.util.StreamUtil;
import nl.nn.adapterframework.util.XmlUtils;

@RunWith(MockitoJUnitRunner.class)
public class SoapProviderTest {

	@Spy
	WebServiceContext webServiceContext = new WebServiceContextStub();

	@InjectMocks
	private SoapProviderStub SOAPProvider = new SoapProviderStub();

	private static final String ATTACHMENT_CONTENT = "<dummy/>";
	private static final String ATTACHMENT_MIMETYPE = "plain/text";

	private static final String ATTACHMENT2_CONTENT = "<I'm a pdf file/>";
	private static final String PART_NAME = "part_file";
	private static final String ATTACHMENT2_NAME = "document.pdf";
	private static final String ATTACHMENT2_MIMETYPE = "application/pdf";
	private static final String MULTIPART_XML = "<parts><part type=\"file\" name=\""+ATTACHMENT2_NAME+"\" "
			+ "sessionKey=\"part_file\" size=\"72833\" "
			+ "mimeType=\""+ATTACHMENT2_MIMETYPE+"\"/></parts>";

	private static final String BASEDIR = "/Soap/";

	@BeforeClass
	public static void setUp() {
		Properties prop = System.getProperties();
		String vendor = prop.getProperty("java.vendor");
		assumeThat(vendor, not(equalTo("IBM Corporation")));

	/*
	 * The above exclusion of IBM JDK to work around the below error, seen when executing these tests with an IBM JDK:
	 *
		java.lang.VerifyError: JVMVRFY012 stack shape inconsistent; class=com/sun/xml/messaging/saaj/soap/SOAPDocumentImpl, method=createDocumentFragment()Lorg/w3c/dom/DocumentFragment;, pc=5; Type Mismatch, argument 0 in signature com/sun/xml/messaging/saaj/soap/SOAPDocumentFragment.<init>:(Lcom/sun/org/apache/xerces/internal/dom/CoreDocumentImpl;)V does not match
		Exception Details:
		  Location:
		    com/sun/xml/messaging/saaj/soap/SOAPDocumentImpl.createDocumentFragment()Lorg/w3c/dom/DocumentFragment; @5: JBinvokespecial
		  Reason:
		    Type 'com/sun/xml/messaging/saaj/soap/SOAPDocumentImpl' (current frame, stack[2]) is not assignable to 'com/sun/org/apache/xerces/internal/dom/CoreDocumentImpl'
		  Current Frame:
		    bci: @5
		    flags: { }
		    locals: { 'com/sun/xml/messaging/saaj/soap/SOAPDocumentImpl' }
		    stack: { 'uninitialized', 'uninitialized', 'com/sun/xml/messaging/saaj/soap/SOAPDocumentImpl' }
			at com.sun.xml.messaging.saaj.soap.SOAPPartImpl.<init>(SOAPPartImpl.java:106)
			at com.sun.xml.messaging.saaj.soap.ver1_1.SOAPPart1_1Impl.<init>(SOAPPart1_1Impl.java:70)
			at com.sun.xml.messaging.saaj.soap.ver1_1.Message1_1Impl.getSOAPPart(Message1_1Impl.java:90)
			at nl.nn.adapterframework.extensions.cxf.SoapProviderTest.createMessage(SoapProviderTest.java:109)
			at nl.nn.adapterframework.extensions.cxf.SoapProviderTest.createMessage(SoapProviderTest.java:98)
			at nl.nn.adapterframework.extensions.cxf.SoapProviderTest.createMessage(SoapProviderTest.java:94)
			at nl.nn.adapterframework.extensions.cxf.SoapProviderTest.sendMessageWithInputStreamAttachmentsTest(SoapProviderTest.java:228)
	*/

	}

	protected Message getFile(String file) throws IOException {
		URL url = this.getClass().getResource(BASEDIR+file);
		if (url == null) {
			throw new IOException("file not found");
		}
		return new UrlMessage(url);
	}

	private SOAPMessage createMessage(String filename) throws Exception {
		return createMessage(filename, false, false);
	}

	private SOAPMessage createMessage(String filename, boolean addAttachment, boolean isSoap1_1) throws Exception {
		MessageFactory factory = MessageFactory.newInstance(isSoap1_1 ? SOAPConstants.SOAP_1_1_PROTOCOL : SOAPConstants.SOAP_1_2_PROTOCOL);
		SOAPMessage soapMessage = factory.createMessage();
		Source streamSource = getFile(filename).asSource();
		soapMessage.getSOAPPart().setContent(streamSource);

		if(addAttachment) {
			InputStream fis = new ByteArrayInputStream(ATTACHMENT_CONTENT.getBytes());
			DataHandler dataHander = new DataHandler(new InputStreamDataSource(ATTACHMENT_MIMETYPE, fis));

			AttachmentPart part = soapMessage.createAttachmentPart(dataHander);
			soapMessage.addAttachmentPart(part);
		}
		return soapMessage;
	}

	private void assertAttachmentInSession(PipeLineSession session) throws Exception {
		assertNotNull(session.get("mimeHeaders"));

		assertNotNull(session.get("attachments"));
		Element xml = XmlUtils.buildElement((String) session.get("attachments"));
		Element attachment = XmlUtils.getFirstChildTag(xml, "attachment");
		assertNotNull(attachment);

		//Retrieve sessionkey the attachment was stored in
		String sessionKey = XmlUtils.getChildTagAsString(attachment, "sessionKey");
		assertNotNull(sessionKey);
		Message attachmentMessage = session.getMessage(sessionKey);

		//Verify that the attachment sent, was received properly
		assertEquals(ATTACHMENT_CONTENT, attachmentMessage.asString());
		assertEquals(ATTACHMENT_MIMETYPE, attachmentMessage.getContext().get(MessageContext.METADATA_MIMETYPE).toString());

		//Verify the content type
		Element mimeTypes = XmlUtils.getFirstChildTag(attachment, "mimeHeaders");
		mimeTypes.getElementsByTagName("mimeHeader");
		//TODO check what happens when multiple attachments are returned...
		String mimeType = XmlUtils.getChildTagAsString(mimeTypes, "mimeHeader");
		assertEquals(ATTACHMENT_MIMETYPE, mimeType);
	}

	private void assertAttachmentInReceivedMessage(SOAPMessage message) throws Exception {
		assertEquals(1, message.countAttachments());

		Iterator<?> attachmentParts = message.getAttachments();
		while (attachmentParts.hasNext()) {
			AttachmentPart soapAttachmentPart = (AttachmentPart)attachmentParts.next();
			String attachment = StreamUtil.streamToString(soapAttachmentPart.getRawContent());
			//ContentID should be equal to the filename
			assertEquals(PART_NAME, soapAttachmentPart.getContentId());

			//Validate the attachment's content
			assertEquals(ATTACHMENT2_CONTENT, attachment);

			//Make sure at least the content-type header has been set
			Iterator<?> headers = soapAttachmentPart.getAllMimeHeaders();
			String contentType = null;
			while (headers.hasNext()) {
				MimeHeader header = (MimeHeader) headers.next();
				if("Content-Type".equalsIgnoreCase(header.getName()))
					contentType = header.getValue();
			}
			assertEquals(ATTACHMENT2_MIMETYPE, contentType);
		}
	}

	@Test
	/**
	 * Receive SOAP message without attachment
	 * Reply SOAP message without attachment
	 * @throws Throwable
	 */
	public void simpleMessageTest() throws Throwable {
		SOAPMessage request = createMessage("correct-soapmsg.xml");

		SOAPMessage message = SOAPProvider.invoke(request);
		String result = XmlUtils.nodeToString(message.getSOAPPart());
		String expected = getFile("correct-soapmsg.xml").asString();
		assertEquals(expected.replaceAll("\r", ""), result.replaceAll("\r", ""));

		PipeLineSession session = SOAPProvider.getSession();
		assertNotNull(session.get("mimeHeaders"));

		assertNotNull(session.get("attachments"));
		assertEquals("<attachments/>", session.get("attachments").toString().trim());
	}

	@Test
	/**
	 * Receive faulty message without attachment
	 * @throws Throwable
	 */
	public void errorMessageTest() throws Throwable {
		SOAPMessage message = SOAPProvider.invoke(null);
		String result = XmlUtils.nodeToString(message.getSOAPPart());
		assertTrue(result.indexOf("SOAPMessage is null") > 0);
	}

	@Test
	/**
	 * Receive SOAP message with MTOM attachment
	 * Reply SOAP message without attachment
	 * @throws Throwable
	 */
	public void receiveMessageWithAttachmentsTest() throws Throwable {
		SOAPMessage request = createMessage("correct-soapmsg.xml", true, false);

		SOAPMessage message = SOAPProvider.invoke(request);
		String result = XmlUtils.nodeToString(message.getSOAPPart());
		String expected = getFile("correct-soapmsg.xml").asString();
		assertEquals(expected.replaceAll("\r", ""), result.replaceAll("\r", ""));

		PipeLineSession session = SOAPProvider.getSession();
		assertAttachmentInSession(session);
	}

	@Test
	/**
	 * Receive SOAP message without attachment
	 * Reply SOAP message with (InputStream) attachment
	 * @throws Throwable
	 */
	public void sendMessageWithInputStreamAttachmentsTest() throws Throwable {
		SOAPMessage request = createMessage("correct-soapmsg.xml");
		PipeLineSession session = new PipeLineSession();

		session.put("attachmentXmlSessionKey", MULTIPART_XML);
		session.put("part_file", new ByteArrayInputStream(ATTACHMENT2_CONTENT.getBytes()));

		SOAPProvider.setAttachmentXmlSessionKey("attachmentXmlSessionKey");
		SOAPProvider.setSession(session);

		SOAPMessage message = SOAPProvider.invoke(request);

		String result = XmlUtils.nodeToString(message.getSOAPPart());
		String expected = getFile("correct-soapmsg.xml").asString();
		assertEquals(expected.replaceAll("\r", ""), result.replaceAll("\r", ""));

		assertAttachmentInReceivedMessage(message);
	}

	@Test
	/**
	 * Receive SOAP message without attachment
	 * Reply SOAP message with (String) attachment
	 * @throws Throwable
	 */
	public void sendMessageWithStringAttachmentsTest() throws Throwable {
		SOAPMessage request = createMessage("correct-soapmsg.xml");
		PipeLineSession session = new PipeLineSession();

		session.put("attachmentXmlSessionKey", MULTIPART_XML);
		session.put("part_file", ATTACHMENT2_CONTENT);

		SOAPProvider.setAttachmentXmlSessionKey("attachmentXmlSessionKey");
		SOAPProvider.setSession(session);

		SOAPMessage message = SOAPProvider.invoke(request);

		String result = XmlUtils.nodeToString(message.getSOAPPart());
		String expected = getFile("correct-soapmsg.xml").asString();
		assertEquals(expected.replaceAll("\r", ""), result.replaceAll("\r", ""));

		assertAttachmentInReceivedMessage(message);
	}

	@Test
	public void sendMessageWithXmlAttachmentsWithoutContentTypeBinary() throws Exception {
		String attachmentFile = "correct-soapmsg.xml";
		SOAPMessage request = createMessage("correct-soapmsg.xml");
		PipeLineSession session = new PipeLineSession();

		session.put("attachmentXmlSessionKey", "<parts><part sessionKey=\"part_file\"/></parts>");
		session.put("part_file", getFile(attachmentFile));

		SOAPProvider.setAttachmentXmlSessionKey("attachmentXmlSessionKey");
		SOAPProvider.setSession(session);

		SOAPMessage message = SOAPProvider.invoke(request);

		String result = XmlUtils.nodeToString(message.getSOAPPart());
		String expected = getFile("correct-soapmsg.xml").asString();
		assertEquals(expected.replaceAll("\r", ""), result.replaceAll("\r", ""));

		assertEquals(1, message.countAttachments());

		testAttachment(message, getFile(attachmentFile).asString(), "application/xml");
	}

	@Test
	public void sendMessageWithXmlAttachmentsWithoutContentTypeNonBinary() throws Exception {
		String attachmentFile = "correct-soapmsg.xml";
		SOAPMessage request = createMessage("correct-soapmsg.xml");
		PipeLineSession session = new PipeLineSession();

		session.put("attachmentXmlSessionKey", "<parts><part sessionKey=\"part_file\"/></parts>");
		session.put("part_file", Message.asMessage(getFile(attachmentFile).asString())); //as String message / no message context

		SOAPProvider.setAttachmentXmlSessionKey("attachmentXmlSessionKey");
		SOAPProvider.setSession(session);

		SOAPMessage message = SOAPProvider.invoke(request);

		String result = XmlUtils.nodeToString(message.getSOAPPart());
		String expected = getFile("correct-soapmsg.xml").asString();
		assertEquals(expected.replaceAll("\r", ""), result.replaceAll("\r", ""));

		assertEquals(1, message.countAttachments());

		testAttachment(message, getFile(attachmentFile).asString(), "application/octet-stream");
	}

	@Test
	public void sendMessageWithImageAttachmentsWithoutContentType() throws Exception {
		String attachmentFile = "test-image.bmp";
		SOAPMessage request = createMessage("correct-soapmsg.xml");
		PipeLineSession session = new PipeLineSession();

		session.put("attachmentXmlSessionKey", "<parts><part sessionKey=\"part_file\"/></parts>");
		session.put("part_file", getFile(attachmentFile));

		SOAPProvider.setAttachmentXmlSessionKey("attachmentXmlSessionKey");
		SOAPProvider.setSession(session);

		SOAPMessage message = SOAPProvider.invoke(request);

		String result = XmlUtils.nodeToString(message.getSOAPPart());
		String expected = getFile("correct-soapmsg.xml").asString();
		assertEquals(expected.replaceAll("\r", ""), result.replaceAll("\r", ""));

		assertEquals(1, message.countAttachments());

		testAttachment(message, getFile(attachmentFile).asString(), "image/bmp");
	}

	private void testAttachment(SOAPMessage message, String expectedAttachmentContent, String expectedContentType) throws Exception {
		//Test attachment
		Iterator<?> attachmentParts = message.getAttachments();
		while (attachmentParts.hasNext()) {
			AttachmentPart soapAttachmentPart = (AttachmentPart)attachmentParts.next();
			String attachment = StreamUtil.streamToString(soapAttachmentPart.getRawContent());
			//ContentID should be equal to the filename
			assertEquals(PART_NAME, soapAttachmentPart.getContentId());

			//Validate the attachment's content
			assertEquals(expectedAttachmentContent, attachment);

			//Make sure at least the content-type header has been set
			Iterator<?> headers = soapAttachmentPart.getAllMimeHeaders();
			String contentType = null;
			while (headers.hasNext()) {
				MimeHeader header = (MimeHeader) headers.next();
				if("Content-Type".equalsIgnoreCase(header.getName()))
					contentType = header.getValue();
			}
			assertEquals(expectedContentType, contentType);
		}
	}

	@Test
	/**
	 * Receive SOAP message with attachment
	 * Reply SOAP message with attachment
	 * @throws Throwable
	 */
	public void receiveAndSendMessageWithAttachmentsTest() throws Throwable {
		SOAPMessage request = createMessage("correct-soapmsg.xml", true, false);
		PipeLineSession session = new PipeLineSession();

		session.put("attachmentXmlSessionKey", MULTIPART_XML);
		session.put("part_file", ATTACHMENT2_CONTENT);

		SOAPProvider.setAttachmentXmlSessionKey("attachmentXmlSessionKey");
		SOAPProvider.setSession(session);

		SOAPMessage message = SOAPProvider.invoke(request);

		String result = XmlUtils.nodeToString(message.getSOAPPart());
		String expected = getFile("correct-soapmsg.xml").asString();
		assertEquals(expected.replaceAll("\r", ""), result.replaceAll("\r", ""));

		//Validate an attachment was sent to the listener
		assertAttachmentInSession(SOAPProvider.getSession());

		//Validate the listener returned an attachment back
		assertAttachmentInReceivedMessage(message);
	}

	@Test
	public void soapActionInSessionKeySOAP1_1() throws Throwable {
		// Soap protocol 1.1
		SOAPMessage request = createMessage("soapmsg1_1.xml", false, true);
		String value = "1.1-SoapAction";
		webServiceContext.getMessageContext().put("SOAPAction", value);
		SOAPProvider.invoke(request);
		webServiceContext.getMessageContext().clear();
		assertEquals(value, SOAPProvider.getSession().get("SOAPAction"));
	}

	@Test
	public void noSoapActionInSessionKeySOAP1_1() throws Throwable {
		// Soap protocol 1.1
		SOAPMessage request = createMessage("soapmsg1_1.xml", false, true);
		SOAPProvider.invoke(request);
		assertNull(SOAPProvider.getSession().get("SOAPAction"));
	}

	@Test
	public void soap1_1MessageWithActionInContentTypeHeader() throws Throwable {
		// Soap protocol 1.1
		SOAPMessage request = createMessage("soapmsg1_1.xml", false, true);
		String value = "ActionInContentTypeHeader";
		webServiceContext.getMessageContext().put("Content-Type", "application/soap+xml; action="+value);
		SOAPProvider.invoke(request);
		webServiceContext.getMessageContext().clear();
		assertNull(SOAPProvider.getSession().get("SOAPAction"));
	}

	@Test
	public void soapActionInSessionKeySOAP1_2ActionIsTheLastItem() throws Throwable {
		SOAPMessage request = createMessage("soapmsg1_2.xml");
		String value = "SOAP1_2ActionIsTheLastItem";
		webServiceContext.getMessageContext().put("Content-Type", "application/soap+xml; action="+value);
		SOAPProvider.invoke(request);
		webServiceContext.getMessageContext().clear();
		assertEquals(value, SOAPProvider.getSession().get("SOAPAction"));
	}

	@Test
	public void soapActionInSessionKeySOAP1_2ActionIsInMiddle() throws Throwable {
		SOAPMessage request = createMessage("soapmsg1_2.xml");
		String value = "SOAP1_2ActionIsInMiddle";
		webServiceContext.getMessageContext().put("Content-Type", "application/soap+xml; action="+value+";somethingelse");
		SOAPProvider.invoke(request);
		webServiceContext.getMessageContext().clear();
		assertEquals(value, SOAPProvider.getSession().get("SOAPAction"));
	}

	@Test
	public void noSoapActionInSessionKey1_2() throws Throwable {
		SOAPMessage request = createMessage("soapmsg1_2.xml");
		webServiceContext.getMessageContext().put("Content-Type", "application/soap+xml; somethingelse");
		SOAPProvider.invoke(request);
		webServiceContext.getMessageContext().clear();
		assertNull(SOAPProvider.getSession().get("SOAPAction"));
	}

	@Test
	public void emptySoapActionInSessionKey1_2() throws Throwable {
		SOAPMessage request = createMessage("soapmsg1_2.xml");
		webServiceContext.getMessageContext().put("Content-Type", "application/soap+xml; action; somethingelse");
		SOAPProvider.invoke(request);
		webServiceContext.getMessageContext().clear();
		assertNull(SOAPProvider.getSession().get("SOAPAction"));
	}

	@Test
	public void soap1_2MessageWithSOAPActionHeader() throws Throwable {
		SOAPMessage request = createMessage("soapmsg1_2.xml");
		webServiceContext.getMessageContext().put("SOAPAction", "action");
		SOAPProvider.invoke(request);
		webServiceContext.getMessageContext().clear();
		assertNull(SOAPProvider.getSession().get("SOAPAction"));
	}
}
